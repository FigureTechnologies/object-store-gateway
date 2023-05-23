package tech.figure.objectstore.gateway.server

import Constants
import io.grpc.Context
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import io.mockk.verifyAll
import io.provenance.hdwallet.ec.extensions.toJavaECKeyPair
import io.provenance.hdwallet.wallet.Account
import io.provenance.metadata.v1.PartyType
import io.provenance.scope.encryption.ecies.ProvenanceKeyGenerator
import io.provenance.scope.encryption.model.DirectKeyRef
import io.provenance.scope.encryption.util.getAddress
import io.provenance.scope.encryption.util.toPublicKey
import io.provenance.scope.objectstore.client.CachedOsClient
import io.provenance.scope.sdk.toPublicKeyProto
import io.provenance.scope.util.MetadataAddress
import io.provenance.scope.util.sha256String
import io.provenance.scope.util.toByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScope
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import tech.figure.objectstore.gateway.GatewayOuterClass
import tech.figure.objectstore.gateway.GatewayOuterClass.BatchGrantScopePermissionRequest
import tech.figure.objectstore.gateway.GatewayOuterClass.BatchGrantScopePermissionResponse
import tech.figure.objectstore.gateway.GatewayOuterClass.GrantObjectPermissionsRequest
import tech.figure.objectstore.gateway.GatewayOuterClass.GrantObjectPermissionsResponse
import tech.figure.objectstore.gateway.GatewayOuterClass.GrantScopePermissionRequest
import tech.figure.objectstore.gateway.GatewayOuterClass.GrantScopePermissionResponse
import tech.figure.objectstore.gateway.GatewayOuterClass.RevokeScopePermissionRequest
import tech.figure.objectstore.gateway.GatewayOuterClass.RevokeScopePermissionResponse
import tech.figure.objectstore.gateway.GatewayOuterClass.ScopeGrantee
import tech.figure.objectstore.gateway.configuration.ProvenanceProperties
import tech.figure.objectstore.gateway.exception.AccessDeniedException
import tech.figure.objectstore.gateway.exception.ResourceAlreadyExistsException
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
import tech.figure.objectstore.gateway.repository.ObjectPermissionsRepository
import tech.figure.objectstore.gateway.repository.ScopePermissionsRepository
import tech.figure.objectstore.gateway.service.AddressVerificationService
import tech.figure.objectstore.gateway.service.ObjectService
import tech.figure.objectstore.gateway.service.ScopeFetchService
import tech.figure.objectstore.gateway.service.ScopePermissionsService
import java.net.URI
import java.security.KeyPair
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@OptIn(ExperimentalCoroutinesApi::class)
class ObjectStoreGatewayServerTest {
    lateinit var addressVerificationService: AddressVerificationService
    lateinit var scopeFetchService: ScopeFetchService
    lateinit var scopePermissionsService: ScopePermissionsService
    lateinit var scopePermissionsRepository: ScopePermissionsRepository
    lateinit var objectService: ObjectService
    lateinit var objectStoreClient: CachedOsClient
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
        constructObjectService: () -> ObjectService = { mockk() },
    ) {
        scopeFetchService = mockk()
        objectStoreClient = mockk()
        scopePermissionsRepository = spyk()
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
        objectService = constructObjectService()
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

    @Test
    fun `putObject should pass through additional audience public keys to service`() {
        setUpBaseServices()
        val someKey = ProvenanceKeyGenerator.generateKeyPair().public.toPublicKeyProto()
        val request = getValidPutObjectRequest().toBuilder()
            .addAdditionalAudienceKeys(someKey)
            .build()

        testSuccessfulPutObject(request)
    }

    fun testSuccessfulPutObject(request: GatewayOuterClass.PutObjectRequest) {
        val responseObserver: StreamObserver<GatewayOuterClass.PutObjectResponse> = mockkObserver()

        val byteHash = request.`object`.toByteArray().sha256String()
        every { objectService.putObject(request.`object`, keyPair.public, request.additionalAudienceKeysList.map { it.toPublicKey() }) } returns byteHash

        server.putObject(request, responseObserver)

        verifyAll {
            objectService.putObject(request.`object`, keyPair.public, request.additionalAudienceKeysList.map { it.toPublicKey() })
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
    fun `grantObjectPermissions should successfully grant permission to grantee`() {
        val responseObserver = mockkObserver<GrantObjectPermissionsResponse>()
        setUpBaseServicesAndObjectService()
        val objectBytes = Random.nextBytes(100)
        val obj = objectFromParts(objectBytes, "some_type")
        val byteHash = objectBytes.sha256String()
        every { objectStoreClient.osClient.put(any(), any(), any(), any(), any(), any(), any(), any()).get().hash } returns byteHash.toByteString()
        val hash = objectService.putObject(
            obj = obj,
            requesterPublicKey = keyPair.public,
            useRequesterKey = true,
        )
        val granteeAddress = genRandomAccount().bech32Address
        val request = GrantObjectPermissionsRequest.newBuilder().also { request ->
            request.hash = hash
            request.granteeAddress = granteeAddress
        }.build()
        server.grantObjectPermissions(request = request, responseObserver = responseObserver)
        verify { responseObserver.onNext(GrantObjectPermissionsResponse.newBuilder().setRequest(request).build()) }
        ObjectPermissionsRepository().getAccessPermission(objectHash = hash, granteeAddress = granteeAddress).also { permission ->
            assertNotNull(
                actual = permission,
                message = "The permission should be granted and a record should be ported to the db",
            )
        }
    }

    @Test
    fun `grantObjectPermissions should reject an invalid grantee`() {
        setUpBaseServicesAndObjectService()
        assertFailsWith<AccessDeniedException>("Invalid grantee should be denied upon request") {
            server.grantObjectPermissions(
                request = GrantObjectPermissionsRequest.newBuilder().also { request ->
                    request.hash = "some hash"
                    request.granteeAddress = "invalid bech32"
                }.build(),
                responseObserver = mockkObserver(),
            )
        }.also { exception ->
            assertEquals(
                expected = "PERMISSION_DENIED: Grantee address [invalid bech32] is not valid",
                actual = exception.message,
                message = "Unexpected exception text after invalid grantee address",
            )
        }
    }

    @Test
    fun `grantObjectPermissions should reject a request that points to an unknown hash`() {
        setUpBaseServicesAndObjectService()
        assertFailsWith<AccessDeniedException>("Unknown hash should be denied upon request") {
            server.grantObjectPermissions(
                request = GrantObjectPermissionsRequest.newBuilder().also { request ->
                    request.hash = "some hash"
                    request.granteeAddress = genRandomAccount().bech32Address
                }.build(),
                responseObserver = mockkObserver(),
            )
        }.also { exception ->
            assertEquals(
                expected = "PERMISSION_DENIED: Granter [${keyPair.public.getAddress(false)}] has no authority to grant on hash [some hash]",
                actual = exception.message,
                message = "Unexpected exception text after nonexistent hash",
            )
        }
    }

    @Test
    fun `grantObjectPermissions should reject a request that points to a hash owned by a different account`() {
        setUpBaseServicesAndObjectService()
        val objectBytes = Random.nextBytes(100)
        val obj = objectFromParts(objectBytes, "some_type")
        val byteHash = objectBytes.sha256String()
        every { objectStoreClient.osClient.put(any(), any(), any(), any(), any(), any(), any(), any()).get().hash } returns byteHash.toByteString()
        val hash = objectService.putObject(
            obj = obj,
            requesterPublicKey = masterAccount.keyRef.publicKey,
            useRequesterKey = false,
        )
        assertFailsWith<AccessDeniedException>("Not owned hash should be denied upon request") {
            server.grantObjectPermissions(
                request = GrantObjectPermissionsRequest.newBuilder().also { request ->
                    request.hash = hash
                    request.granteeAddress = genRandomAccount().bech32Address
                }.build(),
                responseObserver = mockkObserver(),
            )
        }.also { exception ->
            assertEquals(
                expected = "PERMISSION_DENIED: Granter [${keyPair.public.getAddress(false)}] has no authority to grant on hash [$hash]",
                actual = exception.message,
                message = "Unexpected exception text unexpected owner",
            )
        }
    }

    @Test
    fun `grantObjectPermissions should reject a request that tries to create a grant that already exists`() {
        setUpBaseServicesAndObjectService()
        val objectBytes = Random.nextBytes(100)
        val obj = objectFromParts(objectBytes, "some_type")
        val byteHash = objectBytes.sha256String()
        every { objectStoreClient.osClient.put(any(), any(), any(), any(), any(), any(), any(), any()).get().hash } returns byteHash.toByteString()
        val grantee = genRandomAccount()
        val hash = objectService.putObject(
            obj = obj,
            requesterPublicKey = keyPair.public,
            useRequesterKey = true,
            additionalAudienceKeys = listOf(grantee.keyRef.publicKey),
        )
        assertFailsWith<ResourceAlreadyExistsException>("Existing grant should not be considered") {
            server.grantObjectPermissions(
                request = GrantObjectPermissionsRequest.newBuilder().also { request ->
                    request.hash = hash
                    request.granteeAddress = grantee.bech32Address
                }.build(),
                responseObserver = mockkObserver(),
            )
        }.also { exception ->
            assertEquals(
                expected = "ALREADY_EXISTS: Grantee [${grantee.bech32Address}] has already been granted permissions to hash [$hash]",
                actual = exception.message,
                message = "Unexpected exception text after grant already exists",
            )
        }
    }

    fun setUpBaseServicesAndObjectService(objectOwner: KeyPair = keyPair) {
        setUpBaseServices {
            ObjectService(
                accountsRepository = mockk(),
                addressVerificationService = addressVerificationService,
                batchProperties = mockk(),
                batchProcessScope = TestCoroutineScope(),
                objectStoreClient = objectStoreClient,
                encryptionKeys = mapOf(objectOwner.public.getAddress(false) to DirectKeyRef(keyPair)),
                masterKey = masterAccount.keyRef,
                objectPermissionsRepository = ObjectPermissionsRepository(),
                provenanceProperties = ProvenanceProperties(mainNet = false, chainId = "testing", channelUri = URI.create("http://doesntmatter.com")),
            )
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
    fun `batchGrantScopePermission should respond with success to an authorized caller for single grantee without grant id`() {
        testBatchGrantScopePermission(
            contextKeyPair = keyPair,
            buildScopeGrantee(),
        )
    }

    @Test
    fun `batchGrantScopePermission should respond with success to an authorized caller for single grantee with grant id`() {
        testBatchGrantScopePermission(
            contextKeyPair = keyPair,
            buildScopeGrantee(grantId = "whatever"),
        )
    }

    @Test
    fun `batchGrantScopePermission should respond with all successes to an authorized caller for multiple grantees without grant ids`() {
        testBatchGrantScopePermission(
            contextKeyPair = keyPair,
            *(0..10).map { buildScopeGrantee() }.toTypedArray()
        )
    }

    @Test
    fun `batchGrantScopePermission should respond with all successes to an authorized caller for multiple grantees with grant ids`() {
        testBatchGrantScopePermission(
            contextKeyPair = keyPair,
            *(0..10).map { grantIdPrefix -> buildScopeGrantee(grantId = "$grantIdPrefix-${UUID.randomUUID()}") }.toTypedArray()
        )
    }

    @Test
    fun `batchGrantScopePermission should reject grants that are not authorized`() {
        // Use some random account as the requesting account to verify that the requester has to be someone relevant
        val granterAccount = genRandomAccount()
        setUpBaseServices(contextKeyPair = granterAccount.keyPair.toJavaECKeyPair())
        setUpScopePermissionValues()
        val responseObserver = mockkObserver<BatchGrantScopePermissionResponse>()
        val grantees = (0..10).map { buildScopeGrantee() }
        val request = getBatchGrant(*grantees.toTypedArray())
        server.batchGrantScopePermission(request = request, responseObserver = responseObserver)
        verify(inverse = true) { responseObserver.onError(any()) }
        verifyAll {
            responseObserver.onNext(
                BatchGrantScopePermissionResponse.newBuilder().also { response ->
                    response.request = request
                    response.addAllGrantResponses(
                        grantees.map { grantee ->
                            GrantScopePermissionResponse.newBuilder().also { grantResponse ->
                                grantResponse.requestBuilder.scopeAddress = scopeAddress
                                grantResponse.requestBuilder.granteeAddress = grantee.granteeAddress
                                grantResponse.grantAccepted = false
                            }.build()
                        }
                    )
                }.build()
            )
            responseObserver.onCompleted()
        }
        assertEquals(
            expected = 0,
            actual = transaction { ScopePermissionsTable.selectAll().count() },
            message = "No scope permissions should have ever been assigned, proving that all grantees were rejected",
        )
    }

    @Test
    fun `batchGrantScopePermission should remove grants that cause exceptions from the response`() {
        setUpBaseServices()
        setUpScopePermissionValues()
        val goodGrantee = buildScopeGrantee()
        val badGrantee = buildScopeGrantee()
        // Hook into the database to force it to throw an exception only on this address
        every {
            scopePermissionsRepository.addAccessPermission(
                scopeAddress = scopeAddress,
                granteeAddress = badGrantee.granteeAddress,
                granterAddress = defaultGranter,
                grantId = null,
            )
        } throws IllegalStateException("For some reason, this particular address causes exceptions in the database!")
        val responseObserver = mockkObserver<BatchGrantScopePermissionResponse>()
        val request = getBatchGrant(goodGrantee, badGrantee)
        server.batchGrantScopePermission(request = request, responseObserver = responseObserver)
        verify(inverse = true) { responseObserver.onError(any()) }
        verifyAll {
            responseObserver.onNext(
                BatchGrantScopePermissionResponse.newBuilder().also { response ->
                    response.request = request
                    response.addGrantResponses(
                        GrantScopePermissionResponse.newBuilder().also { grantResponse ->
                            grantResponse.requestBuilder.scopeAddress = scopeAddress
                            grantResponse.requestBuilder.granteeAddress = goodGrantee.granteeAddress
                            grantResponse.requestBuilder.grantId = goodGrantee.grantId
                            grantResponse.granterAddress = defaultGranter
                            grantResponse.grantAccepted = true
                        }
                    )
                }.build()
            )
            responseObserver.onCompleted()
        }
        assertEquals(
            expected = 1,
            actual = transaction { ScopePermissionsTable.selectAll().count() },
            message = "Only one grantee should be added to the database",
        )
        assertEquals(
            expected = 1,
            actual = getGrantCount(grantee = goodGrantee.granteeAddress),
            message = "The expected grantee should be added to the database",
        )
        assertEquals(
            expected = 0,
            actual = getGrantCount(grantee = badGrantee.granteeAddress),
            message = "Sanity check: The grantee expected to throw an exception should not be given a grant",
        )
    }

    @Test
    fun `batchGrantScopePermission should reject requests that do not specify any grantees`() {
        setUpBaseServices()
        setUpScopePermissionValues()
        val responseObserver = mockkObserver<BatchGrantScopePermissionResponse>()
        val exceptionSlot = responseObserver.createErrorSlot<StatusRuntimeException>()
        server.batchGrantScopePermission(request = getBatchGrant(), responseObserver = responseObserver)
        verifyAll(inverse = true) {
            responseObserver.onNext(any())
            responseObserver.onCompleted()
        }
        assertEquals(
            expected = Status.INVALID_ARGUMENT.code,
            actual = exceptionSlot.captured.status.code,
            message = "The INVALID_ARGUMENT status should be emitted when no grantees are added",
        )
        assertTrue(
            actual = "At least one grantee is required" in (exceptionSlot.captured.message ?: "NO MESSAGE EMITTED"),
            message = "Expected the correct exception text to be included in message: ${exceptionSlot.captured.message}",
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

    private fun testBatchGrantScopePermission(
        contextKeyPair: KeyPair,
        vararg grantees: ScopeGrantee,
    ) {
        setUpBaseServices(contextKeyPair = contextKeyPair)
        setUpScopePermissionValues()
        val responseObserver = mockkObserver<BatchGrantScopePermissionResponse>()
        val request = getBatchGrant(*grantees)
        server.batchGrantScopePermission(request = request, responseObserver = responseObserver)
        verify(inverse = true) { responseObserver.onError(any()) }
        verifyAll {
            responseObserver.onNext(
                BatchGrantScopePermissionResponse.newBuilder().also { batchResponse ->
                    batchResponse.request = request
                    batchResponse.addAllGrantResponses(
                        grantees.map { grantee ->
                            GrantScopePermissionResponse.newBuilder().also { grantResponse ->
                                grantResponse.requestBuilder.scopeAddress = scopeAddress
                                grantResponse.requestBuilder.granteeAddress = grantee.granteeAddress
                                grantResponse.requestBuilder.grantId = grantee.grantId
                                grantResponse.granterAddress = defaultGranter
                                grantResponse.grantAccepted = true
                            }.build()
                        }
                    )
                }.build()
            )
            responseObserver.onCompleted()
        }
        grantees.forEach { grantee ->
            assertEquals(
                expected = 1,
                actual = getGrantCount(grantee = grantee.granteeAddress, grantId = grantee.grantId.takeIf { it.isNotBlank() }),
                message = "Grantee [${grantee.granteeAddress}] should receive a grant from the batch request",
            )
        }
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

    private fun getBatchGrant(
        vararg grantees: ScopeGrantee,
    ): BatchGrantScopePermissionRequest = BatchGrantScopePermissionRequest.newBuilder().also { reqBuilder ->
        reqBuilder.scopeAddress = scopeAddress
        reqBuilder.addAllGrantees(grantees.toList())
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

    private fun buildScopeGrantee(
        granteeAddress: String = genRandomAccount().bech32Address,
        grantId: String? = null,
    ): ScopeGrantee = ScopeGrantee.newBuilder().also { grantee ->
        grantee.granteeAddress = granteeAddress
        grantId?.also { grantee.grantId = it }
    }.build()
}
