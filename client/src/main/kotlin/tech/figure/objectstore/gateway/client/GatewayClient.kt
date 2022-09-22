package tech.figure.objectstore.gateway.client

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.grpc.Deadline
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.stub.MetadataUtils
import io.provenance.scope.encryption.ecies.ECUtils
import io.provenance.scope.encryption.model.KeyRef
import io.provenance.scope.encryption.util.getAddress
import io.provenance.scope.util.toByteString
import tech.figure.objectstore.gateway.GatewayGrpc
import tech.figure.objectstore.gateway.GatewayOuterClass
import tech.figure.objectstore.gateway.GatewayOuterClass.GrantScopePermissionRequest
import tech.figure.objectstore.gateway.GatewayOuterClass.GrantScopePermissionResponse
import tech.figure.objectstore.gateway.GatewayOuterClass.RevokeScopePermissionRequest
import tech.figure.objectstore.gateway.GatewayOuterClass.RevokeScopePermissionResponse
import tech.figure.objectstore.gateway.shared.KeyRefSecP256K1Algorithm
import tech.figure.objectstore.gateway.util.toJwtMeta
import java.io.Closeable
import java.security.KeyPair
import java.security.PublicKey
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Date
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

    fun createJwt(keyPair: KeyPair, expiresAt: OffsetDateTime = OffsetDateTime.now().plusSeconds(60)): String =
        createJwt(keyPair.public, Algorithm.ECDSA256K(keyPair.public as ECPublicKey, keyPair.private as ECPrivateKey), expiresAt)

    fun createJwt(keyRef: KeyRef, expiresAt: OffsetDateTime = OffsetDateTime.now().plusSeconds(60)): String =
        createJwt(keyRef.publicKey, KeyRefSecP256K1Algorithm(keyRef), expiresAt)

    private fun createJwt(publicKey: PublicKey, algorithm: Algorithm, expiresAt: OffsetDateTime): String = JWT.create()
        .withIssuedAt(OffsetDateTime.now().toInstant().let(Date::from))
        .withExpiresAt(expiresAt.toInstant().let(Date::from))
        .withIssuer("object-store-gateway")
        .withClaim("sub", publicKey.let(ECUtils::publicKeyEncoded))
        .withClaim("addr", publicKey.getAddress(config.mainNet))
        .sign(algorithm)

    /**
     * Fetch scope data from gateway, creating a fresh JWT for authentication
     * @param scopeAddress the scope's address
     * @param keyPair the KeyPair of the key to sign the request with
     * @param timeout an optional timeout for the request/used in the request to expire signature
     */
    fun requestScopeData(scopeAddress: String, keyPair: KeyPair, timeout: Duration = Duration.ofSeconds(10)): GatewayOuterClass.FetchObjectResponse {
        val jwt = createJwt(keyPair, OffsetDateTime.now().plus(timeout))

        return requestScopeData(scopeAddress, jwt, timeout)
    }

    /**
     * Fetch scope data from gateway, creating a fresh JWT for authentication
     * @param scopeAddress the scope's address
     * @param keyRef the KeyRef of the key to sign the request with
     * @param timeout an optional timeout for the request/used in the request to expire signature
     */
    fun requestScopeData(scopeAddress: String, keyRef: KeyRef, timeout: Duration = Duration.ofSeconds(10)): GatewayOuterClass.FetchObjectResponse {
        val jwt = createJwt(keyRef, OffsetDateTime.now().plus(timeout))

        return requestScopeData(scopeAddress, jwt, timeout)
    }

    /**
     * Fetch scope data from gateway, using an existing JWT as authentication
     * @param scopeAddress the scope's address
     * @param jwt a provenance JWT (can be created using this client's `createJwt` methods)
     * @param timeout an optional timeout for the request
     */
    fun requestScopeData(scopeAddress: String, jwt: String, timeout: Duration = Duration.ofSeconds(10)): GatewayOuterClass.FetchObjectResponse {
        return gatewayStub.withDeadline(Deadline.after(timeout.seconds, TimeUnit.SECONDS))
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(jwt.toJwtMeta()))
            .fetchObject(
                GatewayOuterClass.FetchObjectRequest.newBuilder()
                    .setScopeAddress(scopeAddress)
                    .build()
            ).get()
    }

    /**
     * Write an object to object store via the gateway. The object will be encrypted by the server's key and the address in the JWT will be permissioned
     * to retrieve the object.
     *
     * @param objectBytes the raw data to store
     * @param objectType (optional) the type of data that this represents. This is for reference at the time of retrieval if needed
     * @param jwt a provenance JWT (can be created using this client's `createJwt` methods)
     * @param timeout an optional timeout for the request
     *
     * @return a proto containing the hash of the stored object. This hash can be used for future retrieval via [getObject].
     *  Note that this is not the hash of the provided objectBytes, but rather the sha256 hash of a serialized proto containing the provided objectBytes and objectType
     */
    fun putObject(objectBytes: ByteArray, objectType: String? = null, jwt: String, timeout: Duration = Duration.ofSeconds(10)): GatewayOuterClass.PutObjectResponse {
        return gatewayStub.withDeadline(Deadline.after(timeout.seconds, TimeUnit.SECONDS))
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(jwt.toJwtMeta()))
            .putObject(
                GatewayOuterClass.PutObjectRequest.newBuilder()
                    .apply {
                        objectBuilder.setObjectBytes(objectBytes.toByteString())
                        if (objectType != null) {
                            objectBuilder.type = objectType
                        }
                    }
                    .build()
            ).get()
    }

    /**
     * Write an object to object store via the gateway. The object will be encrypted by the server's key and the address corresponding
     * to the provided KeyPair's public key will be able to retrieve the object.
     *
     * @param objectBytes the raw data to store
     * @param objectType (optional) the type of data that this represents. This is for reference at the time of retrieval if needed
     * @param keyPair the KeyPair of the key to sign the request with
     * @param timeout an optional timeout for the request/used in the request to expire signature
     *
     * @return a proto containing the hash of the stored object. This hash can be used for future retrieval via [getObject].
     *  Note that this is not the hash of the provided objectBytes, but rather the sha256 hash of a serialized proto containing the provided objectBytes and objectType
     */
    fun putObject(objectBytes: ByteArray, objectType: String? = null, keyPair: KeyPair, timeout: Duration = Duration.ofSeconds(10)): GatewayOuterClass.PutObjectResponse {
        val jwt = createJwt(keyPair, OffsetDateTime.now().plus(timeout))

        return putObject(objectBytes, objectType, jwt, timeout)
    }

    /**
     * Write an object to object store via the gateway. The object will be encrypted by the server's key and the address corresponding
     * to the provided KeyRef's public key will be able to retrieve the object.
     *
     * @param objectBytes the raw data to store
     * @param objectType (optional) the type of data that this represents. This is for reference at the time of retrieval if needed
     * @param keyRef the KeyRef of the key to sign the request with
     * @param timeout an optional timeout for the request/used in the request to expire signature
     *
     * @return a proto containing the hash of the stored object. This hash can be used for future retrieval via [getObject].
     *  Note that this is not the hash of the provided objectBytes, but rather the sha256 hash of a serialized proto containing the provided objectBytes and objectType
     */
    fun putObject(objectBytes: ByteArray, objectType: String? = null, keyRef: KeyRef, timeout: Duration = Duration.ofSeconds(10)): GatewayOuterClass.PutObjectResponse {
        val jwt = createJwt(keyRef, OffsetDateTime.now().plus(timeout))

        return putObject(objectBytes, objectType, jwt, timeout)
    }

    /**
     * Retrieve an object from object store via the gateway. The object will only be returned if the address contained within the authenticated jwt
     * has been granted access via the gateway.
     *
     * @param hash the hash of the object to retrieve (as returned by [putObject])
     * @param jwt a provenance JWT (can be created using this client's `createJwt` methods)
     * @param timeout an optional timeout for the request
     *
     * @return a proto containing an object that holds the provided objectBytes and objectType as provided by [putObject]
     */
    fun getObject(hash: String, jwt: String, timeout: Duration = Duration.ofSeconds(10)): GatewayOuterClass.FetchObjectByHashResponse {
        return gatewayStub.withDeadline(Deadline.after(timeout.seconds, TimeUnit.SECONDS))
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(jwt.toJwtMeta()))
            .fetchObjectByHash(
                GatewayOuterClass.FetchObjectByHashRequest.newBuilder()
                    .setHash(hash)
                    .build()
            ).get()
    }

    /**
     * Retrieve an object from object store via the gateway. The object will only be returned if the address corresponding to the provided
     * keyPair's public key has been granted access via the gateway.
     *
     * @param hash the hash of the object to retrieve (as returned by [putObject])
     * @param keyPair the KeyPair of the key to sign the request with
     * @param timeout an optional timeout for the request/used in the request to expire signature
     *
     * @return a proto containing an object that holds the provided objectBytes and objectType as provided by [putObject]
     */
    fun getObject(hash: String, keyPair: KeyPair, timeout: Duration = Duration.ofSeconds(10)): GatewayOuterClass.FetchObjectByHashResponse {
        val jwt = createJwt(keyPair, OffsetDateTime.now().plus(timeout))

        return getObject(hash, jwt, timeout)
    }

    /**
     * Retrieve an object from object store via the gateway. The object will only be returned if the address corresponding to the provided
     * keyRef's public key has been granted access via the gateway.
     *
     * @param hash the hash of the object to retrieve (as returned by [putObject])
     * @param keyRef the KeyRef of the key to sign the request with
     * @param timeout an optional timeout for the request/used in the request to expire signature
     *
     * @return a proto containing an object that holds the provided objectBytes and objectType as provided by [putObject]
     */
    fun getObject(hash: String, keyRef: KeyRef, timeout: Duration = Duration.ofSeconds(10)): GatewayOuterClass.FetchObjectByHashResponse {
        val jwt = createJwt(keyRef, OffsetDateTime.now().plus(timeout))

        return getObject(hash, jwt, timeout)
    }

    /**
     * Grants permission to the grantee to view the records associated with the given Provenance Blockchain Scope
     * address.  The caller of this function has to be either the value owner of the given scope, or the administrator
     * of the gateway application.
     *
     * @param scopeAddress The bech32 Provenance Blockchain Scope address for which to grant access
     * @param granteeAddress The bech32 Provenance Blockchain Account address to which access will be granted
     * @param jwt a provenance JWT (can be created using this client's `createJwt` methods)
     * @param grantId A free-form grant identifier that will be appended to the record created in the scope_permissions
     * table for targeted revokes.  If omitted, the record created will have a null grant id
     * @param timeout an optional timeout for the request
     */
    fun grantScopePermission(
        scopeAddress: String,
        granteeAddress: String,
        jwt: String,
        grantId: String? = null,
        timeout: Duration = Duration.ofSeconds(10),
    ): GrantScopePermissionResponse = gatewayStub.withDeadline(Deadline.after(timeout.seconds, TimeUnit.SECONDS))
        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(jwt.toJwtMeta()))
        .grantScopePermission(
            GrantScopePermissionRequest.newBuilder().also { request ->
                request.scopeAddress = scopeAddress
                request.granteeAddress = granteeAddress
                grantId?.also { request.grantId = it }
            }.build()
        )
        .get()

    /**
     * Grants permission to the grantee to view the records associated with the given Provenance Blockchain Scope
     * address.  The caller of this function has to be either the value owner of the given scope, or the administrator
     * of the gateway application.
     *
     * @param scopeAddress The bech32 Provenance Blockchain Scope address for which to grant access
     * @param granteeAddress The bech32 Provenance Blockchain Account address to which access will be granted
     * @param keyPair the KeyPair of the key to sign the request with
     * @param grantId A free-form grant identifier that will be appended to the record created in the scope_permissions
     * table for targeted revokes.  If omitted, the record created will have a null grant id
     * @param timeout an optional timeout for the request
     */
    fun grantScopePermission(
        scopeAddress: String,
        granteeAddress: String,
        keyPair: KeyPair,
        grantId: String? = null,
        timeout: Duration = Duration.ofSeconds(10),
    ): GrantScopePermissionResponse = grantScopePermission(
        scopeAddress = scopeAddress,
        granteeAddress = granteeAddress,
        jwt = createJwt(keyPair, OffsetDateTime.now().plus(timeout)),
        grantId = grantId,
        timeout = timeout,
    )

    /**
     * Grants permission to the grantee to view the records associated with the given Provenance Blockchain Scope
     * address.  The caller of this function has to be one of the following to avoid request rejection:
     * - The associated scope's value owner
     * - The master administrator of the gateway application
     *
     * @param scopeAddress The bech32 Provenance Blockchain Scope address for which to grant access
     * @param granteeAddress The bech32 Provenance Blockchain Account address to which access will be granted
     * @param keyRef the KeyRef of the key to sign the request with
     * @param grantId A free-form grant identifier that will be appended to the record created in the scope_permissions
     * table for targeted revokes.  If omitted, the record created will have a null grant id
     * @param timeout an optional timeout for the request
     */
    fun grantScopePermission(
        scopeAddress: String,
        granteeAddress: String,
        keyRef: KeyRef,
        grantId: String? = null,
        timeout: Duration = Duration.ofSeconds(10),
    ): GrantScopePermissionResponse = grantScopePermission(
        scopeAddress = scopeAddress,
        granteeAddress = granteeAddress,
        jwt = createJwt(keyRef, OffsetDateTime.now().plus(timeout)),
        grantId = grantId,
        timeout = timeout,
    )

    /**
     * Revokes permission from the grantee to view the records associated with the given Provenance Blockchain Scope
     * address.  The caller of this function has to be one of the following to avoid request rejection:
     * - The associated scope's value owner
     * - The master administrator of the gateway application
     * - The grantee (accounts that have been granted permissions to a scope can revoke their own permissions if desired)
     *
     * @param scopeAddress The bech32 Provenance Blockchain Scope address for which to revoke access
     * @param granteeAddress The bech32 Provenance Blockchain Account address for which access will be revoked
     * @param jwt a provenance JWT (can be created using this client's `createJwt` methods)
     * @param grantId A free-form grant identifier that will be used to query for existing scope_permissions records.
     * If this value is omitted, all grants for the given scope and grantee combination will be revoked, versus targeting
     * a singular unique record with the given id.
     * @param timeout an optional timeout for the request
     */
    fun revokeScopePermission(
        scopeAddress: String,
        granteeAddress: String,
        jwt: String,
        grantId: String? = null,
        timeout: Duration = Duration.ofSeconds(10),
    ): RevokeScopePermissionResponse = gatewayStub.withDeadline(Deadline.after(timeout.seconds, TimeUnit.SECONDS))
        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(jwt.toJwtMeta()))
        .revokeScopePermission(
            RevokeScopePermissionRequest.newBuilder().also { request ->
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
     * @param keyPair the KeyPair of the key to sign the request with
     * @param grantId A free-form grant identifier that will be used to query for existing scope_permissions records.
     * If this value is omitted, all grants for the given scope and grantee combination will be revoked, versus targeting
     * a singular unique record with the given id.
     * @param timeout an optional timeout for the request
     */
    fun revokeScopePermission(
        scopeAddress: String,
        granteeAddress: String,
        keyPair: KeyPair,
        grantId: String? = null,
        timeout: Duration = Duration.ofSeconds(10),
    ): RevokeScopePermissionResponse = revokeScopePermission(
        scopeAddress = scopeAddress,
        granteeAddress = granteeAddress,
        jwt = createJwt(keyPair, OffsetDateTime.now().plus(timeout)),
        grantId = grantId,
        timeout = timeout,
    )

    /**
     * Revokes permission from the grantee to view the records associated with the given Provenance Blockchain Scope
     * address.  The caller of this function has to be one of the following to avoid request rejection:
     * - The associated scope's value owner
     * - The master administrator of the gateway application
     * - The grantee (accounts that have been granted permissions to a scope can revoke their own permissions if desired)
     *
     * @param scopeAddress The bech32 Provenance Blockchain Scope address for which to revoke access
     * @param granteeAddress The bech32 Provenance Blockchain Account address for which access will be revoked
     * @param keyRef the KeyRef of the key to sign the request with
     * @param grantId A free-form grant identifier that will be used to query for existing scope_permissions records.
     * If this value is omitted, all grants for the given scope and grantee combination will be revoked, versus targeting
     * a singular unique record with the given id.
     * @param timeout an optional timeout for the request
     */
    fun revokeScopePermission(
        scopeAddress: String,
        granteeAddress: String,
        keyRef: KeyRef,
        grantId: String? = null,
        timeout: Duration = Duration.ofSeconds(10),
    ): RevokeScopePermissionResponse = revokeScopePermission(
        scopeAddress = scopeAddress,
        granteeAddress = granteeAddress,
        jwt = createJwt(keyRef, OffsetDateTime.now().plus(timeout)),
        grantId = grantId,
        timeout = timeout,
    )

    override fun close() {
        channel.shutdown()
    }
}
