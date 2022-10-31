package tech.figure.objectstore.gateway.service

import com.google.common.util.concurrent.Futures
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.provenance.client.grpc.PbClient
import io.provenance.metadata.v1.Party
import io.provenance.metadata.v1.PartyType
import io.provenance.metadata.v1.Process
import io.provenance.metadata.v1.Record
import io.provenance.metadata.v1.RecordInput
import io.provenance.metadata.v1.RecordOutput
import io.provenance.metadata.v1.RecordWrapper
import io.provenance.metadata.v1.ScopeResponse
import io.provenance.scope.encryption.ecies.ProvenanceKeyGenerator
import io.provenance.scope.encryption.model.DirectKeyRef
import io.provenance.scope.encryption.model.KeyRef
import io.provenance.scope.encryption.util.getAddress
import io.provenance.scope.objectstore.client.CachedOsClient
import io.provenance.scope.objectstore.util.base64Decode
import io.provenance.scope.util.MetadataAddress
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tech.figure.objectstore.gateway.configuration.ProvenanceProperties
import tech.figure.objectstore.gateway.exception.AccessDeniedException
import tech.figure.objectstore.gateway.repository.ScopePermissionsRepository
import tech.figure.objectstore.gateway.util.toByteString
import tech.figure.objectstore.gateway.util.toOwnerParty
import tech.figure.objectstore.gateway.util.toPartyType
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ScopeFetchServiceTest {
    lateinit var objectStoreClient: CachedOsClient
    lateinit var pbClient: PbClient
    lateinit var scopePermissionsRepository: ScopePermissionsRepository
    lateinit var encryptionKeys: Map<String, KeyRef>
    lateinit var provenanceProperties: ProvenanceProperties

    val scopeAddress = "scopeOrNotToScope"
    val scopeOwnerKey = generateEncryptionKeys(1).entries.first().toPair()

    lateinit var service: ScopeFetchService

    fun generateEncryptionKeys(num: Int) = (1..num).map {
        ProvenanceKeyGenerator.generateKeyPair().let {
            it.public.getAddress(false) to DirectKeyRef(it)
        }
    }.toMap()

    fun randomScopeAddress() = MetadataAddress.forScope(UUID.randomUUID()).toString()

    fun generateScope(numRecords: Int, scopeAddress: String = randomScopeAddress(), ownerKey: Pair<String, DirectKeyRef> = scopeOwnerKey) = ScopeResponse.newBuilder()
        .addAllRecords(
            (1..numRecords).map {
                val prefix = "record$it"
                RecordWrapper.newBuilder()
                    .setRecord(
                        Record.newBuilder()
                            .setName("${prefix}Name")
                            .addInputs(
                                RecordInput.newBuilder()
                                    .setHash("${prefix}InputHash".toHash())
                                    .setTypeName("${prefix}InputType")
                            ).addOutputs(
                                RecordOutput.newBuilder()
                                    .setHash("${prefix}OutputHash".toHash())
                            ).setProcess(
                                Process.newBuilder()
                                    .setHash("${prefix}ProcessHash".toHash())
                                    .setName("${prefix}OutputType")
                            )
                    )
                    .build()
            }
        )
        .apply {
            scopeBuilder.scopeBuilder
                .addOwners(ownerKey.first.toOwnerParty())
                .setValueOwnerAddress(ownerKey.first)
            scopeBuilder.scopeIdInfoBuilder.scopeAddr = scopeAddress
        }
        .build()

    fun ScopeResponse.replaceOwners(vararg newOwners: Party): ScopeResponse = toBuilder().apply {
        scopeBuilder.scopeBuilder.clearOwners()
            .addAllOwners(newOwners.toList())
    }.build()

    fun ScopeResponse.replaceDataAccessList(vararg addresses: String): ScopeResponse = toBuilder().apply {
        scopeBuilder.scopeBuilder.clearDataAccess()
            .addAllDataAccess(addresses.toList())
    }.build()

    fun String.toHash() = if (length % 2 == 0) this else padEnd(length + 1, '0')

    fun PbClient.setScope(scope: ScopeResponse) = every { pbClient.metadataClient.scope(match { it.scopeId == scope.scope.scopeIdInfo.scopeAddr }) } returns scope

    @BeforeEach
    fun setUp() {
        objectStoreClient = mockk()
        pbClient = mockk()
        scopePermissionsRepository = mockk()
        encryptionKeys = generateEncryptionKeys(2)
        provenanceProperties = ProvenanceProperties(false, "pio-fakenet-1", URI(""))

        // set default scope for tests
        pbClient.setScope(generateScope(2, scopeAddress = scopeAddress))

        val hashSlot = slot<ByteArray>()
        every { objectStoreClient.getJar(capture(hashSlot), any()) } answers { Futures.immediateFuture(ByteArrayInputStream(hashSlot.captured)) }

        service = ScopeFetchService(objectStoreClient, pbClient, scopePermissionsRepository, encryptionKeys + scopeOwnerKey, provenanceProperties)
    }

    @Test
    fun `fetchScope should deny access when no access for scope is set up`() {
        val encryptionPublicKey = encryptionKeys.values.first().publicKey
        val requesterAddress = encryptionPublicKey.getAddress(false)

        every { scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, requesterAddress) } returns listOf()

        val exception = assertThrows<AccessDeniedException> {
            service.fetchScopeForGrantee(scopeAddress, encryptionPublicKey, null)
        }

        assertNotNull(exception.status.description)
        assertContains(exception.status.description!!, "Scope access not granted to $requesterAddress", message = "The request should be denied for lack of access")
    }

    @Test
    fun `fetchScope should allow access for a scope owner even when no scope access is set up and owner is in encryption keys`() {
        val encryptionPublicKey = scopeOwnerKey.second.publicKey

        val records = service.fetchScopeForGrantee(scopeAddress, encryptionPublicKey, null)

        assertEquals(2, records.size)
        records.forEachIndexed { i, record ->
            val prefix = "record${i + 1}"
            assertEquals("${prefix}Name", record.name)
            assertEquals("${prefix}InputHash".toHash(), record.inputsList.first().hash)
            assertEquals("${prefix}InputHash".toHash().base64Decode().toByteString(), record.inputsList.first().objectBytes)
            assertEquals("${prefix}OutputHash".toHash(), record.outputsList.first().hash)
            assertEquals("${prefix}OutputHash".toHash().base64Decode().toByteString(), record.outputsList.first().objectBytes)
        }
    }

    @Test
    fun `fetchScope should allow access for a non-OWNER-type scope owner even when no scope access is set up and owner is in encryption keys`() {
        val encryptionPublicKey = encryptionKeys.values.first().publicKey
        val generatedScopeAddress = randomScopeAddress()
        pbClient.setScope(generateScope(2, scopeAddress = generatedScopeAddress).replaceOwners(encryptionPublicKey.getAddress(false).toPartyType(PartyType.PARTY_TYPE_CUSTODIAN)))

        val records = service.fetchScopeForGrantee(generatedScopeAddress, encryptionPublicKey, null)

        assertEquals(2, records.size)
        records.forEachIndexed { i, record ->
            val prefix = "record${i + 1}"
            assertEquals("${prefix}Name", record.name)
            assertEquals("${prefix}InputHash".toHash(), record.inputsList.first().hash)
            assertEquals("${prefix}InputHash".toHash().base64Decode().toByteString(), record.inputsList.first().objectBytes)
            assertEquals("${prefix}OutputHash".toHash(), record.outputsList.first().hash)
            assertEquals("${prefix}OutputHash".toHash().base64Decode().toByteString(), record.outputsList.first().objectBytes)
        }
    }

    @Test
    fun `fetchScope should allow access for a scope data access address even when no scope access is set up and data access owner is in encryption keys`() {
        val encryptionPublicKey = encryptionKeys.values.first().publicKey
        val generatedScopeAddress = randomScopeAddress()
        pbClient.setScope(generateScope(2, scopeAddress = generatedScopeAddress).replaceDataAccessList(encryptionPublicKey.getAddress(false)))

        val records = service.fetchScopeForGrantee(generatedScopeAddress, encryptionPublicKey, null)

        assertEquals(2, records.size)
        records.forEachIndexed { i, record ->
            val prefix = "record${i + 1}"
            assertEquals("${prefix}Name", record.name)
            assertEquals("${prefix}InputHash".toHash(), record.inputsList.first().hash)
            assertEquals("${prefix}InputHash".toHash().base64Decode().toByteString(), record.inputsList.first().objectBytes)
            assertEquals("${prefix}OutputHash".toHash(), record.outputsList.first().hash)
            assertEquals("${prefix}OutputHash".toHash().base64Decode().toByteString(), record.outputsList.first().objectBytes)
        }
    }

    @Test
    fun `fetchScope should deny access for a granter key that is not found in the configured keys list`() {
        val encryptionPublicKey = encryptionKeys.values.first().publicKey
        val requesterAddress = encryptionPublicKey.getAddress(false)
        val granterAddress = ProvenanceKeyGenerator.generateKeyPair().public.getAddress(false)

        every { scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, requesterAddress) } returns listOf(granterAddress)

        val exception = assertThrows<AccessDeniedException> {
            service.fetchScopeForGrantee(scopeAddress, encryptionPublicKey, null)
        }

        assertNotNull(exception.status.description)
        assertContains(exception.status.description!!, "Encryption key for granter $granterAddress not found", message = "The request should be denied for lack of configured key")
    }

    @Test
    fun `fetchScope should deny access for a scope owner with no encryption key and no granted access`() {
        val (scopeOwnerAddress, scopeOwnerKeyRef) = generateEncryptionKeys(1).entries.first()
        val scopeResponse = generateScope(2, ownerKey = scopeOwnerAddress to scopeOwnerKeyRef)
        every { pbClient.metadataClient.scope(any()) } returns scopeResponse
        every { scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, scopeOwnerAddress) } returns emptyList()
        val exception = assertThrows<AccessDeniedException> {
            service.fetchScopeForGrantee(scopeAddress, scopeOwnerKeyRef.publicKey, null)
        }
        assertNotNull(exception.status.description)
        assertContains(exception.status.description!!, "Scope access not granted to $scopeOwnerAddress")
    }

    @Test
    fun `fetchScope should deny access for an encryption key that does not own the scope and no granted access`() {
        val encryptionKeyPair = encryptionKeys.entries.first().toPair()
        val scopeResponse = generateScope(2, ownerKey = generateEncryptionKeys(1).entries.first().toPair())
        every { pbClient.metadataClient.scope(any()) } returns scopeResponse
        every { scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, encryptionKeyPair.first) } returns emptyList()
        val exception = assertThrows<AccessDeniedException> {
            service.fetchScopeForGrantee(scopeAddress, encryptionKeyPair.second.publicKey, null)
        }
        assertNotNull(exception.status.description)
        assertContains(exception.status.description!!, "Scope access not granted to ${encryptionKeyPair.first}")
    }
}
