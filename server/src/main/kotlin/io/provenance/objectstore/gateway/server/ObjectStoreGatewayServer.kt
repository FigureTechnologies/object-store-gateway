package io.provenance.objectstore.gateway.server

import io.grpc.stub.StreamObserver
import io.provenance.objectstore.gateway.GatewayGrpc
import io.provenance.objectstore.gateway.GatewayOuterClass
import io.provenance.objectstore.gateway.publicKey
import io.provenance.objectstore.gateway.server.interceptor.JwtServerInterceptor
import io.provenance.objectstore.gateway.service.ScopeFetchService
import mu.KLogging
import org.lognet.springboot.grpc.GRpcService

@GRpcService(interceptors = [JwtServerInterceptor::class])
class ObjectStoreGatewayServer(
    private val scopeFetchService: ScopeFetchService,
) : GatewayGrpc.GatewayImplBase() {

    companion object : KLogging()

    override fun fetchObject(
        request: GatewayOuterClass.FetchObjectRequest,
        responseObserver: StreamObserver<GatewayOuterClass.FetchObjectResponse>
    ) {
        scopeFetchService.fetchScope(request.scopeAddress, publicKey(), request.granterAddress.takeIf { it.isNotBlank() }).let {
            responseObserver.onNext(
                GatewayOuterClass.FetchObjectResponse.newBuilder()
                    .setScopeId(request.scopeAddress)
                    .addAllRecords(it)
                    .build()
            )
        }
        responseObserver.onCompleted()
    }
}
