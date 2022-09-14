package io.provenance.objectstore.gateway.service

import io.mockk.every
import io.mockk.mockk
import io.provenance.client.grpc.PbClient
import io.provenance.eventstream.stream.models.Event
import io.provenance.eventstream.stream.models.TxEvent
import io.provenance.metadata.v1.Party
import io.provenance.metadata.v1.PartyType
import io.provenance.metadata.v1.ScopeResponse
import io.provenance.metadata.v1.SessionWrapper
import io.provenance.objectstore.gateway.configuration.DataMigration
import io.provenance.objectstore.gateway.eventstream.ContractKey
import io.provenance.objectstore.gateway.model.ScopePermissionsTable
import io.provenance.objectstore.gateway.repository.ScopePermissionsRepository
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.OffsetDateTime
import java.util.Base64
import kotlin.test.assertEquals

@SpringBootTest
class StreamEventHandlerServiceTest {
    @Autowired
    lateinit var dataMigration: DataMigration

    val onboardingOwnerAddress = "onboardingOwner"
    val otherOwnerAddress = "otherOwner"
    val sessionPartyAddress = "sessionParty"
    val dataAccessAddress = "dataAccess"
    val verifierAddress = "verifierAddress"
    val scopeAddress = "scopeAddress"

    lateinit var pbClient: PbClient
    lateinit var scopePermissionsRepository: ScopePermissionsRepository
    lateinit var service: StreamEventHandlerService

    @BeforeEach
    fun clearDb() {
        transaction { ScopePermissionsTable.deleteAll() }
    }

    fun setUp(vararg watchedAddresses: String = listOf(onboardingOwnerAddress, otherOwnerAddress, sessionPartyAddress, dataAccessAddress).toTypedArray()) {
        scopePermissionsRepository = ScopePermissionsRepository()
        pbClient = mockk()

        every { pbClient.metadataClient.scope(any()) } returns ScopeResponse.newBuilder()
            .apply {
                scopeBuilder.scopeBuilder
                    .addOwners(Party.newBuilder().setRole(PartyType.PARTY_TYPE_OWNER).setAddress(onboardingOwnerAddress))
                    .addOwners(Party.newBuilder().setRole(PartyType.PARTY_TYPE_AFFILIATE).setAddress(otherOwnerAddress))
                    .addDataAccess(dataAccessAddress)
            }.addSessions(
                SessionWrapper.newBuilder()
                    .apply {
                        sessionBuilder.addParties(Party.newBuilder().setRoleValue(PartyType.PARTY_TYPE_CUSTODIAN_VALUE).setAddress(sessionPartyAddress))
                    }
            )
            .build()

        service = StreamEventHandlerService(watchedAddresses.toSet(), scopePermissionsRepository, pbClient)
    }

    @Test
    fun `StreamEventHandlerService chooses onboarding scopeOwner as granter when that address is watched`() {
        setUp()

        submitEvent()

        assertEquals(listOf(onboardingOwnerAddress), scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, verifierAddress))
    }

    @Test
    fun `StreamEventHandlerService chooses other scopeOwner as granter when that address is watched and onboarding owner is not`() {
        setUp(otherOwnerAddress, sessionPartyAddress, dataAccessAddress)

        submitEvent()

        assertEquals(listOf(otherOwnerAddress), scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, verifierAddress))
    }

    @Test
    fun `StreamEventHandlerService chooses data access address as granter when that address is watched and onboarding, other owners are not`() {
        setUp(sessionPartyAddress, dataAccessAddress)

        submitEvent()

        assertEquals(listOf(dataAccessAddress), scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, verifierAddress))
    }

    @Test
    fun `StreamEventHandlerService chooses session address as granter when that address is watched and onboarding, other owners and data access are not`() {
        setUp(sessionPartyAddress)

        submitEvent()

        assertEquals(listOf(sessionPartyAddress), scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, verifierAddress))
    }

    private fun submitEvent() {
        service.handleEvent(
            TxEvent(
                10, OffsetDateTime.now(), "txHash", "wasm",
                listOf(
                    (ContractKey.EVENT_TYPE.eventName to "onboard_asset").toEvent(),
                    (ContractKey.ASSET_TYPE.eventName to "payable").toEvent(),
                    (ContractKey.SCOPE_ADDRESS.eventName to scopeAddress).toEvent(),
                    (ContractKey.SCOPE_OWNER_ADDRESS.eventName to onboardingOwnerAddress).toEvent(),
                    (ContractKey.VERIFIER_ADDRESS.eventName to verifierAddress).toEvent(),
                ),
                1000L, "nhash", "test event"
            )
        )
    }

    private fun String.base64Encode(): String = Base64.getEncoder().encodeToString(toByteArray())
    private fun Pair<String, String>.toEvent(): Event = Event(first.base64Encode(), second.base64Encode())
}
