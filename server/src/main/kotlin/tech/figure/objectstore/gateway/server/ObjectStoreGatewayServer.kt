package tech.figure.objectstore.gateway.server

import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import io.provenance.scope.encryption.model.KeyRef
import io.provenance.scope.encryption.util.getAddress
import io.provenance.scope.encryption.util.toPublicKey
import mu.KLogging
import org.lognet.springboot.grpc.GRpcService
import org.springframework.beans.factory.annotation.Qualifier
import tech.figure.objectstore.gateway.GatewayGrpc
import tech.figure.objectstore.gateway.GatewayOuterClass
import tech.figure.objectstore.gateway.GatewayOuterClass.BatchGrantScopePermissionRequest
import tech.figure.objectstore.gateway.GatewayOuterClass.BatchGrantScopePermissionResponse
import tech.figure.objectstore.gateway.GatewayOuterClass.GrantScopePermissionResponse
import tech.figure.objectstore.gateway.GatewayOuterClass.RevokeScopePermissionResponse
import tech.figure.objectstore.gateway.address
import tech.figure.objectstore.gateway.configuration.BeanQualifiers
import tech.figure.objectstore.gateway.configuration.ProvenanceProperties
import tech.figure.objectstore.gateway.publicKey
import tech.figure.objectstore.gateway.server.interceptor.JwtServerInterceptor
import tech.figure.objectstore.gateway.service.GrantResponse
import tech.figure.objectstore.gateway.service.ObjectService
import tech.figure.objectstore.gateway.service.RevokeResponse
import tech.figure.objectstore.gateway.service.ScopeFetchService
import tech.figure.objectstore.gateway.service.ScopePermissionsService

@GRpcService(interceptors = [JwtServerInterceptor::class])
class ObjectStoreGatewayServer(
    @Qualifier(BeanQualifiers.OBJECTSTORE_MASTER_KEY) private val masterKey: KeyRef,
    private val scopeFetchService: ScopeFetchService,
    private val scopePermissionsService: ScopePermissionsService,
    private val objectService: ObjectService,
    private val provenanceProperties: ProvenanceProperties,
) : GatewayGrpc.GatewayImplBase() {

    companion object : KLogging() {
        const val DEFAULT_UNKNOWN_DESCRIPTION: String = "An unexpected error occurred.  Please try again later"
    }

    override fun fetchObject(
        request: GatewayOuterClass.FetchObjectRequest,
        responseObserver: StreamObserver<GatewayOuterClass.FetchObjectResponse>
    ) {
        scopeFetchService.fetchScopeForGrantee(request.scopeAddress, publicKey(), request.granterAddress.takeIf { it.isNotBlank() }).let {
            responseObserver.onNext(
                GatewayOuterClass.FetchObjectResponse.newBuilder()
                    .setScopeId(request.scopeAddress)
                    .addAllRecords(it)
                    .build()
            )
        }
        responseObserver.onCompleted()
    }

    override fun putObject(
        request: GatewayOuterClass.PutObjectRequest,
        responseObserver: StreamObserver<GatewayOuterClass.PutObjectResponse>
    ) {
        objectService.putObject(request.`object`, publicKey(), request.additionalAudienceKeysList.map { it.toPublicKey() }, useRequesterKey = request.useRequesterKey).let {
        responseObserver.onNext(
            GatewayOuterClass.PutObjectResponse.newBuilder()
                .setHash(it)
                .build()
        )
    }
        responseObserver.onCompleted()
    }

    override fun registerExistingObject(
        request: GatewayOuterClass.RegisterExistingObjectRequest,
        responseObserver: StreamObserver<GatewayOuterClass.RegisterExistingObjectResponse>
    ) {
        objectService.registerExistingObject(request.hash, publicKey(), request.granteeAddressList).let {
            responseObserver.onNext(
                GatewayOuterClass.RegisterExistingObjectResponse.getDefaultInstance()
            )
        }
        responseObserver.onCompleted()
    }

    override fun fetchObjectByHash(
        request: GatewayOuterClass.FetchObjectByHashRequest,
        responseObserver: StreamObserver<GatewayOuterClass.FetchObjectByHashResponse>
    ) {
        objectService.getObject(request.hash, address()).let { obj ->
            responseObserver.onNext(
                GatewayOuterClass.FetchObjectByHashResponse.newBuilder()
                    .setObject(obj)
                    .build()
            )
            responseObserver.onCompleted()
        }
    }

    override fun revokeObjectPermissions(
        request: GatewayOuterClass.RevokeObjectPermissionsRequest,
        responseObserver: StreamObserver<GatewayOuterClass.RevokeObjectPermissionsResponse>
    ) {
        objectService.revokeAccess(request.hash, address(), request.granteeAddressList).let {
            responseObserver.onNext(
                GatewayOuterClass.RevokeObjectPermissionsResponse.getDefaultInstance()
            )
        }
        responseObserver.onCompleted()
    }

    override fun grantScopePermission(
        request: GatewayOuterClass.GrantScopePermissionRequest,
        responseObserver: StreamObserver<GrantScopePermissionResponse>,
    ) {
        val (grantResponse, sourceDetails) = processScopeGrant(
            requesterAddress = address(),
            scopeAddress = request.scopeAddress,
            granteeAddress = request.granteeAddress,
            grantId = request.grantId,
            requestType = "Manual Grant",
        )
        val respond: (accepted: Boolean, granterAddress: String?) -> Unit = { accepted, granterAddress ->
            responseObserver.onNext(
                GrantScopePermissionResponse.newBuilder().also { rpcResp ->
                    rpcResp.request = request
                    rpcResp.grantAccepted = accepted
                    granterAddress?.also { rpcResp.granterAddress = it }
                }.build()
            )
            responseObserver.onCompleted()
        }
        when (grantResponse) {
            is GrantResponse.Accepted -> respond(true, grantResponse.granterAddress)
            is GrantResponse.Rejected -> {
                logger.warn("REJECTED $sourceDetails: ${grantResponse.message}")
                respond(false, null)
            }
            is GrantResponse.Error -> {
                logger.error("ERROR $sourceDetails", grantResponse.cause)
                responseObserver.onError(StatusRuntimeException(Status.UNKNOWN.withDescription(DEFAULT_UNKNOWN_DESCRIPTION)))
            }
        }
    }

    override fun batchGrantScopePermission(
        request: BatchGrantScopePermissionRequest,
        responseObserver: StreamObserver<BatchGrantScopePermissionResponse>,
    ) {
        if (request.granteesList.isEmpty()) {
            responseObserver.onError(StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("At least one grantee is required")))
            return
        }
        val requesterAddress = address()
        val completedGrantDetails = request.granteesList.mapNotNull { grantee ->
            val (grantResponse, sourceDetails) = this.processScopeGrant(
                requesterAddress = requesterAddress,
                scopeAddress = request.scopeAddress,
                granteeAddress = grantee.granteeAddress,
                grantId = grantee.grantId,
                requestType = "Batch Grant",
            )
            // The response should only include grantee information when the grantees did not throw exceptions.  The
            // caller can cross-reference the sent request versus what was produced in that list to determine any failures,
            // if they care.
            when (grantResponse) {
                is GrantResponse.Accepted -> grantee to grantResponse.granterAddress
                is GrantResponse.Rejected -> {
                    logger.warn("REJECTED $sourceDetails: ${grantResponse.message}")
                    grantee to null
                }
                is GrantResponse.Error -> {
                    logger.error("ERROR $sourceDetails", grantResponse.cause)
                    null
                }
            }
        }
        responseObserver.onNext(
            BatchGrantScopePermissionResponse.newBuilder().also { batchResponse ->
                batchResponse.request = request
                completedGrantDetails.map { (grantee, granterAddress) ->
                    GrantScopePermissionResponse.newBuilder().also { grantResponse ->
                        grantResponse.requestBuilder.scopeAddress = request.scopeAddress
                        grantResponse.requestBuilder.granteeAddress = grantee.granteeAddress
                        grantResponse.requestBuilder.grantId = grantee.grantId
                        granterAddress?.also { grantResponse.granterAddress = it }
                        grantResponse.grantAccepted = granterAddress != null
                    }.build()
                }.also(batchResponse::addAllGrantResponses)
            }.build()
        )
        responseObserver.onCompleted()
    }

    override fun revokeScopePermission(
        request: GatewayOuterClass.RevokeScopePermissionRequest,
        responseObserver: StreamObserver<RevokeScopePermissionResponse>,
    ) {
        val requesterAddress = address()
        val grantId = request.grantId.takeIf { it.isNotBlank() }
        val sourceDetails = "Main revoke request by $requesterAddress for scope ${request.scopeAddress}, grantee ${request.granteeAddress}${if (grantId != null) ", grantId $grantId" else ""}"
        val revokeResponse = scopePermissionsService.processAccessRevoke(
            scopeAddress = request.scopeAddress,
            granteeAddress = request.granteeAddress,
            revokeSourceAddresses = setOf(requesterAddress),
            additionalAuthorizedAddresses = setOf(
                // The grantee should be able to remove their own grants upon request
                request.granteeAddress,
                // The application's admin should be able to manually revoke any permission that is desired
                masterKey.publicKey.getAddress(mainNet = provenanceProperties.mainNet),
            ),
            grantId = grantId,
            sourceDetails = sourceDetails,
        )
        val respond: (accepted: Boolean, revokedGrants: Int?) -> Unit = { accepted, revokedGrants ->
            responseObserver.onNext(
                RevokeScopePermissionResponse.newBuilder().also { rpcResp ->
                    rpcResp.request = request
                    rpcResp.revokeAccepted = accepted
                    revokedGrants?.also { rpcResp.revokedGrantsCount = it }
                }.build()
            )
            responseObserver.onCompleted()
        }
        when (revokeResponse) {
            is RevokeResponse.Accepted -> respond(true, revokeResponse.revokedGrantsCount)
            is RevokeResponse.Rejected -> {
                logger.warn("REJECTED $sourceDetails: ${revokeResponse.message}")
                respond(false, null)
            }
            is RevokeResponse.Error -> {
                logger.error("ERROR $sourceDetails", revokeResponse.cause)
                responseObserver.onError(StatusRuntimeException(Status.UNKNOWN.withDescription(DEFAULT_UNKNOWN_DESCRIPTION)))
            }
        }
    }

    /**
     * Helper function to more easily format scope grant requests for both the manual and batch routes.
     */
    private fun processScopeGrant(
        requesterAddress: String,
        scopeAddress: String,
        granteeAddress: String,
        grantId: String,
        requestType: String,
    ): Pair<GrantResponse, String> = "$requestType request by $requesterAddress for scope $scopeAddress, grantee $granteeAddress${if (grantId.isNotBlank()) ", grantId $grantId" else ""}".let { sourceDetails ->
        scopePermissionsService.processAccessGrant(
            scopeAddress = scopeAddress,
            granteeAddress = granteeAddress,
            grantSourceAddresses = setOf(requesterAddress),
            additionalAuthorizedAddresses = setOf(masterKey.publicKey.getAddress(mainNet = provenanceProperties.mainNet)),
            grantId = grantId.takeIf { it.isNotBlank() },
            sourceDetails = sourceDetails,
        ) to sourceDetails
    }
}
