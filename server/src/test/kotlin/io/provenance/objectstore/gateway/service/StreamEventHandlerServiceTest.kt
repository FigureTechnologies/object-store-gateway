package io.provenance.objectstore.gateway.service

import cosmos.crypto.secp256k1.Keys.PubKey
import cosmos.tx.v1beta1.ServiceOuterClass.GetTxResponse
import cosmos.tx.v1beta1.TxOuterClass.SignerInfo
import io.mockk.every
import io.mockk.mockk
import io.provenance.client.grpc.PbClient
import io.provenance.client.protobuf.extensions.toAny
import io.provenance.eventstream.stream.models.Event
import io.provenance.eventstream.stream.models.TxEvent
import io.provenance.hdwallet.bip39.MnemonicWords
import io.provenance.hdwallet.ec.extensions.toJavaECPublicKey
import io.provenance.hdwallet.wallet.Account
import io.provenance.hdwallet.wallet.Wallet
import io.provenance.metadata.v1.Party
import io.provenance.metadata.v1.PartyType
import io.provenance.metadata.v1.ScopeResponse
import io.provenance.metadata.v1.SessionWrapper
import io.provenance.objectstore.gateway.configuration.DataMigration
import io.provenance.objectstore.gateway.configuration.ProvenanceProperties
import io.provenance.objectstore.gateway.eventstream.AcContractKey
import io.provenance.objectstore.gateway.eventstream.GatewayExpectedAttribute
import io.provenance.objectstore.gateway.eventstream.GatewayExpectedEventType
import io.provenance.objectstore.gateway.model.ScopePermissionsTable
import io.provenance.objectstore.gateway.repository.ScopePermissionsRepository
import io.provenance.objectstore.gateway.util.toByteString
import io.provenance.scope.encryption.ecies.ECUtils
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
    val granteeAddress = "verifierAddress"
    val scopeAddress = "scopeAddress"
    val valueOwner: Account = Wallet.fromMnemonic(
        hrp = "tp",
        passphrase = "",
        mnemonicWords = MnemonicWords.generate(strength = 256),
        testnet = true,
    )["m/44'/1'/0'/0/0'"] // Access the standard testnet account address

    lateinit var pbClient: PbClient
    lateinit var provenanceProperties: ProvenanceProperties
    lateinit var scopePermissionsRepository: ScopePermissionsRepository
    lateinit var service: StreamEventHandlerService

    @BeforeEach
    fun clearDb() {
        transaction { ScopePermissionsTable.deleteAll() }
    }

    fun setUp(
        vararg watchedAddresses: String = listOf(onboardingOwnerAddress, otherOwnerAddress, sessionPartyAddress, dataAccessAddress).toTypedArray(),
        txSigner: Account = valueOwner,
    ) {
        scopePermissionsRepository = ScopePermissionsRepository()
        pbClient = mockk()
        provenanceProperties = mockk()

        every { provenanceProperties.mainNet } returns false
        every { pbClient.metadataClient.scope(any()) } returns ScopeResponse.newBuilder()
            .apply {
                scopeBuilder.scopeBuilder
                    .addOwners(Party.newBuilder().setRole(PartyType.PARTY_TYPE_OWNER).setAddress(onboardingOwnerAddress))
                    .addOwners(Party.newBuilder().setRole(PartyType.PARTY_TYPE_AFFILIATE).setAddress(otherOwnerAddress))
                    .addDataAccess(dataAccessAddress)
                scopeBuilder.scopeBuilder.valueOwnerAddress = valueOwner.address.value
            }.addSessions(
                SessionWrapper.newBuilder()
                    .apply {
                        sessionBuilder.addParties(Party.newBuilder().setRoleValue(PartyType.PARTY_TYPE_CUSTODIAN_VALUE).setAddress(sessionPartyAddress))
                    }
            )
            .build()
        every { pbClient.cosmosService.getTx(any()) } returns GetTxResponse.newBuilder()
            .apply {
                txBuilder.authInfoBuilder.addSignerInfos(
                    SignerInfo.newBuilder().apply {
                        publicKey = PubKey.newBuilder().setKey(
                            ECUtils.convertPublicKeyToBytes(valueOwner.keyPair.publicKey.toJavaECPublicKey())
                                .toByteString()
                        ).build().toAny()
                    }
                )
            }.build()

        service = StreamEventHandlerService(watchedAddresses.toSet(), scopePermissionsRepository, pbClient, provenanceProperties)
    }

    @Test
    fun `StreamEventHandlerService chooses onboarding scopeOwner as granter when that address is watched from asset classification event`() {
        setUp()

        submitAssetClassificationEvent()

        assertEquals(listOf(onboardingOwnerAddress), scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, granteeAddress))
    }

    @Test
    fun `StreamEventHandlerService chooses onboarding scopeOwner as granter when that address is watched from gateway grant event`() {
        setUp()

        submitGatewayEvent(GatewayExpectedEventType.ACCESS_GRANT)

        assertEquals(listOf(onboardingOwnerAddress), scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, granteeAddress))
    }

    @Test
    fun `StreamEventHandlerService chooses other scopeOwner as granter when that address is watched and onboarding owner is not from asset classification event`() {
        setUp(otherOwnerAddress, sessionPartyAddress, dataAccessAddress)

        submitAssetClassificationEvent()

        assertEquals(listOf(otherOwnerAddress), scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, granteeAddress))
    }

    @Test
    fun `StreamEventHandlerService chooses other scopeOwner as granter when that address is watched and onboarding owner is not from gateway grant event`() {
        setUp(otherOwnerAddress, sessionPartyAddress, dataAccessAddress)

        submitGatewayEvent(GatewayExpectedEventType.ACCESS_GRANT)

        assertEquals(listOf(otherOwnerAddress), scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, granteeAddress))
    }

    @Test
    fun `StreamEventHandlerService chooses data access address as granter when that address is watched and onboarding, other owners are not from asset classification event`() {
        setUp(sessionPartyAddress, dataAccessAddress)

        submitAssetClassificationEvent()

        assertEquals(listOf(dataAccessAddress), scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, granteeAddress))
    }

    @Test
    fun `StreamEventHandlerService chooses data access address as granter when that address is watched and onboarding, other owners are not from gateway grant event`() {
        setUp(sessionPartyAddress, dataAccessAddress)

        submitGatewayEvent(GatewayExpectedEventType.ACCESS_GRANT)

        assertEquals(listOf(dataAccessAddress), scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, granteeAddress))
    }

    @Test
    fun `StreamEventHandlerService chooses session address as granter when that address is watched and onboarding, other owners and data access are not from asset classification event`() {
        setUp(sessionPartyAddress)

        submitAssetClassificationEvent()

        assertEquals(listOf(sessionPartyAddress), scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, granteeAddress))
    }

    @Test
    fun `StreamEventHandlerService chooses session address as granter when that address is watched and onboarding, other owners and data access are not from gateway grant event`() {
        setUp(sessionPartyAddress)

        submitGatewayEvent(GatewayExpectedEventType.ACCESS_GRANT)

        assertEquals(listOf(sessionPartyAddress), scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, granteeAddress))
    }

    private fun submitAssetClassificationEvent() {
        submitEvent(
            attributes = listOf(
                AcContractKey.EVENT_TYPE.eventName to "onboard_asset",
                AcContractKey.ASSET_TYPE.eventName to "payable",
                AcContractKey.SCOPE_ADDRESS.eventName to scopeAddress,
                AcContractKey.SCOPE_OWNER_ADDRESS.eventName to onboardingOwnerAddress,
                AcContractKey.VERIFIER_ADDRESS.eventName to granteeAddress,
            )
        )
    }

    private fun submitGatewayEvent(eventType: GatewayExpectedEventType) {
        submitEvent(
            attributes = listOf(
                GatewayExpectedAttribute.EVENT_TYPE.key to eventType.wasmName,
                GatewayExpectedAttribute.SCOPE_ADDRESS.key to scopeAddress,
                GatewayExpectedAttribute.TARGET_ACCOUNT.key to granteeAddress,
            )
        )
    }

    private fun submitEvent(
        blockHeight: Long = 10L,
        blockDateTime: OffsetDateTime = OffsetDateTime.now(),
        txHash: String = "txHash",
        eventType: String = "wasm",
        attributes: List<Pair<String, String>>,
        fee: Long = 1000L,
        denom: String = "nhash",
        note: String = "test event",
    ) {
        service.handleEvent(
            event = TxEvent(
                blockHeight = blockHeight,
                blockDateTime = blockDateTime,
                txHash = txHash,
                eventType = eventType,
                attributes = attributes.map { it.toEvent() },
                fee = fee,
                denom = denom,
                note = note,
            )
        )
    }

    private fun String.base64Encode(): String = Base64.getEncoder().encodeToString(toByteArray())
    private fun Pair<String, String>.toEvent(): Event = Event(first.base64Encode(), second.base64Encode())
}
