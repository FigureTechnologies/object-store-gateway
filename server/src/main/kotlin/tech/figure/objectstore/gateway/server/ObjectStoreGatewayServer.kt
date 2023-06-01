package tech.figure.objectstore.gateway.server

import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.provenance.scope.encryption.model.KeyRef
import io.provenance.scope.encryption.util.getAddress
import io.provenance.scope.encryption.util.toPublicKey
import kotlinx.coroutines.flow.Flow
import mu.KLogging
import org.lognet.springboot.grpc.GRpcService
import org.springframework.beans.factory.annotation.Qualifier
import tech.figure.objectstore.gateway.GatewayGrpcKt
import tech.figure.objectstore.gateway.GatewayOuterClass
import tech.figure.objectstore.gateway.GatewayOuterClass.BatchGrantObjectPermissionsRequest
import tech.figure.objectstore.gateway.GatewayOuterClass.BatchGrantObjectPermissionsRequest.GrantTargetCase
import tech.figure.objectstore.gateway.GatewayOuterClass.BatchGrantObjectPermissionsResponse
import tech.figure.objectstore.gateway.GatewayOuterClass.BatchGrantScopePermissionRequest
import tech.figure.objectstore.gateway.GatewayOuterClass.BatchGrantScopePermissionResponse
import tech.figure.objectstore.gateway.GatewayOuterClass.FetchObjectByHashResponse
import tech.figure.objectstore.gateway.GatewayOuterClass.FetchObjectRequest
import tech.figure.objectstore.gateway.GatewayOuterClass.FetchObjectResponse
import tech.figure.objectstore.gateway.GatewayOuterClass.GrantObjectPermissionsRequest
import tech.figure.objectstore.gateway.GatewayOuterClass.GrantObjectPermissionsResponse
import tech.figure.objectstore.gateway.GatewayOuterClass.GrantScopePermissionResponse
import tech.figure.objectstore.gateway.GatewayOuterClass.PutObjectResponse
import tech.figure.objectstore.gateway.GatewayOuterClass.RegisterExistingObjectResponse
import tech.figure.objectstore.gateway.GatewayOuterClass.RevokeObjectPermissionsResponse
import tech.figure.objectstore.gateway.GatewayOuterClass.RevokeScopePermissionResponse
import tech.figure.objectstore.gateway.address
import tech.figure.objectstore.gateway.configuration.BeanQualifiers
import tech.figure.objectstore.gateway.configuration.ProvenanceProperties
import tech.figure.objectstore.gateway.exception.InvalidInputException
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
) : GatewayGrpcKt.GatewayCoroutineImplBase() {

    companion object : KLogging() {
        const val DEFAULT_UNKNOWN_DESCRIPTION: String = "An unexpected error occurred.  Please try again later"
    }

    override suspend fun fetchObject(request: FetchObjectRequest): FetchObjectResponse = scopeFetchService
        .fetchScopeForGrantee(request.scopeAddress, publicKey(), request.granterAddress.takeIf { it.isNotBlank() })
        .let {
            FetchObjectResponse.newBuilder()
                .setScopeId(request.scopeAddress)
                .addAllRecords(it)
                .build()
        }

    override suspend fun putObject(
        request: GatewayOuterClass.PutObjectRequest,
    ): PutObjectResponse = objectService
        .putObject(request.`object`, publicKey(), request.additionalAudienceKeysList.map { it.toPublicKey() }, useRequesterKey = request.useRequesterKey)
        .let { PutObjectResponse.newBuilder().setHash(it).build() }

    override suspend fun registerExistingObject(
        request: GatewayOuterClass.RegisterExistingObjectRequest,
    ): RegisterExistingObjectResponse = objectService
        .registerExistingObject(request.hash, publicKey(), request.granteeAddressList)
        .let { RegisterExistingObjectResponse.newBuilder().setRequest(request).build() }

    override suspend fun fetchObjectByHash(
        request: GatewayOuterClass.FetchObjectByHashRequest,
    ): FetchObjectByHashResponse = objectService.getObject(request.hash, address())
        .let { obj -> FetchObjectByHashResponse.newBuilder().setObject(obj).build() }

    override suspend fun grantObjectPermissions(
        request: GrantObjectPermissionsRequest,
    ): GrantObjectPermissionsResponse = objectService
        .grantAccess(request.hash, request.granteeAddress, address())
        .let {
            GrantObjectPermissionsResponse.newBuilder()
                .setHash(request.hash)
                .setGranteeAddress(request.granteeAddress)
                .build()
        }

    override fun batchGrantObjectPermissions(request: BatchGrantObjectPermissionsRequest): Flow<BatchGrantObjectPermissionsResponse> {
        val (granteeAddress, targetHashes) = when (request.grantTargetCase) {
            GrantTargetCase.ALL_HASHES -> request.allHashes.granteeAddress to null
            GrantTargetCase.SPECIFIED_HASHES -> request.specifiedHashes.let { it.granteeAddress to it.targetHashesList }
            else -> throw InvalidInputException("A grant target must be supplied")
        }
        return objectService.batchGrantAccess(
            granteeAddress = granteeAddress,
            granterAddress = address(),
            targetHashes = targetHashes,
        )
    }

    override suspend fun revokeObjectPermissions(
        request: GatewayOuterClass.RevokeObjectPermissionsRequest,
    ): RevokeObjectPermissionsResponse = objectService
        .revokeAccess(request.hash, address(), request.granteeAddressList)
        .let { RevokeObjectPermissionsResponse.newBuilder().setRequest(request).build() }

    override suspend fun grantScopePermission(
        request: GatewayOuterClass.GrantScopePermissionRequest,
    ): GrantScopePermissionResponse {
        val (grantResponse, sourceDetails) = processScopeGrant(
            requesterAddress = address(),
            scopeAddress = request.scopeAddress,
            granteeAddress = request.granteeAddress,
            grantId = request.grantId,
            requestType = "Manual Grant",
        )
        val getResponse: (accepted: Boolean, granterAddress: String?) -> GrantScopePermissionResponse = { accepted, granterAddress ->
            GrantScopePermissionResponse.newBuilder().also { rpcResp ->
                rpcResp.request = request
                rpcResp.grantAccepted = accepted
                granterAddress?.also { rpcResp.granterAddress = it }
            }.build()
        }
        return when (grantResponse) {
            is GrantResponse.Accepted -> getResponse(true, grantResponse.granterAddress)
            is GrantResponse.Rejected -> {
                logger.warn("REJECTED $sourceDetails: ${grantResponse.message}")
                getResponse(false, null)
            }
            is GrantResponse.Error -> {
                logger.error("ERROR $sourceDetails", grantResponse.cause)
                throw StatusRuntimeException(Status.UNKNOWN.withDescription(DEFAULT_UNKNOWN_DESCRIPTION))
            }
        }
    }

    override suspend fun batchGrantScopePermission(request: BatchGrantScopePermissionRequest): BatchGrantScopePermissionResponse {
        if (request.granteesList.isEmpty()) {
            throw StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("At least one grantee is required"))
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
            // caller can cross-reference the intercepted request versus what was produced in that list to determine any failures,
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
        return BatchGrantScopePermissionResponse.newBuilder().also { batchResponse ->
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
    }

    override suspend fun revokeScopePermission(
        request: GatewayOuterClass.RevokeScopePermissionRequest,
    ): RevokeScopePermissionResponse {
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
        val getResponse: (accepted: Boolean, revokedGrants: Int?) -> RevokeScopePermissionResponse = { accepted, revokedGrants ->
            RevokeScopePermissionResponse.newBuilder().also { rpcResp ->
                rpcResp.request = request
                rpcResp.revokeAccepted = accepted
                revokedGrants?.also { rpcResp.revokedGrantsCount = it }
            }.build()
        }
        return when (revokeResponse) {
            is RevokeResponse.Accepted -> getResponse(true, revokeResponse.revokedGrantsCount)
            is RevokeResponse.Rejected -> {
                logger.warn("REJECTED $sourceDetails: ${revokeResponse.message}")
                getResponse(false, null)
            }
            is RevokeResponse.Error -> {
                logger.error("ERROR $sourceDetails", revokeResponse.cause)
                throw StatusRuntimeException(Status.UNKNOWN.withDescription(DEFAULT_UNKNOWN_DESCRIPTION))
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
