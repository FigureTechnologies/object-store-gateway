package io.provenance.objectstore.gateway.server

import io.grpc.stub.StreamObserver
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyAll
import io.provenance.objectstore.gateway.GatewayOuterClass
import io.provenance.objectstore.gateway.exception.SignatureValidationException
import io.provenance.objectstore.gateway.exception.TimestampValidationException
import io.provenance.objectstore.gateway.helpers.getValidRequest
import io.provenance.objectstore.gateway.service.ScopeFetchService
import io.provenance.objectstore.gateway.util.toByteString
import io.provenance.scope.encryption.ecies.ProvenanceKeyGenerator
import io.provenance.scope.encryption.util.toPublicKey
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.KeyPair
import kotlin.random.Random
import kotlin.test.assertContains

class ObjectStoreGatewayServerTest {
    lateinit var scopeFetchService: ScopeFetchService
    lateinit var responseObserver: StreamObserver<GatewayOuterClass.FetchObjectResponse>
    val keyPair: KeyPair = ProvenanceKeyGenerator.generateKeyPair()

    lateinit var server: ObjectStoreGatewayServer

    @BeforeEach
    fun setUp() {
        scopeFetchService = mockk()
        responseObserver = mockk()

        server = ObjectStoreGatewayServer(scopeFetchService)
    }

    @Test
    fun `fetchObject should reject a request without an expiration time`() {
        val request = keyPair.getValidRequest().toBuilder()
            .apply {
                paramsBuilder.clearExpiration()
            }.build()

        val exception = assertThrows<TimestampValidationException> {
            server.fetchObject(request, responseObserver)
        }

        assertContains(exception.status.description!!, "Missing expiration", message = "Appropriate error should be thrown for missing expiration")
    }

    @Test
    fun `fetchObject should reject a request with a past expiration time`() {
        val request = keyPair.getValidRequest(-1000)

        val exception = assertThrows<TimestampValidationException> {
            server.fetchObject(request, responseObserver)
        }

        assertContains(exception.status.description!!, "Expired expiration", message = "Appropriate error should be thrown for past expiration")
    }

    @Test
    fun `fetchObject should reject a request with an invalid signature`() {
        val request = keyPair.getValidRequest().toBuilder()
            .apply {
                signatureBuilder.clearSignature()
            }.build()

        val exception = assertThrows<SignatureValidationException> {
            server.fetchObject(request, responseObserver)
        }

        assertContains(exception.status.description!!, "Invalid signature", message = "Appropriate error should be thrown for invalid signature")
    }

    @Test
    fun `fetchObject should succeed with a valid request`() {
        val request = keyPair.getValidRequest()

        val dummyRecords = listOf(GatewayOuterClass.Record.newBuilder()
            .setName("dummyRecordName")
            .addInputs(GatewayOuterClass.RawObject.newBuilder().setObjectBytes(Random.nextBytes(100).toByteString()))
            .addOutputs(GatewayOuterClass.RawObject.newBuilder().setObjectBytes(Random.nextBytes(100).toByteString()))
            .build())

        every { scopeFetchService.fetchScope(request.params.scopeAddress, request.signature.signer.signingPublicKey.toPublicKey(), request.params.granterAddress.takeIf { it.isNotBlank() }) } returns dummyRecords

        every { responseObserver.onNext(any()) } returns mockk()
        every { responseObserver.onCompleted() } returns mockk()

        server.fetchObject(request, responseObserver)

        verifyAll {
            responseObserver.onNext(GatewayOuterClass.FetchObjectResponse.newBuilder()
                .setScopeId(request.params.scopeAddress)
                .addAllRecords(dummyRecords)
                .build())
            responseObserver.onCompleted()
        }
    }
}
