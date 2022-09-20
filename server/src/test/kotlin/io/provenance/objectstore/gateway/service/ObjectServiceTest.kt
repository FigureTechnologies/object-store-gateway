package io.provenance.objectstore.gateway.service

import com.google.common.util.concurrent.Futures
import io.mockk.every
import io.mockk.mockk
import io.provenance.objectstore.gateway.configuration.ProvenanceProperties
import io.provenance.objectstore.gateway.exception.AccessDeniedException
import io.provenance.objectstore.gateway.helpers.randomObject
import io.provenance.objectstore.gateway.model.ObjectPermissionsTable.objectHash
import io.provenance.objectstore.gateway.repository.ObjectPermissionsRepository
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
import java.io.InputStream
import java.net.URI
import kotlin.random.Random
import kotlin.test.assertEquals

class ObjectServiceTest {
    lateinit var osClient: CachedOsClient
    lateinit var objectPermissionsRepository: ObjectPermissionsRepository
    lateinit var provenanceProperties: ProvenanceProperties
    val masterKey = ProvenanceKeyGenerator.generateKeyPair().let(::DirectKeyRef)
    val ownerKey = ProvenanceKeyGenerator.generateKeyPair().public

    lateinit var objectService: ObjectService

    @BeforeEach
    fun setUp() {
        osClient = mockk()
        objectPermissionsRepository = mockk()

        provenanceProperties = ProvenanceProperties(false, "pio-fakenet-1", URI(""))

        objectService = ObjectService(osClient, masterKey, objectPermissionsRepository, provenanceProperties)
    }

    @Test
    fun `putObject should send object to object store and insert permissions record`() {
        val obj = randomObject()
        val objectBytes = obj.toByteArray()
        val objectHash = objectBytes.sha256String()

        every { osClient.osClient.put(any<InputStream>(), masterKey.publicKey, any(), objectBytes.size.toLong(), setOf(ownerKey), mapOf(), any(), false) } returns Futures.immediateFuture(
            Objects.ObjectResponse.newBuilder().setHash(objectHash.base64Decode().toByteString()).build()
        )

        every { objectPermissionsRepository.addAccessPermission(objectHash, ownerKey.getAddress(false), objectBytes.size.toLong()) } returns Unit

        val response = objectService.putObject(obj, ownerKey)

        assertEquals(objectHash, response)
    }

    @Test
    fun `putObject should send object to object store with type`() {
        val obj = randomObject("cool_type_bro")
        val objectBytes = obj.toByteArray()
        val objectHash = objectBytes.sha256String()

        every { osClient.osClient.put(any<InputStream>(), masterKey.publicKey, any(), objectBytes.size.toLong(), setOf(ownerKey), mapOf(), any(), false) } returns Futures.immediateFuture(
            Objects.ObjectResponse.newBuilder().setHash(objectHash.base64Decode().toByteString()).build()
        )

        every { objectPermissionsRepository.addAccessPermission(objectHash, ownerKey.getAddress(false), objectBytes.size.toLong()) } returns Unit

        val response = objectService.putObject(obj, ownerKey)

        assertEquals(objectHash, response)
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
