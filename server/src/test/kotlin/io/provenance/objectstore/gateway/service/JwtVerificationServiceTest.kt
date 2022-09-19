package io.provenance.objectstore.gateway.service

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.provenance.objectstore.gateway.exception.JwtValidationException
import io.provenance.scope.encryption.ecies.ECUtils
import io.provenance.scope.encryption.ecies.ProvenanceKeyGenerator
import io.provenance.scope.encryption.util.getAddress
import org.bouncycastle.asn1.x509.ObjectDigestInfo.publicKey
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.KeyPair
import java.security.PublicKey
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.time.OffsetDateTime
import java.util.Date
import kotlin.test.assertEquals

class JwtVerificationServiceTest {
    val keyPair = ProvenanceKeyGenerator.generateKeyPair()
    val otherKeyPair = ProvenanceKeyGenerator.generateKeyPair()
    val verificationService = JwtVerificationService()

    fun getJwt(expiration: OffsetDateTime?, signingKeyPair: KeyPair, publicKey: PublicKey? = signingKeyPair.public, address: String? = publicKey?.getAddress(false)): String = JWT.create()
        .apply {
            if (expiration != null) {
                withExpiresAt(Date.from(expiration.toInstant()))
            }
            if (publicKey != null) {
                withClaim(Constants.JWT_PUBLIC_KEY, ECUtils.publicKeyEncoded(publicKey))
            }
            if (address != null) {
                withClaim(Constants.JWT_ADDRESS_KEY, address)
            }
        }.sign(Algorithm.ECDSA256K(signingKeyPair.public as ECPublicKey, signingKeyPair.private as ECPrivateKey))

    @Test
    fun `should reject a jwt without an expiration time`() {
        val jwt = getJwt(expiration = null, signingKeyPair = keyPair)

        val exception = assertThrows<JwtValidationException> {
            verificationService.verifyJwtString(jwt)
        }

        assert(exception.message!!.contains("JWT expiration is required"))
    }

    @Test
    fun `should reject a jwt with an expired expiration`() {
        val jwt = getJwt(expiration = OffsetDateTime.now().minusSeconds(1), signingKeyPair = keyPair)

        val exception = assertThrows<JwtValidationException> {
            verificationService.verifyJwtString(jwt)
        }

        assert(exception.message!!.contains("The Token has expired"))
    }

    @Test
    fun `should reject a jwt with an invalid signature`() {
        val jwt = getJwt(expiration = OffsetDateTime.now().minusSeconds(1), signingKeyPair = keyPair, publicKey = otherKeyPair.public)

        val exception = assertThrows<JwtValidationException> {
            verificationService.verifyJwtString(jwt)
        }

        assert(exception.message!!.contains("The Token's Signature resulted invalid")) { """expected ${exception.message} to contain "The Token's Signature resulted invalid"""" }
    }

    @Test
    fun `should reject a jwt where the provided address is not derived from the provided public key`() {
        val jwt = getJwt(expiration = OffsetDateTime.now().plusSeconds(10), keyPair, address = otherKeyPair.public.getAddress(false))

        val exception = assertThrows<JwtValidationException> {
            verificationService.verifyJwtString(jwt)
        }

        assert(exception.message!!.contains("Jwt public key and address do not match"))
    }

    @Test
    fun `should return a successful result when all is well`() {
        val jwt = getJwt(expiration = OffsetDateTime.now().plusSeconds(10), keyPair)

        val result = verificationService.verifyJwtString(jwt)

        assertEquals(result.publicKey, keyPair.public, "The verification result public key should match what was in the jwt")
        assertEquals(result.address, keyPair.public.getAddress(false), "The verification result address should match that of the provided address")
    }

    @Test
    fun `should return a successful result when all is well for a mainnet address as well`() {
        val jwt = getJwt(expiration = OffsetDateTime.now().plusSeconds(10), keyPair, address = keyPair.public.getAddress(true))

        val result = verificationService.verifyJwtString(jwt)

        assertEquals(result.publicKey, keyPair.public, "The verification result public key should match what was in the jwt")
        assertEquals(result.address, keyPair.public.getAddress(true), "The verification result address should match that of the provided address")
    }
}
