package io.provenance.objectstore.gateway.server

import io.grpc.stub.StreamObserver
import io.provenance.objectstore.gateway.GatewayGrpc
import io.provenance.objectstore.gateway.GatewayOuterClass
import io.provenance.objectstore.gateway.address
import io.provenance.objectstore.gateway.publicKey
import io.provenance.objectstore.gateway.server.interceptor.JwtServerInterceptor
import io.provenance.objectstore.gateway.service.ObjectService
import io.provenance.objectstore.gateway.service.ScopeFetchService
import io.provenance.objectstore.gateway.util.toByteString
import mu.KLogging
import org.lognet.springboot.grpc.GRpcService

@GRpcService(interceptors = [JwtServerInterceptor::class])
class ObjectStoreGatewayServer(
    private val scopeFetchService: ScopeFetchService,
    private val objectService: ObjectService,
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

    override fun putObject(
        request: GatewayOuterClass.PutObjectRequest,
        responseObserver: StreamObserver<GatewayOuterClass.PutObjectResponse>
    ) {
        objectService.putObject(request.objectBytes.toByteArray(), request.type.takeIf { it.isNotBlank() }, publicKey()).let {
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
        objectService.getObject(request.hash, address()).let { (objectBytes, type) ->
            responseObserver.onNext(
                GatewayOuterClass.FetchObjectByHashResponse.newBuilder()
                    .apply {
                        objectBuilder.setHash(request.hash)
                            .setObjectBytes(objectBytes.toByteString())
                        if (type != null) {
                            objectBuilder.type = type
                        }
                    }
                    .build()
            )
            responseObserver.onCompleted()
        }
    }
}
