package tech.figure.objectstore.gateway.server

import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import io.provenance.scope.encryption.model.KeyRef
import io.provenance.scope.encryption.util.getAddress
import mu.KLogging
import org.lognet.springboot.grpc.GRpcService
import tech.figure.objectstore.gateway.GatewayGrpc
import tech.figure.objectstore.gateway.GatewayOuterClass
import tech.figure.objectstore.gateway.GatewayOuterClass.GrantScopePermissionResponse
import tech.figure.objectstore.gateway.address
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
    private val masterKey: KeyRef,
    private val scopeFetchService: ScopeFetchService,
    private val scopePermissionsService: ScopePermissionsService,
    private val objectService: ObjectService,
    private val provenanceProperties: ProvenanceProperties,
) : GatewayGrpc.GatewayImplBase() {

    companion object : KLogging()

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

    override fun grantScopePermission(
        request: GatewayOuterClass.GrantScopePermissionRequest,
        responseObserver: StreamObserver<GrantScopePermissionResponse>,
    ) {
        val requesterAddress = publicKey().getAddress(mainNet = provenanceProperties.mainNet)
        val grantId = request.grantId.takeIf { it.isNotBlank() }
        val sourceDetails = "Manual grant request by $requesterAddress for scope ${request.scopeAddress}, grantee ${request.granteeAddress}${if (grantId != null) ", grantId $grantId" else ""}"
        val grantResponse = scopePermissionsService.processAccessGrant(
            scopeAddress = request.scopeAddress,
            granteeAddress = request.granteeAddress,
            grantSourceAddresses = listOf(requesterAddress),
            // The application's admin should be able to manually grant any permission that is desired
            additionalAuthorizedAddresses = listOf(masterKey.publicKey.getAddress(mainNet = provenanceProperties.mainNet)),
            grantId = grantId,
            sourceDetails = sourceDetails,
        )
        if (grantResponse is GrantResponse.Error) {
            logger.error("ERROR $sourceDetails", grantResponse.cause)
            responseObserver.onError(StatusRuntimeException(Status.UNKNOWN.withCause(grantResponse.cause)))
            return
        }
        responseObserver.onNext(
            GrantScopePermissionResponse.newBuilder().also { rpcResponse ->
                rpcResponse.request = request
                if (grantResponse is GrantResponse.Accepted) {
                    rpcResponse.grantAccepted = true
                    rpcResponse.granterAddress = grantResponse.granterAddress
                } else if (grantResponse is GrantResponse.Rejected) {
                    rpcResponse.grantAccepted = false
                    logger.warn("REJECTED $sourceDetails: ${grantResponse.message}")
                }
            }.build()
        )
        responseObserver.onCompleted()
    }

    override fun revokeScopePermission(
        request: GatewayOuterClass.RevokeScopePermissionRequest,
        responseObserver: StreamObserver<GatewayOuterClass.RevokeScopePermissionResponse>,
    ) {
        val requesterAddress = publicKey().getAddress(mainNet = provenanceProperties.mainNet)
        val grantId = request.grantId.takeIf { it.isNotBlank() }
        val sourceDetails = "Main revoke request by $requesterAddress for scope ${request.scopeAddress}, grantee ${request.granteeAddress}${if (grantId != null) ", grantId $grantId" else ""}"
        val revokeResponse = scopePermissionsService.processAccessRevoke(
            scopeAddress = request.scopeAddress,
            granteeAddress = request.granteeAddress,
            revokeSourceAddresses = listOf(requesterAddress),
            additionalAuthorizedAddresses = listOf(
                // The grantee should be able to remove their own grants upon request
                request.granteeAddress,
                masterKey.publicKey.getAddress(mainNet = provenanceProperties.mainNet),
            ),
            grantId = grantId,
            sourceDetails = sourceDetails,
        )
        if (revokeResponse is RevokeResponse.Error) {
            logger.error("ERROR $sourceDetails", revokeResponse.cause)
            responseObserver.onError(StatusRuntimeException(Status.UNKNOWN.withCause(revokeResponse.cause)))
        }
        responseObserver.onNext(
            GatewayOuterClass.RevokeScopePermissionResponse.newBuilder().also { rpcResponse ->
                rpcResponse.request = request
                if (revokeResponse is RevokeResponse.Accepted) {
                    rpcResponse.revokeAccepted = true
                    rpcResponse.revokedGrantsCount = revokeResponse.revokedGrantsCount
                } else if (revokeResponse is RevokeResponse.Rejected) {
                    rpcResponse.revokeAccepted = false
                    logger.warn("REJECTED $sourceDetails: ${revokeResponse.message}")
                }
            }.build()
        )
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
}
