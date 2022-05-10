package io.provenance.objectstore.gateway.client

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.grpc.Deadline
import io.grpc.Metadata
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.stub.MetadataUtils
import io.provenance.objectstore.gateway.GatewayGrpc
import io.provenance.objectstore.gateway.GatewayOuterClass
import io.provenance.scope.encryption.crypto.SignerImpl
import io.provenance.scope.encryption.ecies.ECUtils
import io.provenance.scope.encryption.model.KeyRef
import io.provenance.scope.encryption.util.getAddress
import io.provenance.scope.util.toProtoTimestamp
import java.io.Closeable
import java.security.KeyPair
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Date
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

    fun createJWT(keyPair: KeyPair, expiresAt: OffsetDateTime = OffsetDateTime.now().plusSeconds(60)): String = JWT.create()
        .withIssuedAt(OffsetDateTime.now().toInstant().let(Date::from))
        .withExpiresAt(expiresAt.toInstant().let(Date::from))
        .withIssuer("object-store-gateway")
        .withClaim("sub", keyPair.public.let(ECUtils::publicKeyEncoded))
        .withClaim("addr", keyPair.public.getAddress(config.mainNet))
        .sign(Algorithm.ECDSA256K(keyPair.public as ECPublicKey, keyPair.private as ECPrivateKey))

    /**
     * Fetch scope data from gateway, creating a fresh JWT for authentication
     * @param scopeAddress the scope's address
     * @param keyRef the KeyRef of the key to sign the request with/decrypt the received response
     * @param timeout an optional timeout for the request/used in the request to expire signature
     */
    fun requestScopeData(scopeAddress: String, keyPair: KeyPair, timeout: Duration = Duration.ofSeconds(10)): GatewayOuterClass.FetchObjectResponse {
        val jwt = createJWT(keyPair, OffsetDateTime.now().plus(timeout))

        return requestScopeData(scopeAddress, jwt, timeout)
    }

    /**
     * Fetch scope data from gateway, using an existing JWT as authentication
     * @param scopeAddress the scope's address
     * @param keyRef the KeyRef of the key to sign the request with/decrypt the received response
     * @param timeout an optional timeout for the request/used in the request to expire signature
     */
    fun requestScopeData(scopeAddress: String, jwt: String, timeout: Duration = Duration.ofSeconds(10)): GatewayOuterClass.FetchObjectResponse {

        val metadata = Metadata().apply {
            put(Constants.JWT_GRPC_HEADER_KEY, jwt)
        }

        return gatewayStub.withDeadline(Deadline.after(timeout.seconds, TimeUnit.SECONDS))
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
            .fetchObject(GatewayOuterClass.FetchObjectRequest.newBuilder()
                .setScopeAddress(scopeAddress)
                .build()
            ).get()
    }

    override fun close() {
        channel.shutdown()
    }
}
