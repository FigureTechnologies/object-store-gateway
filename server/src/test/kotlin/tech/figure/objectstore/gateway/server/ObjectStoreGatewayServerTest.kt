package tech.figure.objectstore.gateway.server

import Constants
import io.grpc.Context
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.test.context.SpringBootTest
import tech.figure.objectstore.gateway.GatewayOuterClass
import tech.figure.objectstore.gateway.GatewayOuterClass.BatchGrantObjectPermissionsRequest
import tech.figure.objectstore.gateway.GatewayOuterClass.BatchGrantScopePermissionRequest
import tech.figure.objectstore.gateway.GatewayOuterClass.BatchGrantScopePermissionResponse
import tech.figure.objectstore.gateway.GatewayOuterClass.FetchObjectByHashResponse
import tech.figure.objectstore.gateway.GatewayOuterClass.GrantObjectPermissionsRequest
import tech.figure.objectstore.gateway.GatewayOuterClass.GrantObjectPermissionsResponse
import tech.figure.objectstore.gateway.GatewayOuterClass.GrantScopePermissionRequest
import tech.figure.objectstore.gateway.GatewayOuterClass.GrantScopePermissionResponse
import tech.figure.objectstore.gateway.GatewayOuterClass.RevokeScopePermissionRequest
import tech.figure.objectstore.gateway.GatewayOuterClass.RevokeScopePermissionResponse
import tech.figure.objectstore.gateway.GatewayOuterClass.ScopeGrantee
import tech.figure.objectstore.gateway.configuration.BatchProperties
import tech.figure.objectstore.gateway.configuration.ProvenanceProperties
import tech.figure.objectstore.gateway.exception.AccessDeniedException
import tech.figure.objectstore.gateway.exception.InvalidInputException
import tech.figure.objectstore.gateway.exception.ResourceAlreadyExistsException
import tech.figure.objectstore.gateway.helpers.bech32Address
import tech.figure.objectstore.gateway.helpers.genRandomAccount
import tech.figure.objectstore.gateway.helpers.getValidFetchObjectByHashRequest
import tech.figure.objectstore.gateway.helpers.getValidPutObjectRequest
import tech.figure.objectstore.gateway.helpers.getValidRequest
import tech.figure.objectstore.gateway.helpers.keyRef
import tech.figure.objectstore.gateway.helpers.mockScopeResponse
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
import java.security.PublicKey
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
class ObjectStoreGatewayServerTest {
    private companion object {
        const val MAX_PROVIDED_BATCH_RECORDS: Int = 10
    }
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

        val dummyRecords = listOf(
            GatewayOuterClass.Record.newBuilder()
                .setName("dummyRecordName")
                .addInputs(GatewayOuterClass.RecordObject.newBuilder().setObjectBytes(Random.nextBytes(100).toByteString()))
                .addOutputs(GatewayOuterClass.RecordObject.newBuilder().setObjectBytes(Random.nextBytes(100).toByteString()))
                .build()
        )

        every { scopeFetchService.fetchScopeForGrantee(request.scopeAddress, keyPair.public, request.granterAddress.takeIf { it.isNotBlank() }) } returns dummyRecords

        assertEquals(
            expected = runBlocking { server.fetchObject(request) },
            actual = GatewayOuterClass.FetchObjectResponse.newBuilder()
                .setScopeId(request.scopeAddress)
                .addAllRecords(dummyRecords)
                .build()
        )
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
        val byteHash = request.`object`.toByteArray().sha256String()
        every { objectService.putObject(request.`object`, keyPair.public, request.additionalAudienceKeysList.map { it.toPublicKey() }) } returns byteHash

        assertEquals(
            expected = runBlocking { server.putObject(request) },
            actual = GatewayOuterClass.PutObjectResponse.newBuilder().setHash(byteHash).build(),
        )
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
        val ownerAddress = keyPair.public.getAddress(false)
        val obj = objectFromParts(objectBytes, type)

        val byteHash = objectBytes.sha256String()
        every { objectService.getObject(byteHash, ownerAddress) } returns obj

        assertEquals(
            expected = runBlocking { server.fetchObjectByHash(getValidFetchObjectByHashRequest(byteHash)) },
            actual = FetchObjectByHashResponse.newBuilder().setObject(obj).build(),
        )
    }

    @Test
    fun `grantObjectPermissions should successfully grant permission to grantee`() {
        setUpBaseServicesAndObjectService()
        val hash = putTestObject()
        val granteeAddress = genRandomAccount().bech32Address
        val request = GrantObjectPermissionsRequest.newBuilder().also { request ->
            request.hash = hash
            request.granteeAddress = granteeAddress
        }.build()
        assertEquals(
            expected = runBlocking { server.grantObjectPermissions(request = request) },
            actual = GrantObjectPermissionsResponse.newBuilder().setHash(request.hash).setGranteeAddress(request.granteeAddress).build(),
        )
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
            runBlocking {
                server.grantObjectPermissions(
                    request = GrantObjectPermissionsRequest.newBuilder().also { request ->
                        request.hash = "some hash"
                        request.granteeAddress = "invalid bech32"
                    }.build(),
                )
            }
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
            runBlocking {
                server.grantObjectPermissions(
                    request = GrantObjectPermissionsRequest.newBuilder().also { request ->
                        request.hash = "some hash"
                        request.granteeAddress = genRandomAccount().bech32Address
                    }.build(),
                )
            }
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
        val hash = putTestObject(requester = masterAccount.keyRef.publicKey)
        assertFailsWith<AccessDeniedException>("Not owned hash should be denied upon request") {
            runBlocking {
                server.grantObjectPermissions(
                    request = GrantObjectPermissionsRequest.newBuilder().also { request ->
                        request.hash = hash
                        request.granteeAddress = genRandomAccount().bech32Address
                    }.build(),
                )
            }
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
        val grantee = genRandomAccount()
        val hash = putTestObject(additionalAudiences = listOf(grantee.keyRef.publicKey))
        assertFailsWith<ResourceAlreadyExistsException>("Existing grant should not be considered") {
            runBlocking {
                server.grantObjectPermissions(
                    request = GrantObjectPermissionsRequest.newBuilder().also { request ->
                        request.hash = hash
                        request.granteeAddress = grantee.bech32Address
                    }.build(),
                )
            }
        }.also { exception ->
            assertEquals(
                expected = "ALREADY_EXISTS: Grantee [${grantee.bech32Address}] has already been granted permissions to hash [$hash]",
                actual = exception.message,
                message = "Unexpected exception text after grant already exists",
            )
        }
    }

    @Test
    fun `batchGrantObjectPermissions should successfully grant all permissions when requested`() {
        setUpBaseServicesAndObjectService()
        val hashes = (0 until 10).mapTo(HashSet()) { putTestObject() }
        val interceptedHashes = mutableSetOf<String>()
        val grantee = genRandomAccount()
        runBlocking {
            server.batchGrantObjectPermissions(
                request = BatchGrantObjectPermissionsRequest.newBuilder().also { request ->
                    request.allHashesBuilder.granteeAddress = grantee.bech32Address
                }.build(),
            ).collect { interceptedHashes += it.hash }
        }
        assertEquals(
            expected = interceptedHashes.size,
            actual = hashes.size,
            message = "Expected the amount of hashes encountered to exist in the responses. Got $interceptedHashes, expected $hashes",
        )
        assertTrue(
            actual = interceptedHashes.all { it in hashes },
            message = "Expected all stored hashes $hashes to be intercepted, but got $interceptedHashes",
        )
        val repository = ObjectPermissionsRepository()
        interceptedHashes.forEach { interceptedHash ->
            val permission = repository.getAccessPermission(objectHash = interceptedHash, granteeAddress = grantee.bech32Address)
            assertNotNull(actual = permission, message = "Expected the grant to exist in the database")
        }
    }

    @Test
    fun `batchGrantObjectPermissions should successfully grant target permissions when requested`() {
        setUpBaseServicesAndObjectService()
        val hashes = (0 until 10).mapTo(HashSet()) { putTestObject() }
        val interceptedHashes = mutableSetOf<String>()
        val grantee = genRandomAccount()
        val targetGrantHashes = hashes.take(4)
        runBlocking {
            server.batchGrantObjectPermissions(
                request = BatchGrantObjectPermissionsRequest.newBuilder().also { request ->
                    request.specifiedHashesBuilder.addAllTargetHashes(targetGrantHashes)
                    request.specifiedHashesBuilder.granteeAddress = grantee.bech32Address
                }.build(),
            ).collect { interceptedHashes += it.hash }
        }
        assertEquals(
            expected = interceptedHashes.size,
            actual = targetGrantHashes.size,
            message = "Expected the amount of hashes encountered to exist in the responses. Got $interceptedHashes, expected $targetGrantHashes",
        )
        assertTrue(
            actual = interceptedHashes.all { it in targetGrantHashes },
            message = "Expected all target hashes $targetGrantHashes to be intercepted, but got $interceptedHashes",
        )
        val repository = ObjectPermissionsRepository()
        hashes.forEach { interceptedHash ->
            val permission = repository.getAccessPermission(objectHash = interceptedHash, granteeAddress = grantee.bech32Address)
            if (interceptedHash in targetGrantHashes) {
                assertNotNull(actual = permission, message = "Expected a target grant hash to be granted to the grantee")
            } else {
                assertNull(actual = permission, message = "Expected an untargeted grant hash to be omitted from grants")
            }
        }
    }

    @Test
    fun `batchGrantScopePermission should fail when no hashes are specified`() {
        setUpBaseServicesAndObjectService()
        assertFailsWith<InvalidInputException> {
            runBlocking {
                server.batchGrantObjectPermissions(
                    request = BatchGrantObjectPermissionsRequest.newBuilder().also { request ->
                        request.specifiedHashesBuilder.granteeAddress = genRandomAccount().bech32Address
                    }.build(),
                )
            }
        }.also { exception ->
            assertEquals(
                expected = "INVALID_ARGUMENT: Target hash count must be greater than zero",
                actual = exception.message,
                message = "Unexpected exception text when providing too many hashes",
            )
        }
    }

    @Test
    fun `batchGrantScopePermission should fail when specified hash count greater than max allowed records`() {
        setUpBaseServicesAndObjectService()
        val hashes = (0..MAX_PROVIDED_BATCH_RECORDS + 1).mapTo(HashSet()) { putTestObject() }
        assertFailsWith<InvalidInputException> {
            runBlocking {
                server.batchGrantObjectPermissions(
                    request = BatchGrantObjectPermissionsRequest.newBuilder().also { request ->
                        request.specifiedHashesBuilder.addAllTargetHashes(hashes)
                        request.specifiedHashesBuilder.granteeAddress = genRandomAccount().bech32Address
                    }.build(),
                )
            }
        }.also { exception ->
            assertEquals(
                expected = "INVALID_ARGUMENT: Target hash count must be less than maximum value of [$MAX_PROVIDED_BATCH_RECORDS]",
                actual = exception.message,
                message = "Unexpected exception text when providing too many hashes",
            )
        }
    }

    private fun putTestObject(
        requester: PublicKey = keyPair.public,
        additionalAudiences: Collection<PublicKey> = emptyList(),
    ): String {
        val objectBytes = Random.nextBytes(100)
        val obj = objectFromParts(objectBytes, "some_type")
        val byteHash = objectBytes.sha256String()
        every { objectStoreClient.osClient.put(any(), any(), any(), any(), any(), any(), any(), any()).get().hash } returns byteHash.toByteString()
        return objectService.putObject(
            obj = obj,
            requesterPublicKey = requester,
            useRequesterKey = requester != masterAccount.keyRef.publicKey,
            additionalAudienceKeys = additionalAudiences.toList(),
        )
    }

    fun setUpBaseServicesAndObjectService(objectOwner: KeyPair = keyPair) {
        setUpBaseServices {
            ObjectService(
                accountsRepository = mockk(),
                addressVerificationService = addressVerificationService,
                batchProperties = BatchProperties(maxProvidedRecords = MAX_PROVIDED_BATCH_RECORDS),
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
        val request = getPermissionGrant()
        assertEquals(
            expected = runBlocking { server.grantScopePermission(request = request) },
            actual = GrantScopePermissionResponse.newBuilder().also { response ->
                response.request = request
                response.grantAccepted = false
            }.build(),
        )
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
        assertThrows<StatusRuntimeException> {
            runBlocking { server.grantScopePermission(request = getPermissionGrant()) }
        }.also { exception ->
            assertEquals(
                expected = Status.UNKNOWN.code,
                actual = exception.status.code,
                message = "The UNKNOWN status should be emitted when an exception is encountered",
            )
            assertNull(
                actual = exception.status.cause,
                message = "The source exception should not be sent to the consumer",
            )
            assertEquals(
                expected = ObjectStoreGatewayServer.DEFAULT_UNKNOWN_DESCRIPTION,
                actual = exception.status.description,
                message = "The expected description should be sent",
            )
        }
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
        val grantees = (0..10).map { buildScopeGrantee() }
        val request = getBatchGrant(*grantees.toTypedArray())
        assertEquals(
            expected = runBlocking { server.batchGrantScopePermission(request = request) },
            actual = BatchGrantScopePermissionResponse.newBuilder().also { response ->
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
            }.build(),
        )
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
        val request = getBatchGrant(goodGrantee, badGrantee)
        assertEquals(
            expected = runBlocking { server.batchGrantScopePermission(request = request) },
            actual = BatchGrantScopePermissionResponse.newBuilder().also { response ->
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
            }.build(),
        )
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
        assertThrows<StatusRuntimeException> {
            runBlocking { server.batchGrantScopePermission(request = getBatchGrant()) }
        }.also { exception ->
            assertEquals(
                expected = Status.INVALID_ARGUMENT.code,
                actual = exception.status.code,
                message = "The INVALID_ARGUMENT status should be emitted when no grantees are added",
            )
            assertTrue(
                actual = "At least one grantee is required" in (exception.message ?: "NO MESSAGE EMITTED"),
                message = "Expected the correct exception text to be included in message: ${exception.message}",
            )
        }
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
        val request = getPermissionRevoke()
        assertEquals(
            expected = runBlocking { server.revokeScopePermission(request = request) },
            actual = RevokeScopePermissionResponse.newBuilder().also { response ->
                response.request = request
                response.revokeAccepted = false
            }.build(),
        )
    }

    @Test
    fun `revokeScopePermission should return expected unknown response on exception`() {
        setUpBaseServices()
        // Setup scope fetch service to freak out, simulating a provenance communication error
        every { scopeFetchService.fetchScope(any(), any(), any()) } throws IllegalArgumentException("That ol' blockchain is givin' us trouble")
        assertFailsWith<StatusRuntimeException> {
            runBlocking { server.revokeScopePermission(request = getPermissionRevoke()) }
        }.also { exception ->
            assertEquals(
                expected = Status.UNKNOWN.code,
                actual = exception.status.code,
                message = "The UNKNOWN status should be emitted when an exception is encountered",
            )
            assertNull(
                actual = exception.status.cause,
                message = "The source exception should not be sent to the consumer",
            )
            assertEquals(
                expected = ObjectStoreGatewayServer.DEFAULT_UNKNOWN_DESCRIPTION,
                actual = exception.status.description,
                message = "The expected description should be sent",
            )
        }
    }

    private fun testGrantScopePermission(
        contextKeyPair: KeyPair,
        grantId: String?,
    ) {
        setUpBaseServices(contextKeyPair = contextKeyPair)
        setUpScopePermissionValues()
        val request = getPermissionGrant(grantId = grantId)
        assertEquals(
            expected = runBlocking { server.grantScopePermission(request = request) },
            actual = GrantScopePermissionResponse.newBuilder().also { response ->
                response.request = request
                response.granterAddress = defaultGranter
                response.grantAccepted = true
            }.build(),
        )
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
        val request = getBatchGrant(*grantees)
        assertEquals(
            expected = runBlocking { server.batchGrantScopePermission(request = request) },
            actual = BatchGrantScopePermissionResponse.newBuilder().also { batchResponse ->
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
            }.build(),
        )
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
        runBlocking { server.grantScopePermission(request = getPermissionGrant(grantId = grantId)) }
        assertEquals(
            expected = 1,
            actual = getGrantCount(grantId = grantId),
            message = "A record with the provided specifications should be created by the request",
        )
        val request = getPermissionRevoke(grantId = grantId)
        assertEquals(
            expected = runBlocking { server.revokeScopePermission(request = request) },
            actual = RevokeScopePermissionResponse.newBuilder().also { response ->
                response.request = request
                response.revokedGrantsCount = 1
                response.revokeAccepted = true
            }.build(),
        )
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
