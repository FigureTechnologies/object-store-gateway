package tech.figure.objectstore.gateway.client

import io.grpc.Deadline
import io.grpc.Metadata
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.stub.AbstractStub
import io.grpc.stub.MetadataUtils
import io.provenance.scope.util.toByteString
import tech.figure.objectstore.gateway.GatewayGrpc
import tech.figure.objectstore.gateway.GatewayOuterClass
import tech.figure.objectstore.gateway.GatewayOuterClass.GrantScopePermissionRequest
import tech.figure.objectstore.gateway.GatewayOuterClass.GrantScopePermissionResponse
import tech.figure.objectstore.gateway.GatewayOuterClass.PutObjectResponse
import tech.figure.objectstore.gateway.GatewayOuterClass.RevokeScopePermissionRequest
import tech.figure.objectstore.gateway.GatewayOuterClass.RevokeScopePermissionResponse
import java.io.Closeable
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

class GatewayClient(val config: ClientConfig) : Closeable {
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
     * Fetch scope data from gateway, using an existing JWT as authentication
     * @param scopeAddress the scope's address
     * @param jwt any instance of GatewayJwt for use in generating the proper JWT metadata for the request
     * @param timeout an optional timeout for the request that also controls the timeout for any generated jwt
     */
    fun requestScopeData(
        scopeAddress: String,
        jwt: GatewayJwt,
        timeout: Duration = GatewayJwt.DEFAULT_TIMEOUT,
    ): GatewayOuterClass.FetchObjectResponse = gatewayStub
        .withDeadline(Deadline.after(timeout.seconds, TimeUnit.SECONDS))
        .interceptJwt(jwt, timeout)
        .fetchObject(
            GatewayOuterClass.FetchObjectRequest.newBuilder()
                .setScopeAddress(scopeAddress)
                .build()
        )
        .get()

    /**
     * Write an object to object store via the gateway. The object will be encrypted by the server's key and the address in the JWT will be permissioned
     * to retrieve the object.
     *
     * @param objectBytes the raw data to store
     * @param objectType (optional) the type of data that this represents. This is for reference at the time of retrieval if needed
     * @param jwt any instance of GatewayJwt for use in generating the proper JWT metadata for the request
     * @param timeout an optional timeout for the request that also controls the timeout for any generated jwt
     *
     * @return a proto containing the hash of the stored object. This hash can be used for future retrieval via [getObject].
     *  Note that this is not the hash of the provided objectBytes, but rather the sha256 hash of a serialized proto containing the provided objectBytes and objectType
     */
    fun putObject(
        objectBytes: ByteArray,
        objectType: String? = null,
        jwt: GatewayJwt,
        timeout: Duration = Duration.ofSeconds(10),
    ): PutObjectResponse {
        return gatewayStub.withDeadline(Deadline.after(timeout.seconds, TimeUnit.SECONDS))
            .interceptJwt(jwt, timeout)
            .putObject(
                GatewayOuterClass.PutObjectRequest.newBuilder()
                    .apply {
                        objectBuilder.objectBytes = objectBytes.toByteString()
                        if (objectType != null) {
                            objectBuilder.type = objectType
                        }
                    }
                    .build()
            ).get()
    }

    /**
     * Retrieve an object from object store via the gateway. The object will only be returned if the address contained within the authenticated jwt
     * has been granted access via the gateway.
     *
     * @param hash the hash of the object to retrieve (as returned by [putObject])
     * @param jwt any instance of GatewayJwt for use in generating the proper JWT metadata for the request
     * @param timeout an optional timeout for the request that also controls the timeout for any generated jwt
     *
     * @return a proto containing an object that holds the provided objectBytes and objectType as provided by [putObject]
     */
    fun getObject(
        hash: String,
        jwt: GatewayJwt,
        timeout: Duration = Duration.ofSeconds(10),
    ): GatewayOuterClass.FetchObjectByHashResponse {
        return gatewayStub.withDeadline(Deadline.after(timeout.seconds, TimeUnit.SECONDS))
            .interceptJwt(jwt, timeout)
            .fetchObjectByHash(
                GatewayOuterClass.FetchObjectByHashRequest.newBuilder()
                    .setHash(hash)
                    .build()
            ).get()
    }

    /**
     * Grants permission to the grantee to view the records associated with the given Provenance Blockchain Scope
     * address.  The caller of this function has to be either the value owner of the given scope, or the administrator
     * of the gateway application.
     *
     * @param scopeAddress The bech32 Provenance Blockchain Scope address for which to grant access
     * @param granteeAddress The bech32 Provenance Blockchain Account address to which access will be granted
     * @param jwt any instance of GatewayJwt for use in generating the proper JWT metadata for the request
     * @param grantId A free-form grant identifier that will be appended to the record created in the scope_permissions
     * table for targeted revokes.  If omitted, the record created will have a null grant id
     * @param timeout an optional timeout for the request that also controls the timeout for any generated jwt
     */
    fun grantScopePermission(
        scopeAddress: String,
        granteeAddress: String,
        jwt: GatewayJwt,
        grantId: String? = null,
        timeout: Duration = Duration.ofSeconds(10),
    ): GrantScopePermissionResponse = gatewayStub.withDeadline(Deadline.after(timeout.seconds, TimeUnit.SECONDS))
        .interceptJwt(jwt, timeout)
        .grantScopePermission(
            GrantScopePermissionRequest.newBuilder().also { request ->
                request.scopeAddress = scopeAddress
                request.granteeAddress = granteeAddress
                grantId?.also { request.grantId = it }
            }.build()
        )
        .get()

    /**
     * Revokes permission from the grantee to view the records associated with the given Provenance Blockchain Scope
     * address.  The caller of this function has to be one of the following to avoid request rejection:
     * - The associated scope's value owner
     * - The master administrator of the gateway application
     * - The grantee (accounts that have been granted permissions to a scope can revoke their own permissions if desired)
     *
     * @param scopeAddress The bech32 Provenance Blockchain Scope address for which to revoke access
     * @param granteeAddress The bech32 Provenance Blockchain Account address for which access will be revoked
     * @param jwt any instance of GatewayJwt for use in generating the proper JWT metadata for the request
     * @param grantId A free-form grant identifier that will be used to query for existing scope_permissions records.
     * If this value is omitted, all grants for the given scope and grantee combination will be revoked, versus targeting
     * a singular unique record with the given id.
     * @param timeout an optional timeout for the request that also controls the timeout for any generated jwt
     */
    fun revokeScopePermission(
        scopeAddress: String,
        granteeAddress: String,
        jwt: GatewayJwt,
        grantId: String? = null,
        timeout: Duration = Duration.ofSeconds(10),
    ): RevokeScopePermissionResponse = gatewayStub.withDeadline(Deadline.after(timeout.seconds, TimeUnit.SECONDS))
        .interceptJwt(jwt, timeout)
        .revokeScopePermission(
            RevokeScopePermissionRequest.newBuilder().also { request ->
                request.scopeAddress = scopeAddress
                request.granteeAddress = granteeAddress
                grantId?.also { request.grantId = it }
            }.build()
        )
        .get()

    override fun close() {
        channel.shutdown()
    }

    /**
     * A helper extension function to dynamically append header metadata that includes a generated JWT to the
     * gatewayStub's requests
     */
    private fun <S : AbstractStub<S>> S.interceptJwt(jwt: GatewayJwt, timeout: Duration): S = this
        .withInterceptors(
            MetadataUtils.newAttachHeadersInterceptor(
                Metadata().apply {
                    this.put(
                        Constants.JWT_GRPC_HEADER_KEY,
                        jwt.createJwt(mainNet = config.mainNet, expiresAt = OffsetDateTime.now() + timeout),
                    )
                }
            )
        )
}
