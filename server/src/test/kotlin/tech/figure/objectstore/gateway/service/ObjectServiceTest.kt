package tech.figure.objectstore.gateway.service

import com.google.common.util.concurrent.Futures
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyAll
import io.provenance.objectstore.proto.Objects
import io.provenance.scope.encryption.crypto.SignatureInputStream
import io.provenance.scope.encryption.domain.inputstream.DIMEInputStream
import io.provenance.scope.encryption.ecies.ProvenanceKeyGenerator
import io.provenance.scope.encryption.model.DirectKeyRef
import io.provenance.scope.encryption.model.KeyRef
import io.provenance.scope.encryption.util.getAddress
import io.provenance.scope.objectstore.client.CachedOsClient
import io.provenance.scope.objectstore.util.base64Decode
import io.provenance.scope.util.sha256
import io.provenance.scope.util.sha256String
import io.provenance.scope.util.toByteString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.any
import tech.figure.objectstore.gateway.configuration.BatchProperties
import tech.figure.objectstore.gateway.configuration.ProvenanceProperties
import tech.figure.objectstore.gateway.exception.AccessDeniedException
import tech.figure.objectstore.gateway.helpers.mockDime
import tech.figure.objectstore.gateway.helpers.mockObjectPermission
import tech.figure.objectstore.gateway.helpers.randomBytes
import tech.figure.objectstore.gateway.helpers.randomObject
import tech.figure.objectstore.gateway.model.ObjectPermissionsTable.objectHash
import tech.figure.objectstore.gateway.repository.DataStorageAccountsRepository
import tech.figure.objectstore.gateway.repository.ObjectPermissionsRepository
import java.net.URI
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

class ObjectServiceTest {
    lateinit var accountsRepository: DataStorageAccountsRepository
    lateinit var addressVerificationService: AddressVerificationService
    lateinit var batchProperties: BatchProperties
    lateinit var osClient: CachedOsClient
    lateinit var objectPermissionsRepository: ObjectPermissionsRepository
    lateinit var provenanceProperties: ProvenanceProperties
    val masterKey = ProvenanceKeyGenerator.generateKeyPair().let(::DirectKeyRef)
    val masterKeyAddress = masterKey.publicKey.getAddress(false)
    val ownerKey = ProvenanceKeyGenerator.generateKeyPair().public
    val ownerKeyAddress = ownerKey.getAddress(false)

    val registeredEncryptionKeys = listOf(
        ProvenanceKeyGenerator.generateKeyPair(),
        ProvenanceKeyGenerator.generateKeyPair(),
    ).map {
        it.public.getAddress(false) to DirectKeyRef(it)
    }.toMap()

    lateinit var objectService: ObjectService

    @BeforeEach
    fun setUp() {
        accountsRepository = mockk()
        addressVerificationService = mockk()
        osClient = mockk()
        objectPermissionsRepository = mockk()

        batchProperties = BatchProperties(maxProvidedRecords = 10)
        provenanceProperties = ProvenanceProperties(false, "pio-fakenet-1", URI(""))

        objectService = ObjectService(
            accountsRepository = accountsRepository,
            addressVerificationService = addressVerificationService,
            batchProperties = batchProperties,
            objectStoreClient = osClient,
            encryptionKeys = registeredEncryptionKeys,
            masterKey = masterKey,
            objectPermissionsRepository = objectPermissionsRepository,
            provenanceProperties = provenanceProperties,
        )
    }

    @Test
    fun `putObject should throw an access denied exception when requester is not enabled for object storage`() {
        val obj = randomObject()
        every { accountsRepository.isAddressEnabled(any()) } returns false
        val exception = assertFailsWith<AccessDeniedException> { objectService.putObject(obj, ownerKey) }
        assertTrue(
            actual = "Object storage not granted to ${ownerKey.getAddress(false)}" in (
                exception.message
                    ?: fail("Thrown exception should have a message")
                ),
            message = "The access denied exception should have the correct exception text",
        )
    }

    @Test
    fun `putObject should allow the master key to do data storage even when it is not in the accounts repository`() {
        val obj = randomObject()
        val objectBytes = obj.toByteArray()
        val objectHash = objectBytes.sha256String()

        every { accountsRepository.isAddressEnabled(any()) } returns false
        every { osClient.osClient.put(any(), masterKey.publicKey, any(), objectBytes.size.toLong(), setOf(masterKey.publicKey), mapOf(), any(), true, false) } returns Futures.immediateFuture(
            Objects.ObjectResponse.newBuilder().setHash(objectHash.base64Decode().toByteString()).build()
        )

        every {
            objectPermissionsRepository.addAccessPermission(
                objectHash = objectHash,
                granterAddress = masterKeyAddress,
                granteeAddress = masterKeyAddress,
                storageKeyAddress = masterKeyAddress,
                objectSizeBytes = objectBytes.size.toLong(),
                isObjectWithMeta = true,
            )
        } returns Unit

        val response = objectService.putObject(obj, masterKey.publicKey)

        assertEquals(objectHash, response)
        verifyAll {
            osClient.osClient.put(any(), masterKey.publicKey, any(), objectBytes.size.toLong(), setOf(masterKey.publicKey), mapOf(), any(), true, false)
            objectPermissionsRepository.addAccessPermission(
                objectHash = objectHash,
                granterAddress = masterKeyAddress,
                granteeAddress = masterKeyAddress,
                storageKeyAddress = masterKeyAddress,
                objectSizeBytes = objectBytes.size.toLong(),
                isObjectWithMeta = true,
            )
        }
    }

    @Test
    fun `putObject should send object to object store and insert permissions record`() {
        val obj = randomObject()
        val objectBytes = obj.toByteArray()
        val objectHash = objectBytes.sha256String()

        every { accountsRepository.isAddressEnabled(any()) } returns true

        every { osClient.osClient.put(any(), masterKey.publicKey, any(), objectBytes.size.toLong(), setOf(ownerKey), mapOf(), any(), true, false) } returns Futures.immediateFuture(
            Objects.ObjectResponse.newBuilder().setHash(objectHash.base64Decode().toByteString()).build()
        )

        every {
            objectPermissionsRepository.addAccessPermission(
                objectHash = objectHash,
                granterAddress = ownerKeyAddress,
                granteeAddress = ownerKeyAddress,
                storageKeyAddress = masterKeyAddress,
                objectSizeBytes = objectBytes.size.toLong(),
                isObjectWithMeta = true,
            )
        } returns Unit

        val response = objectService.putObject(obj, ownerKey)

        assertEquals(objectHash, response)
        verifyAll {
            osClient.osClient.put(any(), masterKey.publicKey, any(), objectBytes.size.toLong(), setOf(ownerKey), mapOf(), any(), true, false)
            objectPermissionsRepository.addAccessPermission(
                objectHash = objectHash,
                granterAddress = ownerKeyAddress,
                granteeAddress = ownerKeyAddress,
                storageKeyAddress = masterKeyAddress,
                objectSizeBytes = objectBytes.size.toLong(),
                isObjectWithMeta = true,
            )
        }
    }

    @Test
    fun `putObject should send object to object store with type`() {
        val obj = randomObject("cool_type_bro")
        val objectBytes = obj.toByteArray()
        val objectHash = objectBytes.sha256String()

        every { accountsRepository.isAddressEnabled(any()) } returns true

        every { osClient.osClient.put(any(), masterKey.publicKey, any(), objectBytes.size.toLong(), setOf(ownerKey), mapOf(), any(), true, false) } returns Futures.immediateFuture(
            Objects.ObjectResponse.newBuilder().setHash(objectHash.base64Decode().toByteString()).build()
        )

        every {
            objectPermissionsRepository.addAccessPermission(
                objectHash = objectHash,
                granterAddress = ownerKeyAddress,
                granteeAddress = ownerKeyAddress,
                storageKeyAddress = masterKeyAddress,
                objectSizeBytes = objectBytes.size.toLong(),
                isObjectWithMeta = true,
            )
        } returns Unit

        val response = objectService.putObject(obj, ownerKey)

        assertEquals(objectHash, response)
        verifyAll {
            osClient.osClient.put(any(), masterKey.publicKey, any(), objectBytes.size.toLong(), setOf(ownerKey), mapOf(), any(), true, false)
            objectPermissionsRepository.addAccessPermission(
                objectHash = objectHash,
                granterAddress = ownerKeyAddress,
                granteeAddress = ownerKeyAddress,
                storageKeyAddress = masterKeyAddress,
                objectSizeBytes = objectBytes.size.toLong(),
                isObjectWithMeta = true,
            )
        }
    }

    @Test
    fun `putObject should send object to object store with additional audience keys and insert permissions records for those keys' addresses`() {
        val obj = randomObject("cool_type_bro")
        val objectBytes = obj.toByteArray()
        val objectHash = objectBytes.sha256String()
        val otherKey = ProvenanceKeyGenerator.generateKeyPair().public

        every { accountsRepository.isAddressEnabled(any()) } returns true

        every { osClient.osClient.put(any(), masterKey.publicKey, any(), objectBytes.size.toLong(), setOf(ownerKey, otherKey), mapOf(), any(), true, false) } returns Futures.immediateFuture(
            Objects.ObjectResponse.newBuilder().setHash(objectHash.base64Decode().toByteString()).build()
        )

        every {
            objectPermissionsRepository.addAccessPermission(
                objectHash = objectHash,
                granterAddress = ownerKeyAddress,
                granteeAddress = any(),
                storageKeyAddress = masterKeyAddress,
                objectSizeBytes = objectBytes.size.toLong(),
                isObjectWithMeta = true,
            )
        } returns Unit

        val response = objectService.putObject(obj, ownerKey, listOf(otherKey))

        assertEquals(objectHash, response)
        verifyAll {
            listOf(ownerKey, otherKey).forEach {
                objectPermissionsRepository.addAccessPermission(
                    objectHash = objectHash,
                    granterAddress = ownerKeyAddress,
                    granteeAddress = it.getAddress(false),
                    storageKeyAddress = masterKeyAddress,
                    objectSizeBytes = objectBytes.size.toLong(),
                    isObjectWithMeta = true,
                )
            }
        }
    }

    @Test
    fun `putObject should utilize and store the object with the requester's key if they are a registered key and so requested`() {
        doPutObjectForRequesterKey(registeredEncryptionKeys.values.first(), true)
    }

    @Test
    fun `putObject should utilize and store the object with the master key if they are a registered key and don't request their own key to be used`() {
        doPutObjectForRequesterKey(registeredEncryptionKeys.values.first(), false)
    }

    @Test
    fun `putObject should deny using requester's key when they are not a registered key`() {
        val requesterKey = ProvenanceKeyGenerator.generateKeyPair().let(::DirectKeyRef)
        every { accountsRepository.isAddressEnabled(requesterKey.publicKey.getAddress(false)) } returns true
        val exception = assertThrows<AccessDeniedException> {
            doPutObjectForRequesterKey(requesterKey, true)
        }

        assertEquals("PERMISSION_DENIED: Only registered encryption keys can request storage with their own key", exception.message)
    }

    fun doPutObjectForRequesterKey(requesterKey: KeyRef, useOwnKey: Boolean) {
        val obj = randomObject("cool_type_bro")
        val objectBytes = obj.toByteArray()
        val objectHash = objectBytes.sha256String()
        val otherKey = ProvenanceKeyGenerator.generateKeyPair().public
        val requesterAddress = requesterKey.publicKey.getAddress(false)

        val keyToUse = if (useOwnKey) requesterKey else masterKey
        val keyToUseAddress = keyToUse.publicKey.getAddress(false)

        every { osClient.osClient.put(any(), keyToUse.publicKey, any(), objectBytes.size.toLong(), setOf(requesterKey.publicKey, otherKey), mapOf(), any(), true, false) } returns Futures.immediateFuture(
            Objects.ObjectResponse.newBuilder().setHash(objectHash.base64Decode().toByteString()).build()
        )

        every {
            objectPermissionsRepository.addAccessPermission(
                objectHash = objectHash,
                granterAddress = requesterAddress,
                granteeAddress = any(),
                storageKeyAddress = keyToUseAddress,
                objectSizeBytes = objectBytes.size.toLong(),
                isObjectWithMeta = true,
            )
        } returns Unit

        val response = objectService.putObject(obj, requesterKey.publicKey, listOf(otherKey), useOwnKey)

        assertEquals(objectHash, response)
        verifyAll {
            listOf(requesterKey.publicKey, otherKey).forEach {
                objectPermissionsRepository.addAccessPermission(
                    objectHash = objectHash,
                    granterAddress = requesterAddress,
                    granteeAddress = it.getAddress(false),
                    storageKeyAddress = keyToUseAddress,
                    objectSizeBytes = objectBytes.size.toLong(),
                    isObjectWithMeta = true,
                )
            }
        }
    }

    @Test
    fun `registerExistingObject should add object permissions records for an existing object where the requester is a registered encryption key (not storage account)`() {
        doRegisterExistingForKey(registeredEncryptionKeys.entries.first().value)
    }

    @Test
    fun `registerExistingObject should add object permissions records for an existing object where the requester is the master key`() {
        doRegisterExistingForKey(masterKey)
    }

    fun doRegisterExistingForKey(encryptionKey: KeyRef) {
        val objectBytes = randomBytes()
        val objectHash = objectBytes.sha256String()
        val encryptionPublicKey = encryptionKey.publicKey
        val granteeAddress = ProvenanceKeyGenerator.generateKeyPair().public.getAddress(false)

        val dimeInputStream = mockDime(objectBytes, encryptionKey)
        every { osClient.osClient.get(objectHash.base64Decode(), encryptionPublicKey) } returns Futures.immediateFuture(
            dimeInputStream
        )

        val encryptionKeyAddress = encryptionPublicKey.getAddress(false)
        every {
            objectPermissionsRepository.addAccessPermission(
                objectHash = objectHash,
                granterAddress = encryptionKeyAddress,
                granteeAddress = any(),
                storageKeyAddress = encryptionKeyAddress, // should use registered key address in permission
                objectSizeBytes = objectBytes.size.toLong(),
                isObjectWithMeta = false, // not an objectwithmeta because it was not stored via PutObject
            )
        } returns Unit

        objectService.registerExistingObject(objectHash, encryptionPublicKey, listOf(granteeAddress))

        verifyAll {
            osClient.osClient.get(objectHash.base64Decode(), encryptionPublicKey)
            listOf(encryptionKeyAddress, granteeAddress).forEach {
                objectPermissionsRepository.addAccessPermission(
                    objectHash = objectHash,
                    granterAddress = encryptionKeyAddress,
                    granteeAddress = it,
                    storageKeyAddress = encryptionKeyAddress, // should use registered key address in permission
                    objectSizeBytes = objectBytes.size.toLong(),
                    isObjectWithMeta = false, // not an objectwithmeta because it was not stored via PutObject
                )
            }
        }
    }

    @Test
    fun `registerExistingObject should not allow a non-registered encryption key to register an object`() {
        val objectBytes = randomBytes()
        val objectHash = objectBytes.sha256String()
        val encryptionKey = ProvenanceKeyGenerator.generateKeyPair().let(::DirectKeyRef)
        val encryptionPublicKey = encryptionKey.publicKey
        val granteeAddress = ProvenanceKeyGenerator.generateKeyPair().public.getAddress(false)

        val exception = assertThrows<AccessDeniedException> {
            objectService.registerExistingObject(objectHash, encryptionPublicKey, listOf(granteeAddress))
        }

        assertEquals("PERMISSION_DENIED: Existing object registration only available for master or registered encryption keys", exception.message)
    }

    @Test
    fun `registerExistingObject should fail if the requester does not have access to the object`() {
        val objectBytes = randomBytes()
        val objectHash = objectBytes.sha256String()
        val encryptionKey = registeredEncryptionKeys.entries.first().value
        val encryptionPublicKey = encryptionKey.publicKey
        val granteeAddress = ProvenanceKeyGenerator.generateKeyPair().public.getAddress(false)

        every { osClient.osClient.get(objectHash.base64Decode(), encryptionPublicKey) } throws StatusRuntimeException(
            Status.NOT_FOUND
        )

        assertThrows<StatusRuntimeException> {
            objectService.registerExistingObject(objectHash, encryptionPublicKey, listOf(granteeAddress))
        }
    }

    @Test
    fun `getObject should retrieve object if permissions present`() {
        val obj = randomObject("cool_type_bro")
        val objectBytes = obj.toByteArray()
        val objectHash = objectBytes.sha256String()

        configureOsClientGet(objectBytes)

        every { objectPermissionsRepository.getAccessPermission(objectHash, ownerKeyAddress) } returns mockObjectPermission(
            objectHash,
            masterKeyAddress,
            ownerKeyAddress,
            masterKeyAddress,
            true
        )

        val response = objectService.getObject(objectHash, ownerKeyAddress)

        assertEquals(obj, response)
    }

    @Test
    fun `getObject should deny access if permissions not present`() {
        val objectBytes = Random.nextBytes(100)
        val objectHash = objectBytes.sha256String()

        every { objectPermissionsRepository.getAccessPermission(objectHash, ownerKey.getAddress(false)) } returns null

        val exception = assertThrows<AccessDeniedException> {
            objectService.getObject(objectHash, ownerKey.getAddress(false))
        }

        assertEquals("PERMISSION_DENIED: Object access not granted to ${ownerKey.getAddress(false)} [hash: $objectHash]", exception.message)
    }

    @Test
    fun `getObject should retrieve a non-meta object successfully`() {
        val objectBytes = randomBytes()
        val objectHash = objectBytes.sha256String()
        val encryptionKey = registeredEncryptionKeys.entries.first().value
        val encryptionPublicKey = encryptionKey.publicKey
        val granteeAddress = ProvenanceKeyGenerator.generateKeyPair().public.getAddress(false)

        every { osClient.osClient.get(objectHash.base64Decode(), encryptionPublicKey) } returns Futures.immediateFuture(mockDime(objectBytes, encryptionKey))

        every { objectPermissionsRepository.getAccessPermission(objectHash, granteeAddress) } returns mockObjectPermission(
            objectHash,
            encryptionPublicKey.getAddress(false),
            granteeAddress,
            encryptionPublicKey.getAddress(false),
            false
        )

        val obj = objectService.getObject(objectHash, granteeAddress)

        assertContentEquals(objectBytes, obj.objectBytes.toByteArray(), "The returned ObjectWithMeta should wrap the non-meta object bytes")
        assertEquals("", obj.type, "There should be no type for a non-meta object")
    }

    @Test
    fun `revokeObjectPermissions should remove the requested object permissions`() {
        val objectBytes = randomBytes()
        val objectHash = objectBytes.sha256String()
        val encryptionKey = registeredEncryptionKeys.entries.first().value
        val encryptionPublicKey = encryptionKey.publicKey
        val granteeAddress = ProvenanceKeyGenerator.generateKeyPair().public.getAddress(false)

        every { objectPermissionsRepository.revokeAccessPermissions(objectHash, encryptionPublicKey.getAddress(false), listOf(granteeAddress)) } returns 1

        objectService.revokeAccess(objectHash, encryptionPublicKey.getAddress(false), listOf(granteeAddress))

        verify {
            objectPermissionsRepository.revokeAccessPermissions(objectHash, encryptionPublicKey.getAddress(false), listOf(granteeAddress))
        }
    }

    private fun configureOsClientGet(objectBytes: ByteArray) {
        every { osClient.osClient.get(objectBytes.sha256(), masterKey.publicKey) } returns Futures.immediateFuture(
            mockk<DIMEInputStream>().also {
                every { it.getDecryptedPayload(any()) } returns mockk<SignatureInputStream>().also { sigStream ->
                    every { sigStream.readAllBytes() } returns objectBytes
                    every { sigStream.verify() } returns true
                    every { sigStream.close() } returns Unit
                }
                every { it.close() } returns Unit
            }
        )
    }
}
