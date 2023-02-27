package tech.figure.objectstore.gateway.client

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.provenance.scope.encryption.ecies.ECUtils
import io.provenance.scope.encryption.model.KeyRef
import io.provenance.scope.encryption.util.getAddress
import tech.figure.objectstore.gateway.shared.KeyRefSecP256K1Algorithm
import java.security.KeyPair
import java.security.PublicKey
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Date

/**
 * Defines all the various ways in which a JWT can be derived in order to interact with the object store gateway's
 * verification processes.
 */
sealed interface GatewayJwt {
    companion object {
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(10)

        /**
         * Generates a JWT string based on the input values.  Allows this functionality to be shared across various
         * implementations of the interface without exposing it to external consumers.
         *
         * @param publicKey The caller's key that will be encoded into the JWT.
         * @param algorithm The algorithm used to sign the key, ensuring that the server can verify that the sender
         * owns the key that is provided in the sub claim.
         * @param mainNet Whether or not the address encoded into the JWT should be a Provenance Blockchain mainnet
         * bech32 account address versus testnet.
         * @param expiresAt The instant at which the JWT should be considered expired and automatically rejected.
         */
        private fun createJwtInternal(
            publicKey: PublicKey,
            algorithm: Algorithm,
            mainNet: Boolean,
            expiresAt: OffsetDateTime,
        ): String = JWT.create()
            .withIssuedAt(OffsetDateTime.now().toInstant().let(Date::from))
            .withExpiresAt(expiresAt.toInstant().let(Date::from))
            .withIssuer("object-store-gateway")
            .withClaim("sub", publicKey.let(ECUtils::publicKeyEncoded))
            .withClaim("addr", publicKey.getAddress(mainNet = mainNet))
            .sign(algorithm)
    }

    /**
     * A function that all instances of GatewayJwt should implement, providing the ability to create a JWT string from
     * the various components included in an implementation.  This is utilized by the client to derive a JWT that will
     * be packaged in the rpc metadata sent to Object Store Gateway.
     */
    fun createJwt(mainNet: Boolean, expiresAt: OffsetDateTime): String

    /**
     * Allows direct specification of a pre-generated JWT string.  Useful for implementations that wish to use their
     * own form of signing.  The provided value must still conform to the SecP256K1 standard and include valid 'sub',
     * 'addr' and expiration claims.
     *
     * @param jwt The direct string version of a valid JWT.
     */
    data class DirectJwt(val jwt: String) : GatewayJwt {
        override fun createJwt(mainNet: Boolean, expiresAt: OffsetDateTime): String = jwt
    }

    /**
     * Provides JWT generation from a Java KeyPair.  This will create a valid SecP256K1 signature for the given keys,
     * if possible.  This implementation holds all necessary information for creating as many instances of a JWT as is
     * necessary, and can be declared safely as a singleton.
     *
     * @param keyPair The public and private keys of the requesting entity.
     */
    @Suppress("DEPRECATION")
    data class KeyPairJwt(val keyPair: KeyPair) : GatewayJwt {
        override fun createJwt(mainNet: Boolean, expiresAt: OffsetDateTime): String = createJwtInternal(
            publicKey = keyPair.public,
            algorithm = Algorithm.ECDSA256K(keyPair.public as ECPublicKey, keyPair.private as ECPrivateKey),
            mainNet = mainNet,
            expiresAt = expiresAt,
        )
    }

    /**
     * Provides JWT generation for Provenance Scope KeyRef.  This will create a valid SecP256K1 signature for the given
     * keys, if possible.  This implementation holds all necessary information for creating as many instances of a JWT
     * as is necessary, and can be declared safely as a singleton.
     *
     * @param keyRef The public and private keys of the requesting entity.
     */
    data class KeyRefJwt(val keyRef: KeyRef) : GatewayJwt {
        override fun createJwt(mainNet: Boolean, expiresAt: OffsetDateTime): String = createJwtInternal(
            publicKey = keyRef.publicKey,
            algorithm = KeyRefSecP256K1Algorithm(keyRef),
            mainNet = mainNet,
            expiresAt = expiresAt,
        )
    }
}
