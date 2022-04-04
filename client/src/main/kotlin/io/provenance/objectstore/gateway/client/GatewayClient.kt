package io.provenance.objectstore.gateway.client

import io.grpc.Deadline
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.provenance.objectstore.gateway.GatewayGrpc
import io.provenance.objectstore.gateway.GatewayOuterClass
import io.provenance.scope.encryption.crypto.SignerImpl
import io.provenance.scope.encryption.model.KeyRef
import io.provenance.scope.util.toProtoTimestamp
import java.io.Closeable
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

class GatewayClient(val config: ClientConfig): Closeable {
    private val channel = NettyChannelBuilder.forAddress(config.gatewayUri.host, config.gatewayUri.port)
        .apply {
            if (config.gatewayUri.scheme == "grpcs") {
                useTransportSecurity()
            } else {
                usePlaintext()
            }
        }
        .executor(config.executor)
        .maxInboundMessageSize(config.inboundMessageSize)
        .idleTimeout(config.idleTimeout.first, config.idleTimeout.second)
        .keepAliveTime(config.keepAliveTime.first, config.keepAliveTime.second)
        .keepAliveTimeout(config.keepAliveTimeout.first, config.keepAliveTimeout.second)
        .also { builder -> config.channelConfigLambda(builder) }
        .build()

    private val gatewayStub = GatewayGrpc.newFutureStub(channel)

    /**
     * Fetch scope data from gateway, signing the request and decrypting the response with the provided key
     * @param scopeAddress the scope's address
     * @param keyRef the KeyRef of the key to sign the request with/decrypt the received response
     * @param timeout an optional timeout for the request/used in the request to expire signature
     */
    fun requestScopeData(scopeAddress: String, keyRef: KeyRef, timeout: Duration = Duration.ofSeconds(10)): GatewayOuterClass.FetchObjectResponse {
        val params = GatewayOuterClass.FetchObjectParams.newBuilder()
            .setScopeAddress(scopeAddress)
            .setExpiration(OffsetDateTime.now().plusSeconds(timeout.seconds).toProtoTimestamp())
            .build()

        val signer = keyRef.signer().apply {
            deterministic = true
            hashType = SignerImpl.Companion.HashType.SHA512
        }
        val signature = signer.sign(params)

        return gatewayStub.withDeadline(Deadline.after(timeout.seconds, TimeUnit.SECONDS))
            .fetchObject(GatewayOuterClass.FetchObjectRequest.newBuilder()
                .setParams(params)
                .setSignature(signature)
                .build()
            ).get()
    }

    override fun close() {
        channel.shutdown()
    }
}
