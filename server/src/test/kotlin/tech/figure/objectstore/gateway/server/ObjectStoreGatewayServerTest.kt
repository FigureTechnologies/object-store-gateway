package tech.figure.objectstore.gateway.server

import Constants
import io.grpc.Context
import io.grpc.stub.StreamObserver
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyAll
import io.provenance.scope.encryption.ecies.ProvenanceKeyGenerator
import io.provenance.scope.encryption.util.getAddress
import io.provenance.scope.util.sha256String
import io.provenance.scope.util.toByteString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tech.figure.objectstore.gateway.GatewayOuterClass
import tech.figure.objectstore.gateway.helpers.getValidFetchObjectByHashRequest
import tech.figure.objectstore.gateway.helpers.getValidPutObjectRequest
import tech.figure.objectstore.gateway.helpers.getValidRequest
import tech.figure.objectstore.gateway.helpers.objectFromParts
import tech.figure.objectstore.gateway.service.ObjectService
import tech.figure.objectstore.gateway.service.ScopeFetchService
import java.security.KeyPair
import kotlin.random.Random

class ObjectStoreGatewayServerTest {
    lateinit var scopeFetchService: ScopeFetchService
    lateinit var objectService: ObjectService
    val keyPair: KeyPair = ProvenanceKeyGenerator.generateKeyPair()

    lateinit var server: ObjectStoreGatewayServer

    @BeforeEach
    fun setUp() {
        scopeFetchService = mockk()
        objectService = mockk()

        Context.current()
            .withValue(Constants.REQUESTOR_PUBLIC_KEY_CTX, keyPair.public)
            .withValue(Constants.REQUESTOR_ADDRESS_CTX, keyPair.public.getAddress(false))
            .attach()

        server = ObjectStoreGatewayServer(scopeFetchService, objectService)
    }

    @Test
    fun `fetchObject should succeed with a valid request`() {
        val request = getValidRequest()
        val responseObserver: StreamObserver<GatewayOuterClass.FetchObjectResponse> = mockk()

        val dummyRecords = listOf(
            GatewayOuterClass.Record.newBuilder()
                .setName("dummyRecordName")
                .addInputs(GatewayOuterClass.RecordObject.newBuilder().setObjectBytes(Random.nextBytes(100).toByteString()))
                .addOutputs(GatewayOuterClass.RecordObject.newBuilder().setObjectBytes(Random.nextBytes(100).toByteString()))
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

    @Test
    fun `putObject should succeed with a valid request without type`() {
        testSuccessfulPutObject(getValidPutObjectRequest())
    }

    @Test
    fun `putObject should succeed with a valid request with type`() {
        testSuccessfulPutObject(getValidPutObjectRequest("cool_type"))
    }

    fun testSuccessfulPutObject(request: GatewayOuterClass.PutObjectRequest) {
        val responseObserver: StreamObserver<GatewayOuterClass.PutObjectResponse> = mockk()

        val byteHash = request.`object`.toByteArray().sha256String()
        every { objectService.putObject(request.`object`, keyPair.public) } returns byteHash

        every { responseObserver.onNext(any()) } returns mockk()
        every { responseObserver.onCompleted() } returns mockk()

        server.putObject(request, responseObserver)

        verifyAll {
            responseObserver.onNext(
                GatewayOuterClass.PutObjectResponse.newBuilder()
                    .setHash(byteHash)
                    .build()
            )
            responseObserver.onCompleted()
        }
    }

    @Test
    fun `getObjectByHash should return object with no type`() {
        testSuccessfulGetObjectByHash(Random.nextBytes(100))
    }

    @Test
    fun `getObjectByHash should return object with type`() {
        testSuccessfulGetObjectByHash(Random.nextBytes(100), "my_type")
    }

    fun testSuccessfulGetObjectByHash(objectBytes: ByteArray, type: String? = null) {
        val responseObserver: StreamObserver<GatewayOuterClass.FetchObjectByHashResponse> = mockk()
        val ownerAddress = keyPair.public.getAddress(false)
        val obj = objectFromParts(objectBytes, type)

        val byteHash = objectBytes.sha256String()
        every { objectService.getObject(byteHash, ownerAddress) } returns obj

        every { responseObserver.onNext(any()) } returns mockk()
        every { responseObserver.onCompleted() } returns mockk()

        server.fetchObjectByHash(getValidFetchObjectByHashRequest(byteHash), responseObserver)

        verifyAll {
            responseObserver.onNext(
                GatewayOuterClass.FetchObjectByHashResponse.newBuilder()
                    .setObject(obj)
                    .build()
            )
            responseObserver.onCompleted()
        }
    }
}
