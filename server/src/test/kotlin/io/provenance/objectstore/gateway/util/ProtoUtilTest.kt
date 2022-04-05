package io.provenance.objectstore.gateway.util

import io.provenance.objectstore.gateway.GatewayOuterClass
import io.provenance.objectstore.gateway.model.ScopePermissionsTable.scopeAddress
import io.provenance.scope.encryption.crypto.SignerImpl
import io.provenance.scope.encryption.ecies.ProvenanceKeyGenerator
import io.provenance.scope.encryption.model.DirectKeyRef
import io.provenance.scope.encryption.util.toKeyPair
import io.provenance.scope.sdk.toPublicKeyProto
import io.provenance.scope.util.toByteString
import io.provenance.scope.util.toProtoTimestamp
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.PrivateKey
import java.time.OffsetDateTime
import kotlin.math.sign
import kotlin.random.Random
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtoUtilTest {
    lateinit var key: KeyPair

    @BeforeEach
    fun setUp() {
        key = ProvenanceKeyGenerator.generateKeyPair()
    }

    @Test
    fun `Verify validateSignature rejects missing signature`() {
        val request = key.getValidRequest().toBuilder()
            .clearSignature()
            .build()

        val valid = request.validateSignature()

        assertFalse(valid, "validateSignature should return false on a missing signature")
    }

    @Test
    fun `Verify validateSignature rejects invalid signature`() {
        val request = key.getValidRequest().toBuilder()
            .apply {
                signatureBuilder.signature = "totallyNotASignature"
            }.build()

        val valid = request.validateSignature()

        assertFalse(valid, "validateSignature should return false on an invalid signature")
    }

    @Test
    fun `Verify validateSignature rejects valid signature from different key`() {
        val request = key.getValidRequest().toBuilder()
            .apply {
                signatureBuilder.signerBuilder.signingPublicKey = ProvenanceKeyGenerator.generateKeyPair().public.toPublicKeyProto()
            }.build()

        val valid = request.validateSignature()

        assertFalse(valid, "validateSignature should return false on a valid signature from a different key")
    }

    @Test
    fun `Verify validateSignature accepts a valid signature from the specified key`() {
        val request = key.getValidRequest()

        val valid = request.validateSignature()

        assertTrue(valid, "The signature should validate")
    }

    private fun KeyPair.getValidRequest(expirationSeconds: Long = 1000): GatewayOuterClass.FetchObjectRequest {
        val params = GatewayOuterClass.FetchObjectParams.newBuilder()
            .setScopeAddress("myCoolScope")
            .setExpiration(OffsetDateTime.now().plusSeconds(expirationSeconds).toProtoTimestamp())
            .build()

        val signer = DirectKeyRef(this). signer().apply {
            deterministic = true
            hashType = SignerImpl.Companion.HashType.SHA512
        }
        val signature = signer.sign(params)

        return GatewayOuterClass.FetchObjectRequest.newBuilder()
            .setParams(params)
            .setSignature(signature)
            .build()
    }
}
