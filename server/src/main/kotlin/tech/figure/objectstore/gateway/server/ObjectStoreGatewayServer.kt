package tech.figure.objectstore.gateway.server

import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import io.provenance.scope.encryption.model.KeyRef
import io.provenance.scope.encryption.util.getAddress
import mu.KLogging
import org.lognet.springboot.grpc.GRpcService
import org.springframework.beans.factory.annotation.Qualifier
import tech.figure.objectstore.gateway.GatewayGrpc
import tech.figure.objectstore.gateway.GatewayOuterClass
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
        objectService.putObject(request.`object`, publicKey()).let {
            responseObserver.onNext(
                GatewayOuterClass.PutObjectResponse.newBuilder()
                    .setHash(it)
                    .build()
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

    override fun grantScopePermission(
        request: GatewayOuterClass.GrantScopePermissionRequest,
        responseObserver: StreamObserver<GrantScopePermissionResponse>,
    ) {
        val requesterAddress = address()
        val grantId = request.grantId.takeIf { it.isNotBlank() }
        val sourceDetails = "Manual grant request by $requesterAddress for scope ${request.scopeAddress}, grantee ${request.granteeAddress}${if (grantId != null) ", grantId $grantId" else ""}"
        val grantResponse = scopePermissionsService.processAccessGrant(
            scopeAddress = request.scopeAddress,
            granteeAddress = request.granteeAddress,
            grantSourceAddresses = setOf(requesterAddress),
            // The application's admin should be able to manually grant any permission that is desired
            additionalAuthorizedAddresses = setOf(masterKey.publicKey.getAddress(mainNet = provenanceProperties.mainNet)),
            grantId = grantId,
            sourceDetails = sourceDetails,
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
}
