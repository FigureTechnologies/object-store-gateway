package io.provenance.objectstore.gateway.server

import Constants
import io.grpc.Context
import io.grpc.stub.StreamObserver
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyAll
import io.provenance.objectstore.gateway.GatewayOuterClass
import io.provenance.objectstore.gateway.helpers.getValidRequest
import io.provenance.objectstore.gateway.service.ScopeFetchService
import io.provenance.objectstore.gateway.util.toByteString
import io.provenance.scope.encryption.ecies.ProvenanceKeyGenerator
import io.provenance.scope.encryption.util.getAddress
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.KeyPair
import kotlin.random.Random

class ObjectStoreGatewayServerTest {
    lateinit var scopeFetchService: ScopeFetchService
    lateinit var responseObserver: StreamObserver<GatewayOuterClass.FetchObjectResponse>
    val keyPair: KeyPair = ProvenanceKeyGenerator.generateKeyPair()

    lateinit var server: ObjectStoreGatewayServer

    @BeforeEach
    fun setUp() {
        scopeFetchService = mockk()
        responseObserver = mockk()

        Context.current()
            .withValue(Constants.REQUESTOR_PUBLIC_KEY_CTX, keyPair.public)
            .withValue(Constants.REQUESTOR_ADDRESS_CTX, keyPair.public.getAddress(false))
            .attach()

        server = ObjectStoreGatewayServer(scopeFetchService)
    }

    @Test
    fun `fetchObject should succeed with a valid request`() {
        val request = getValidRequest()

        val dummyRecords = listOf(
            GatewayOuterClass.Record.newBuilder()
                .setName("dummyRecordName")
                .addInputs(GatewayOuterClass.RawObject.newBuilder().setObjectBytes(Random.nextBytes(100).toByteString()))
                .addOutputs(GatewayOuterClass.RawObject.newBuilder().setObjectBytes(Random.nextBytes(100).toByteString()))
                .build()
        )

        every { scopeFetchService.fetchScope(request.scopeAddress, keyPair.public, request.granterAddress.takeIf { it.isNotBlank() }) } returns dummyRecords

        every { responseObserver.onNext(any()) } returns mockk()
        every { responseObserver.onCompleted() } returns mockk()

        server.fetchObject(request, responseObserver)

        verifyAll {
            responseObserver.onNext(
                GatewayOuterClass.FetchObjectResponse.newBuilder()
                    .setScopeId(request.scopeAddress)
                    .addAllRecords(dummyRecords)
                    .build()
            )
            responseObserver.onCompleted()
        }
    }
}
