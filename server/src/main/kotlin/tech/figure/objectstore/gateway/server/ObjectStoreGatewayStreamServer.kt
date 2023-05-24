package tech.figure.objectstore.gateway.server

import kotlinx.coroutines.flow.Flow
import org.lognet.springboot.grpc.GRpcService
import tech.figure.objectstore.gateway.GatewayGrpcKt
import tech.figure.objectstore.gateway.GatewayOuterClass.BatchGrantObjectPermissionsRequest
import tech.figure.objectstore.gateway.GatewayOuterClass.BatchGrantObjectPermissionsRequest.GrantTargetCase
import tech.figure.objectstore.gateway.GatewayOuterClass.GrantObjectPermissionsResponse
import tech.figure.objectstore.gateway.address
import tech.figure.objectstore.gateway.exception.InvalidInputException
import tech.figure.objectstore.gateway.server.interceptor.JwtServerInterceptor
import tech.figure.objectstore.gateway.service.ObjectService

@GRpcService(interceptors = [JwtServerInterceptor::class])
class ObjectStoreGatewayStreamServer(private val objectService: ObjectService) : GatewayGrpcKt.GatewayCoroutineImplBase() {
    override fun batchGrantObjectPermissions(request: BatchGrantObjectPermissionsRequest): Flow<GrantObjectPermissionsResponse> {
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
}
