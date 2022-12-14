package tech.figure.objectstore.gateway.service

import com.google.common.util.concurrent.Futures
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyAll
import io.provenance.objectstore.proto.Objects
import io.provenance.scope.encryption.crypto.SignatureInputStream
import io.provenance.scope.encryption.domain.inputstream.DIMEInputStream
import io.provenance.scope.encryption.ecies.ProvenanceKeyGenerator
import io.provenance.scope.encryption.model.DirectKeyRef
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
import tech.figure.objectstore.gateway.configuration.ProvenanceProperties
import tech.figure.objectstore.gateway.exception.AccessDeniedException
import tech.figure.objectstore.gateway.helpers.randomObject
import tech.figure.objectstore.gateway.model.ObjectPermissionsTable.objectHash
import tech.figure.objectstore.gateway.repository.DataStorageAccountsRepository
import tech.figure.objectstore.gateway.repository.ObjectPermissionsRepository
import java.net.URI
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

class ObjectServiceTest {
    lateinit var accountsRepository: DataStorageAccountsRepository
    lateinit var osClient: CachedOsClient
    lateinit var objectPermissionsRepository: ObjectPermissionsRepository
    lateinit var provenanceProperties: ProvenanceProperties
    val masterKey = ProvenanceKeyGenerator.generateKeyPair().let(::DirectKeyRef)
    val ownerKey = ProvenanceKeyGenerator.generateKeyPair().public

    lateinit var objectService: ObjectService

    @BeforeEach
    fun setUp() {
        accountsRepository = mockk()
        osClient = mockk()
        objectPermissionsRepository = mockk()

        provenanceProperties = ProvenanceProperties(false, "pio-fakenet-1", URI(""))

        objectService = ObjectService(
            accountsRepository = accountsRepository,
            objectStoreClient = osClient,
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
        every { osClient.osClient.put(any(), masterKey.publicKey, any(), objectBytes.size.toLong(), setOf(masterKey.publicKey), mapOf(), any(), false) } returns Futures.immediateFuture(
            Objects.ObjectResponse.newBuilder().setHash(objectHash.base64Decode().toByteString()).build()
        )
        every { objectPermissionsRepository.addAccessPermission(objectHash, masterKey.publicKey.getAddress(false), objectBytes.size.toLong()) } returns Unit

        val response = objectService.putObject(obj, masterKey.publicKey)

        assertEquals(objectHash, response)
        verifyAll {
            osClient.osClient.put(any(), masterKey.publicKey, any(), objectBytes.size.toLong(), setOf(masterKey.publicKey), mapOf(), any(), false)
            objectPermissionsRepository.addAccessPermission(objectHash, masterKey.publicKey.getAddress(false), objectBytes.size.toLong())
        }
    }

    @Test
    fun `putObject should send object to object store and insert permissions record`() {
        val obj = randomObject()
        val objectBytes = obj.toByteArray()
        val objectHash = objectBytes.sha256String()

        every { accountsRepository.isAddressEnabled(any()) } returns true

        every { osClient.osClient.put(any(), masterKey.publicKey, any(), objectBytes.size.toLong(), setOf(ownerKey), mapOf(), any(), false) } returns Futures.immediateFuture(
            Objects.ObjectResponse.newBuilder().setHash(objectHash.base64Decode().toByteString()).build()
        )

        every { objectPermissionsRepository.addAccessPermission(objectHash, ownerKey.getAddress(false), objectBytes.size.toLong()) } returns Unit

        val response = objectService.putObject(obj, ownerKey)

        assertEquals(objectHash, response)
        verifyAll {
            osClient.osClient.put(any(), masterKey.publicKey, any(), objectBytes.size.toLong(), setOf(ownerKey), mapOf(), any(), false)
            objectPermissionsRepository.addAccessPermission(objectHash, ownerKey.getAddress(false), objectBytes.size.toLong())
        }
    }

    @Test
    fun `putObject should send object to object store with type`() {
        val obj = randomObject("cool_type_bro")
        val objectBytes = obj.toByteArray()
        val objectHash = objectBytes.sha256String()

        every { accountsRepository.isAddressEnabled(any()) } returns true

        every { osClient.osClient.put(any(), masterKey.publicKey, any(), objectBytes.size.toLong(), setOf(ownerKey), mapOf(), any(), false) } returns Futures.immediateFuture(
            Objects.ObjectResponse.newBuilder().setHash(objectHash.base64Decode().toByteString()).build()
        )

        every { objectPermissionsRepository.addAccessPermission(objectHash, ownerKey.getAddress(false), objectBytes.size.toLong()) } returns Unit

        val response = objectService.putObject(obj, ownerKey)

        assertEquals(objectHash, response)
        verifyAll {
            osClient.osClient.put(any(), masterKey.publicKey, any(), objectBytes.size.toLong(), setOf(ownerKey), mapOf(), any(), false)
            objectPermissionsRepository.addAccessPermission(objectHash, ownerKey.getAddress(false), objectBytes.size.toLong())
        }
    }

    @Test
    fun `putObject should send object to object store with additional audience keys and insert permissions records for those keys' addresses`() {
        val obj = randomObject("cool_type_bro")
        val objectBytes = obj.toByteArray()
        val objectHash = objectBytes.sha256String()
        val otherKey = ProvenanceKeyGenerator.generateKeyPair().public

        every { accountsRepository.isAddressEnabled(any()) } returns true

        every { osClient.osClient.put(any(), masterKey.publicKey, any(), objectBytes.size.toLong(), setOf(ownerKey, otherKey), mapOf(), any(), false) } returns Futures.immediateFuture(
            Objects.ObjectResponse.newBuilder().setHash(objectHash.base64Decode().toByteString()).build()
        )

        every { objectPermissionsRepository.addAccessPermission(objectHash, any(), objectBytes.size.toLong()) } returns Unit

        val response = objectService.putObject(obj, ownerKey, listOf(otherKey))

        assertEquals(objectHash, response)
        verifyAll {
            listOf(ownerKey, otherKey).forEach {
                objectPermissionsRepository.addAccessPermission(objectHash, it.getAddress(false), objectBytes.size.toLong())
            }
        }
    }

    @Test
    fun `getObject should retrieve object if permissions present`() {
        val obj = randomObject("cool_type_bro")
        val objectBytes = obj.toByteArray()
        val objectHash = objectBytes.sha256String()

        configureOsClientGet(objectBytes)

        every { objectPermissionsRepository.hasAccessPermission(objectHash, ownerKey.getAddress(false)) } returns true

        val response = objectService.getObject(objectHash, ownerKey.getAddress(false))

        assertEquals(obj, response)
    }

    @Test
    fun `getObject should deny access if permissions not present`() {
        val objectBytes = Random.nextBytes(100)
        val objectHash = objectBytes.sha256String()

        every { objectPermissionsRepository.hasAccessPermission(objectHash, ownerKey.getAddress(false)) } returns false

        val exception = assertThrows<AccessDeniedException> {
            objectService.getObject(objectHash, ownerKey.getAddress(false))
        }

        assertEquals("PERMISSION_DENIED: Object access not granted to ${ownerKey.getAddress(false)} [hash: $objectHash]", exception.message)
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
