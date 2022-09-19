package io.provenance.objectstore.gateway.shared

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.provenance.objectstore.gatway.shared.KeyRefSecP256K1Algorithm
import io.provenance.scope.encryption.ecies.ECUtils
import io.provenance.scope.encryption.ecies.ProvenanceKeyGenerator
import io.provenance.scope.encryption.model.DirectKeyRef
import io.provenance.scope.encryption.util.getAddress
import org.junit.Test
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.time.OffsetDateTime
import java.util.Date
import kotlin.test.assertEquals

class AlgorithmTest {
    val keyPair = ProvenanceKeyGenerator.generateKeyPair()
    val keyRef = DirectKeyRef(keyPair)

    @Test
    fun `built-in secp256k1 JWT verification is compatible with KeyRef implementation`() {
        val jwt = JWT.create()
            .withIssuedAt(OffsetDateTime.now().toInstant().let(Date::from))
            .withExpiresAt(OffsetDateTime.now().plusSeconds(10).toInstant().let(Date::from))
            .withIssuer("me")
            .withClaim(Constants.JWT_PUBLIC_KEY, ECUtils.publicKeyEncoded(keyPair.public))
            .withClaim(Constants.JWT_ADDRESS_KEY, keyPair.public.getAddress(false))
            .sign(KeyRefSecP256K1Algorithm(keyRef))

        val result = JWT.require(Algorithm.ECDSA256K(keyPair.public as ECPublicKey, null))
            .build()
            .verify(jwt)

        assertEquals(result.getClaim(Constants.JWT_PUBLIC_KEY).asString(), ECUtils.publicKeyEncoded(keyPair.public))
        assertEquals(result.getClaim(Constants.JWT_ADDRESS_KEY).asString(), keyPair.public.getAddress(false))
    }

    @Test
    fun `keyRef can verify its own signature`() {
        val jwt = JWT.create()
            .withIssuedAt(OffsetDateTime.now().toInstant().let(Date::from))
            .withExpiresAt(OffsetDateTime.now().plusSeconds(10).toInstant().let(Date::from))
            .withIssuer("me")
            .withClaim(Constants.JWT_PUBLIC_KEY, ECUtils.publicKeyEncoded(keyPair.public))
            .withClaim(Constants.JWT_ADDRESS_KEY, keyPair.public.getAddress(false))
            .sign(KeyRefSecP256K1Algorithm(keyRef))

        val result = JWT.require(KeyRefSecP256K1Algorithm(keyRef))
            .build()
            .verify(jwt)

        assertEquals(result.getClaim(Constants.JWT_PUBLIC_KEY).asString(), ECUtils.publicKeyEncoded(keyPair.public))
        assertEquals(result.getClaim(Constants.JWT_ADDRESS_KEY).asString(), keyPair.public.getAddress(false))
    }

    @Test
    fun `keyRef can verify a standard JWT signature`() {
        val jwt = JWT.create()
            .withIssuedAt(OffsetDateTime.now().toInstant().let(Date::from))
            .withExpiresAt(OffsetDateTime.now().plusSeconds(10).toInstant().let(Date::from))
            .withIssuer("me")
            .withClaim(Constants.JWT_PUBLIC_KEY, ECUtils.publicKeyEncoded(keyPair.public))
            .withClaim(Constants.JWT_ADDRESS_KEY, keyPair.public.getAddress(false))
            .sign(Algorithm.ECDSA256K(keyPair.public as ECPublicKey, keyPair.private as ECPrivateKey))

        val result = JWT.require(KeyRefSecP256K1Algorithm(keyRef))
            .build()
            .verify(jwt)

        assertEquals(result.getClaim(Constants.JWT_PUBLIC_KEY).asString(), ECUtils.publicKeyEncoded(keyPair.public))
        assertEquals(result.getClaim(Constants.JWT_ADDRESS_KEY).asString(), keyPair.public.getAddress(false))
    }
}
