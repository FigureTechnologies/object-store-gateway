package tech.figure.objectstore.gateway.service

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
import io.provenance.scope.encryption.ecies.ECUtils
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import tech.figure.objectstore.gateway.configuration.ProvenanceProperties
import tech.figure.objectstore.gateway.eventstream.AcContractKey
import tech.figure.objectstore.gateway.eventstream.GatewayExpectedAttribute
import tech.figure.objectstore.gateway.eventstream.GatewayExpectedEventType
import tech.figure.objectstore.gateway.model.ScopePermission
import tech.figure.objectstore.gateway.model.ScopePermissionsTable
import tech.figure.objectstore.gateway.repository.ScopePermissionsRepository
import tech.figure.objectstore.gateway.util.toByteString
import java.time.OffsetDateTime
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@SpringBootTest
class StreamEventHandlerServiceTest {
    val onboardingOwnerAddress = "onboardingOwner"
    val otherOwnerAddress = "otherOwner"
    val sessionPartyAddress = "sessionParty"
    val dataAccessAddress = "dataAccess"
    val grantee: Account = genRandomAccount()
    val scopeAddress = "scopeAddress"
    val valueOwner: Account = genRandomAccount() // Access the standard testnet account address

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
                scopeBuilder.scopeBuilder.valueOwnerAddress = valueOwner.bech32Address
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
                            ECUtils.convertPublicKeyToBytes(txSigner.keyPair.publicKey.toJavaECPublicKey())
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

        assertEquals(listOf(onboardingOwnerAddress), scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, grantee.bech32Address))
    }

    @Test
    fun `StreamEventHandlerService chooses onboarding scopeOwner as granter when that address is watched from gateway grant event`() {
        setUp()

        submitGatewayEvent(GatewayExpectedEventType.ACCESS_GRANT)

        assertEquals(listOf(onboardingOwnerAddress), scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, grantee.bech32Address))
    }

    @Test
    fun `StreamEventHandlerService chooses other scopeOwner as granter when that address is watched and onboarding owner is not from asset classification event`() {
        setUp(otherOwnerAddress, sessionPartyAddress, dataAccessAddress)

        submitAssetClassificationEvent()

        assertEquals(listOf(otherOwnerAddress), scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, grantee.bech32Address))
    }

    @Test
    fun `StreamEventHandlerService chooses other scopeOwner as granter when that address is watched and onboarding owner is not from gateway grant event`() {
        setUp(otherOwnerAddress, sessionPartyAddress, dataAccessAddress)

        submitGatewayEvent(GatewayExpectedEventType.ACCESS_GRANT)

        assertEquals(listOf(otherOwnerAddress), scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, grantee.bech32Address))
    }

    @Test
    fun `StreamEventHandlerService chooses data access address as granter when that address is watched and onboarding, other owners are not from asset classification event`() {
        setUp(sessionPartyAddress, dataAccessAddress)

        submitAssetClassificationEvent()

        assertEquals(listOf(dataAccessAddress), scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, grantee.bech32Address))
    }

    @Test
    fun `StreamEventHandlerService chooses data access address as granter when that address is watched and onboarding, other owners are not from gateway grant event`() {
        setUp(sessionPartyAddress, dataAccessAddress)

        submitGatewayEvent(GatewayExpectedEventType.ACCESS_GRANT)

        assertEquals(listOf(dataAccessAddress), scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, grantee.bech32Address))
    }

    @Test
    fun `StreamEventHandlerService chooses session address as granter when that address is watched and onboarding, other owners and data access are not from asset classification event`() {
        setUp(sessionPartyAddress)

        submitAssetClassificationEvent()

        assertEquals(listOf(sessionPartyAddress), scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, grantee.bech32Address))
    }

    @Test
    fun `StreamEventHandlerService chooses session address as granter when that address is watched and onboarding, other owners and data access are not from gateway grant event`() {
        setUp(sessionPartyAddress)

        submitGatewayEvent(GatewayExpectedEventType.ACCESS_GRANT)

        assertEquals(listOf(sessionPartyAddress), scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, grantee.bech32Address))
    }

    @Test
    fun `StreamEventHandlerService ignores event with scope that does not include registered address from gateway grant event`() {
        setUp(watchedAddresses = emptyArray())

        submitGatewayEvent(GatewayExpectedEventType.ACCESS_GRANT)

        assertEquals(
            expected = emptyList(),
            actual = scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, grantee.bech32Address),
        )
    }

    @Test
    fun `StreamEventHandlerService ignores event with no related signer from gateway grant event`() {
        // Use a rando signer to ensure that bad signers/actors are ignored
        setUp(txSigner = genRandomAccount())

        submitGatewayEvent(GatewayExpectedEventType.ACCESS_GRANT)

        assertEquals(
            expected = emptyList(),
            actual = scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, grantee.bech32Address),
        )
    }

    @Test
    fun `StreamEventHandlerService successfully revokes access when requested by scope owner`() {
        setUp()

        submitGatewayEvent(GatewayExpectedEventType.ACCESS_GRANT)

        assertEquals(
            expected = listOf(onboardingOwnerAddress),
            actual = scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, grantee.bech32Address),
            message = "Access should be given to grantee from the scope owner",
        )

        submitGatewayEvent(GatewayExpectedEventType.ACCESS_REVOKE)

        assertEquals(
            expected = emptyList(),
            actual = scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, grantee.bech32Address),
            message = "All access should be revoked from the grantee for the scope after the revoke event is processed",
        )
    }

    @Test
    fun `StreamEventHandlerService successfully revokes access when requested by grantee`() {
        setUp()

        submitGatewayEvent(GatewayExpectedEventType.ACCESS_GRANT)

        assertEquals(
            expected = listOf(onboardingOwnerAddress),
            actual = scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, grantee.bech32Address),
            message = "Access should be given to grantee from the scope owner",
        )

        setUp(txSigner = grantee)

        submitGatewayEvent(GatewayExpectedEventType.ACCESS_REVOKE)

        assertEquals(
            expected = emptyList(),
            actual = scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, grantee.bech32Address),
            message = "Access should be revoked by the grantee itself",
        )
    }

    @Test
    fun `StreamEventHandlerService skips revoke when no signers match`() {
        setUp()

        submitGatewayEvent(GatewayExpectedEventType.ACCESS_GRANT)

        assertEquals(
            expected = listOf(onboardingOwnerAddress),
            actual = scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, grantee.bech32Address),
            message = "Access should be given to grantee from the scope owner",
        )

        setUp(txSigner = genRandomAccount())

        submitGatewayEvent(GatewayExpectedEventType.ACCESS_REVOKE)

        assertEquals(
            expected = listOf(onboardingOwnerAddress),
            actual = scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, grantee.bech32Address),
            message = "Access should remain for the grantee because the revoke was ignored for not having a valid signer",
        )
    }

    @Test
    fun `StreamEventHandlerService allows multiple duplicate grants with different grant ids`() {
        setUp()

        val firstGrantId = "first-grant-id"
        val secondGrantId = "second-grant-id"

        submitGatewayEvent(GatewayExpectedEventType.ACCESS_GRANT, firstGrantId)
        submitGatewayEvent(GatewayExpectedEventType.ACCESS_GRANT, secondGrantId)
        submitGatewayEvent(GatewayExpectedEventType.ACCESS_GRANT)

        assertEquals(
            expected = listOf(onboardingOwnerAddress),
            actual = scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, grantee.bech32Address).distinct(),
            message = "All granters should be located for the given combinations, but because they all use the same granter, only one result should be returned",
        )
        assertScopePermissionExists(grantId = firstGrantId)
        assertScopePermissionExists(grantId = secondGrantId)
        assertScopePermissionExists(grantId = null)
    }

    @Test
    fun `StreamEventHandlerService removes any and disregards grant id when revoke expression does not include it`() {
        setUp()

        val firstGrantId = "the best grant ever"
        val secondGrantId = "the betterest grant ever"
        val thirdGrantId = "I don't even have a fake adjective for this one"

        submitGatewayEvent(GatewayExpectedEventType.ACCESS_GRANT, grantId = firstGrantId)
        submitGatewayEvent(GatewayExpectedEventType.ACCESS_GRANT, grantId = secondGrantId)
        submitGatewayEvent(GatewayExpectedEventType.ACCESS_GRANT, grantId = thirdGrantId)
        submitGatewayEvent(GatewayExpectedEventType.ACCESS_GRANT, grantId = null)

        assertScopePermissionExists(grantId = firstGrantId)
        assertScopePermissionExists(grantId = secondGrantId)
        assertScopePermissionExists(grantId = thirdGrantId)
        assertScopePermissionExists(grantId = null)

        submitGatewayEvent(GatewayExpectedEventType.ACCESS_REVOKE)

        assertScopePermissionDoesNotExist(grantId = firstGrantId, messagePrefix = "The first grant should be removed after an access revoke does not supply a grant id")
        assertScopePermissionDoesNotExist(grantId = secondGrantId, messagePrefix = "The second grant should be removed after an access revoke does not supply a grant id")
        assertScopePermissionDoesNotExist(grantId = thirdGrantId, messagePrefix = "The third grant should be removed after an access revoke does not supply a grant id")
        assertScopePermissionDoesNotExist(grantId = null, messagePrefix = "The null id grant should be removed after an access revoke does not supply a grant id")

        assertEquals(
            expected = emptyList(),
            actual = scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, grantee.bech32Address),
            message = "All grants should be removed when the revocation does not supply a targeted grant id",
        )
    }

    @Test
    fun `StreamEventHandlerService removes grant id specifically upon request`() {
        setUp()

        val firstGrantId = "first-grant"
        val secondGrantId = "second-grant"

        submitGatewayEvent(GatewayExpectedEventType.ACCESS_GRANT, grantId = firstGrantId)
        submitGatewayEvent(GatewayExpectedEventType.ACCESS_GRANT, grantId = secondGrantId)
        submitGatewayEvent(GatewayExpectedEventType.ACCESS_GRANT)

        assertScopePermissionExists(grantId = firstGrantId)
        assertScopePermissionExists(grantId = secondGrantId)
        assertScopePermissionExists(grantId = null)

        submitGatewayEvent(GatewayExpectedEventType.ACCESS_REVOKE, grantId = "other grant id")

        assertScopePermissionExists(grantId = firstGrantId, messagePrefix = "First grant should still exist after targeting a nonexistent grant id")
        assertScopePermissionExists(grantId = secondGrantId, messagePrefix = "Second grant should still exist after targeting a nonexistent grant id")
        assertScopePermissionExists(grantId = null, messagePrefix = "Null id grant should still exist after targeting a nonexistent grant id")

        submitGatewayEvent(GatewayExpectedEventType.ACCESS_REVOKE, grantId = firstGrantId)

        assertScopePermissionDoesNotExist(grantId = firstGrantId, messagePrefix = "First grant should be deleted after an access revoke requested it")
        assertScopePermissionExists(grantId = secondGrantId, messagePrefix = "Second grant should still exist after only deleting the first grant id's record")
        assertScopePermissionExists(grantId = null, messagePrefix = "Null id grant should still exist after only deleting the first grant id's record")

        submitGatewayEvent(GatewayExpectedEventType.ACCESS_REVOKE, grantId = secondGrantId)

        assertScopePermissionDoesNotExist(grantId = firstGrantId, messagePrefix = "First grant should remain deleted after deleting the second grant")
        assertScopePermissionDoesNotExist(grantId = secondGrantId, messagePrefix = "Second grant should be deleted after an access revoke requested it")
        assertScopePermissionExists(grantId = null, messagePrefix = "Null id grant should still exist after only deleting the second grant id's record")

        submitGatewayEvent(GatewayExpectedEventType.ACCESS_REVOKE)

        assertScopePermissionDoesNotExist(grantId = firstGrantId, messagePrefix = "First grant should remain deleted after removing grants with a null grant id")
        assertScopePermissionDoesNotExist(grantId = secondGrantId, messagePrefix = "Second grant should remain deleted after removing grants with a null grant id")
        assertScopePermissionDoesNotExist(grantId = null, messagePrefix = "Null id grant should be deleted after an access revoke requested all grants to be deleted")

        assertEquals(
            expected = emptyList(),
            actual = scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, grantee.bech32Address),
            message = "After all grants have been deleted, no granter addresses should be available to the grantee",
        )
    }

    private fun submitAssetClassificationEvent() {
        submitEvent(
            attributes = listOf(
                AcContractKey.EVENT_TYPE.eventName to "onboard_asset",
                AcContractKey.ASSET_TYPE.eventName to "payable",
                AcContractKey.SCOPE_ADDRESS.eventName to scopeAddress,
                AcContractKey.SCOPE_OWNER_ADDRESS.eventName to onboardingOwnerAddress,
                AcContractKey.VERIFIER_ADDRESS.eventName to grantee.bech32Address,
            )
        )
    }

    private fun submitGatewayEvent(eventType: GatewayExpectedEventType, grantId: String? = null) {
        submitEvent(
            attributes = listOfNotNull(
                GatewayExpectedAttribute.EVENT_TYPE.key to eventType.wasmName,
                GatewayExpectedAttribute.SCOPE_ADDRESS.key to scopeAddress,
                GatewayExpectedAttribute.TARGET_ACCOUNT.key to grantee.bech32Address,
                grantId?.let { GatewayExpectedAttribute.ACCESS_GRANT_ID.key to it },
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

    private fun assertScopePermissionExists(
        targetScopeAddress: String = scopeAddress,
        granterAddress: String = onboardingOwnerAddress,
        granteeAddress: String = grantee.bech32Address,
        grantId: String? = null,
        messagePrefix: String? = null,
    ): ScopePermission = findScopePermissionOrNull(
        scopeAddress = targetScopeAddress,
        granterAddress = granterAddress,
        granteeAddress = granteeAddress,
        grantId = grantId,
    ).let { scopePermissionOrNull ->
        assertNotNull(
            actual = scopePermissionOrNull,
            message = "${messagePrefix ?: "Scope permission does not exist"}: Expected scope permission with scope address [$targetScopeAddress], granter [$granterAddress], grantee [$granteeAddress], and grant ID [$grantId] to exist",
        )
        scopePermissionOrNull
    }

    private fun assertScopePermissionDoesNotExist(
        targetScopeAddress: String = scopeAddress,
        granterAddress: String = onboardingOwnerAddress,
        granteeAddress: String = grantee.bech32Address,
        grantId: String? = null,
        messagePrefix: String? = null,
    ) {
        assertNull(
            actual = findScopePermissionOrNull(
                scopeAddress = targetScopeAddress,
                granterAddress = granterAddress,
                granteeAddress = granteeAddress,
                grantId = grantId,
            ),
            message = "${messagePrefix ?: "Scope permission should not exist"}: Found scope permission with scope address [$targetScopeAddress], granter [$granterAddress], grantee [$granteeAddress], and grant ID [$grantId]",
        )
    }

    private fun findScopePermissionOrNull(
        scopeAddress: String,
        granterAddress: String,
        granteeAddress: String,
        grantId: String?,
    ): ScopePermission? = transaction {
        ScopePermission.find {
            ScopePermissionsTable.scopeAddress.eq(scopeAddress)
                .and { ScopePermissionsTable.granterAddress eq granterAddress }
                .and { ScopePermissionsTable.granteeAddress eq granteeAddress }
                .and { ScopePermissionsTable.grantId eq grantId }
        }.singleOrNull()
    }

    private fun String.base64Encode(): String = Base64.getEncoder().encodeToString(toByteArray())
    private fun Pair<String, String>.toEvent(): Event = Event(first.base64Encode(), second.base64Encode())

    private fun genRandomAccount(): Account = Wallet.fromMnemonic(
        hrp = "tp",
        passphrase = "",
        mnemonicWords = MnemonicWords.generate(strength = 256),
        testnet = true,
    )["m/44'/1'/0'/0/0'"]

    private val Account.bech32Address: String
        get() = address.value
}
