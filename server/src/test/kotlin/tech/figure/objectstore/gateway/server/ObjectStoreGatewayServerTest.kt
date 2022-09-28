package tech.figure.objectstore.gateway.server

import Constants
import io.grpc.Context
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyAll
import io.provenance.hdwallet.ec.extensions.toJavaECKeyPair
import io.provenance.hdwallet.wallet.Account
import io.provenance.metadata.v1.PartyType
import io.provenance.scope.encryption.ecies.ProvenanceKeyGenerator
import io.provenance.scope.encryption.util.getAddress
import io.provenance.scope.util.MetadataAddress
import io.provenance.scope.util.sha256String
import io.provenance.scope.util.toByteString
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import tech.figure.objectstore.gateway.GatewayOuterClass
import tech.figure.objectstore.gateway.GatewayOuterClass.GrantScopePermissionRequest
import tech.figure.objectstore.gateway.GatewayOuterClass.GrantScopePermissionResponse
import tech.figure.objectstore.gateway.GatewayOuterClass.RevokeScopePermissionRequest
import tech.figure.objectstore.gateway.GatewayOuterClass.RevokeScopePermissionResponse
import tech.figure.objectstore.gateway.configuration.ProvenanceProperties
import tech.figure.objectstore.gateway.helpers.bech32Address
import tech.figure.objectstore.gateway.helpers.createErrorSlot
import tech.figure.objectstore.gateway.helpers.genRandomAccount
import tech.figure.objectstore.gateway.helpers.getValidFetchObjectByHashRequest
import tech.figure.objectstore.gateway.helpers.getValidPutObjectRequest
import tech.figure.objectstore.gateway.helpers.getValidRequest
import tech.figure.objectstore.gateway.helpers.keyRef
import tech.figure.objectstore.gateway.helpers.mockScopeResponse
import tech.figure.objectstore.gateway.helpers.mockkObserver
import tech.figure.objectstore.gateway.helpers.objectFromParts
import tech.figure.objectstore.gateway.helpers.queryGrantCount
import tech.figure.objectstore.gateway.model.ScopePermissionsTable
import tech.figure.objectstore.gateway.repository.ScopePermissionsRepository
import tech.figure.objectstore.gateway.service.AddressVerificationService
import tech.figure.objectstore.gateway.service.ObjectService
import tech.figure.objectstore.gateway.service.ScopeFetchService
import tech.figure.objectstore.gateway.service.ScopePermissionsService
import java.security.KeyPair
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull

@SpringBootTest
class ObjectStoreGatewayServerTest {
    lateinit var addressVerificationService: AddressVerificationService
    lateinit var scopeFetchService: ScopeFetchService
    lateinit var scopePermissionsService: ScopePermissionsService
    lateinit var scopePermissionsRepository: ScopePermissionsRepository
    lateinit var objectService: ObjectService
    lateinit var provenanceProperties: ProvenanceProperties
    val keyPair: KeyPair = ProvenanceKeyGenerator.generateKeyPair()
    val masterAccount: Account = genRandomAccount()

    lateinit var server: ObjectStoreGatewayServer

    val defaultGranter: String = genRandomAccount().bech32Address
    val scopeAddress = MetadataAddress.forScope(UUID.randomUUID()).toString()
    val defaultGrantee = genRandomAccount().bech32Address

    @BeforeEach
    fun clearDb() {
        transaction { ScopePermissionsTable.deleteAll() }
    }

    fun setUpBaseServices(
        accountAddresses: Set<String> = setOf(defaultGranter),
        contextKeyPair: KeyPair = keyPair,
    ) {
        scopeFetchService = mockk()
        scopePermissionsRepository = ScopePermissionsRepository()
        objectService = mockk()
        provenanceProperties = mockk()
        addressVerificationService = AddressVerificationService(provenanceProperties = provenanceProperties)

        every { provenanceProperties.mainNet } returns false

        Context.current()
            .withValue(Constants.REQUESTOR_PUBLIC_KEY_CTX, contextKeyPair.public)
            .withValue(Constants.REQUESTOR_ADDRESS_CTX, contextKeyPair.public.getAddress(false))
            .attach()

        scopePermissionsService = ScopePermissionsService(
            accountAddresses = accountAddresses,
            addressVerificationService = addressVerificationService,
            scopeFetchService = scopeFetchService,
            scopePermissionsRepository = scopePermissionsRepository,
        )
        server = ObjectStoreGatewayServer(
            masterKey = masterAccount.keyRef,
            scopeFetchService = scopeFetchService,
            scopePermissionsService = scopePermissionsService,
            objectService = objectService,
            provenanceProperties = provenanceProperties,
        )
    }

    fun setUpScopePermissionValues() {
        every { scopeFetchService.fetchScope(any(), any(), any()) } returns mockScopeResponse(
            address = scopeAddress,
            owners = setOf(
                PartyType.PARTY_TYPE_OWNER to keyPair.public.getAddress(false),
                PartyType.PARTY_TYPE_AFFILIATE to defaultGranter,
            ),
            valueOwnerAddress = keyPair.public.getAddress(false),
        )
    }

    @Test
    fun `fetchObject should succeed with a valid request`() {
        setUpBaseServices()
        val request = getValidRequest()
        val responseObserver: StreamObserver<GatewayOuterClass.FetchObjectResponse> = mockkObserver()

        val dummyRecords = listOf(
            GatewayOuterClass.Record.newBuilder()
                .setName("dummyRecordName")
                .addInputs(GatewayOuterClass.RecordObject.newBuilder().setObjectBytes(Random.nextBytes(100).toByteString()))
                .addOutputs(GatewayOuterClass.RecordObject.newBuilder().setObjectBytes(Random.nextBytes(100).toByteString()))
                .build()
        )

        every { scopeFetchService.fetchScopeForGrantee(request.scopeAddress, keyPair.public, request.granterAddress.takeIf { it.isNotBlank() }) } returns dummyRecords

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
        setUpBaseServices()
        testSuccessfulPutObject(getValidPutObjectRequest())
    }

    @Test
    fun `putObject should succeed with a valid request with type`() {
        setUpBaseServices()
        testSuccessfulPutObject(getValidPutObjectRequest("cool_type"))
    }

    fun testSuccessfulPutObject(request: GatewayOuterClass.PutObjectRequest) {
        val responseObserver: StreamObserver<GatewayOuterClass.PutObjectResponse> = mockkObserver()

        val byteHash = request.`object`.toByteArray().sha256String()
        every { objectService.putObject(request.`object`, keyPair.public) } returns byteHash

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
        setUpBaseServices()
        testSuccessfulGetObjectByHash(Random.nextBytes(100))
    }

    @Test
    fun `getObjectByHash should return object with type`() {
        setUpBaseServices()
        testSuccessfulGetObjectByHash(Random.nextBytes(100), "my_type")
    }

    fun testSuccessfulGetObjectByHash(objectBytes: ByteArray, type: String? = null) {
        val responseObserver = mockkObserver<GatewayOuterClass.FetchObjectByHashResponse>()
        val ownerAddress = keyPair.public.getAddress(false)
        val obj = objectFromParts(objectBytes, type)

        val byteHash = objectBytes.sha256String()
        every { objectService.getObject(byteHash, ownerAddress) } returns obj

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

    @Test
    fun `grantScopePermission should respond with a success to an authorized caller no grant id`() {
        testGrantScopePermission(contextKeyPair = keyPair, grantId = null)
    }

    @Test
    fun `grantScopePermission should respond with a success to an authorized caller with grant id`() {
        testGrantScopePermission(contextKeyPair = keyPair, grantId = "some-grant-yolo")
    }

    @Test
    fun `grantScopePermission should respond with a success to the master key no grant id`() {
        testGrantScopePermission(contextKeyPair = masterAccount.keyPair.toJavaECKeyPair(), grantId = null)
    }

    @Test
    fun `grantScopePermission should respond with a success to the master key with grant id`() {
        testGrantScopePermission(contextKeyPair = masterAccount.keyPair.toJavaECKeyPair(), grantId = "MASTER GRANT SHELLYEAH")
    }

    @Test
    fun `grantScopePermission should reject grants that are not authorized`() {
        // Use some random account as the requesting account to verify that the requester has to be someone relevant
        setUpBaseServices(contextKeyPair = genRandomAccount().keyPair.toJavaECKeyPair())
        setUpScopePermissionValues()
        val responseObserver = mockkObserver<GrantScopePermissionResponse>()
        val request = getPermissionGrant()
        server.grantScopePermission(request = request, responseObserver = responseObserver)
        verify(inverse = true) { responseObserver.onError(any()) }
        verifyAll {
            responseObserver.onNext(
                GrantScopePermissionResponse.newBuilder().also { response ->
                    response.request = request
                    response.grantAccepted = false
                }.build()
            )
            responseObserver.onCompleted()
        }
        assertEquals(
            expected = 0,
            actual = transaction { ScopePermissionsTable.selectAll().count() },
            message = "No scope permissions should have ever been assigned, proving that the grant was rejected",
        )
    }

    @Test
    fun `grantScopePermission should return expected unknown response on exception`() {
        setUpBaseServices()
        // Setup scope fetch service to freak out, simulating a provenance communication error
        every { scopeFetchService.fetchScope(any(), any(), any()) } throws IllegalStateException("Provenance is actin' up again")
        val responseObserver = mockkObserver<GrantScopePermissionResponse>()
        val exceptionSlot = responseObserver.createErrorSlot<StatusRuntimeException>()
        server.grantScopePermission(request = getPermissionGrant(), responseObserver = responseObserver)
        verifyAll(inverse = true) {
            responseObserver.onNext(any())
            responseObserver.onCompleted()
        }
        assertEquals(
            expected = Status.UNKNOWN.code,
            actual = exceptionSlot.captured.status.code,
            message = "The UNKNOWN status should be emitted when an exception is encountered",
        )
        assertNull(
            actual = exceptionSlot.captured.status.cause,
            message = "The source exception should not be sent to the consumer",
        )
        assertEquals(
            expected = ObjectStoreGatewayServer.DEFAULT_UNKNOWN_DESCRIPTION,
            actual = exceptionSlot.captured.status.description,
            message = "The expected description should be sent",
        )
    }

    @Test
    fun `revokeScopePermission should respond with a success to an authorized caller no grant id`() {
        testRevokeScopePermission(contextKeyPair = keyPair, grantId = null)
    }

    @Test
    fun `revokeScopePermission should respond with a success to an authorized caller with grant id`() {
        testRevokeScopePermission(contextKeyPair = keyPair, grantId = "my-best-grant-ever")
    }

    @Test
    fun `revokeScopePermission should respond with a success to the master key no grant id`() {
        testRevokeScopePermission(contextKeyPair = masterAccount.keyPair.toJavaECKeyPair(), grantId = null)
    }

    @Test
    fun `revokeScopePermission should respond with a success to the master key with grant id`() {
        testRevokeScopePermission(contextKeyPair = masterAccount.keyPair.toJavaECKeyPair(), grantId = "THE MASTER GRANT")
    }

    @Test
    fun `revokeScopePermission should reject requests that are not authorized`() {
        // Use some random account as the requesting account to verify that the requester has to be someone relevant
        setUpBaseServices(contextKeyPair = genRandomAccount().keyPair.toJavaECKeyPair())
        setUpScopePermissionValues()
        val responseObserver = mockkObserver<RevokeScopePermissionResponse>()
        val request = getPermissionRevoke()
        server.revokeScopePermission(request = request, responseObserver = responseObserver)
        verify(inverse = true) { responseObserver.onError(any()) }
        verifyAll {
            responseObserver.onNext(
                RevokeScopePermissionResponse.newBuilder().also { response ->
                    response.request = request
                    response.revokeAccepted = false
                }.build()
            )
            responseObserver.onCompleted()
        }
    }

    @Test
    fun `revokeScopePermission should return expected unknown response on exception`() {
        setUpBaseServices()
        // Setup scope fetch service to freak out, simulating a provenance communication error
        every { scopeFetchService.fetchScope(any(), any(), any()) } throws IllegalArgumentException("That ol' blockchain is givin' us trouble")
        val responseObserver = mockkObserver<RevokeScopePermissionResponse>()
        val exceptionSlot = responseObserver.createErrorSlot<StatusRuntimeException>()
        server.revokeScopePermission(request = getPermissionRevoke(), responseObserver = responseObserver)
        verifyAll(inverse = true) {
            responseObserver.onNext(any())
            responseObserver.onCompleted()
        }
        assertEquals(
            expected = Status.UNKNOWN.code,
            actual = exceptionSlot.captured.status.code,
            message = "The UNKNOWN status should be emitted when an exception is encountered",
        )
        assertNull(
            actual = exceptionSlot.captured.status.cause,
            message = "The source exception should not be sent to the consumer",
        )
        assertEquals(
            expected = ObjectStoreGatewayServer.DEFAULT_UNKNOWN_DESCRIPTION,
            actual = exceptionSlot.captured.status.description,
            message = "The expected description should be sent",
        )
    }

    private fun testGrantScopePermission(
        contextKeyPair: KeyPair,
        grantId: String?,
    ) {
        setUpBaseServices(contextKeyPair = contextKeyPair)
        setUpScopePermissionValues()
        val responseObserver = mockkObserver<GrantScopePermissionResponse>()
        val request = getPermissionGrant(grantId = grantId)
        server.grantScopePermission(request = request, responseObserver = responseObserver)
        verify(inverse = true) { responseObserver.onError(any()) }
        verifyAll {
            responseObserver.onNext(
                GrantScopePermissionResponse.newBuilder().also { response ->
                    response.request = request
                    response.granterAddress = defaultGranter
                    response.grantAccepted = true
                }.build()
            )
            responseObserver.onCompleted()
        }
        assertEquals(
            expected = 1,
            actual = getGrantCount(grantId = grantId),
            message = "A record with the provided specifications should be created by the request",
        )
    }

    private fun testRevokeScopePermission(
        contextKeyPair: KeyPair,
        grantId: String?,
    ) {
        setUpBaseServices(contextKeyPair = contextKeyPair)
        setUpScopePermissionValues()
        server.grantScopePermission(request = getPermissionGrant(grantId = grantId), responseObserver = mockkObserver())
        assertEquals(
            expected = 1,
            actual = getGrantCount(grantId = grantId),
            message = "A record with the provided specifications should be created by the request",
        )
        val responseObserver = mockkObserver<RevokeScopePermissionResponse>()
        val request = getPermissionRevoke(grantId = grantId)
        server.revokeScopePermission(request = request, responseObserver = responseObserver)
        verify(inverse = true) { responseObserver.onError(any()) }
        verifyAll {
            responseObserver.onNext(
                RevokeScopePermissionResponse.newBuilder().also { response ->
                    response.request = request
                    response.revokedGrantsCount = 1
                    response.revokeAccepted = true
                }.build()
            )
            responseObserver.onCompleted()
        }
        assertEquals(
            expected = 0,
            actual = getGrantCount(grantId = grantId),
            message = "The record should be removed after successfully processing a revoke",
        )
    }

    private fun getPermissionGrant(
        grantee: String = defaultGrantee,
        grantId: String? = null
    ): GrantScopePermissionRequest = GrantScopePermissionRequest.newBuilder().also { reqBuilder ->
        reqBuilder.scopeAddress = scopeAddress
        reqBuilder.granteeAddress = grantee
        grantId?.also { reqBuilder.grantId = it }
    }.build()

    private fun getPermissionRevoke(
        grantee: String = defaultGrantee,
        grantId: String? = null,
    ): RevokeScopePermissionRequest = RevokeScopePermissionRequest.newBuilder().also { reqBuilder ->
        reqBuilder.scopeAddress = scopeAddress
        reqBuilder.granteeAddress = grantee
        grantId?.also { reqBuilder.grantId = it }
    }.build()

    private fun getGrantCount(
        scopeAddr: String = scopeAddress,
        grantee: String = defaultGrantee,
        granter: String = defaultGranter,
        grantId: String? = null,
    ): Long = queryGrantCount(
        scopeAddr = scopeAddr,
        grantee = grantee,
        granter = granter,
        grantId = grantId,
    )
}
