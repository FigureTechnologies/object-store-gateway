package tech.figure.objectstore.gateway.service

import cosmos.crypto.secp256k1.Keys.PubKey
import cosmos.tx.v1beta1.ServiceOuterClass.GetTxResponse
import cosmos.tx.v1beta1.TxOuterClass.SignerInfo
import io.mockk.every
import io.mockk.mockk
import io.provenance.client.grpc.PbClient
import io.provenance.client.protobuf.extensions.toAny
import io.provenance.hdwallet.ec.extensions.toJavaECPublicKey
import io.provenance.hdwallet.wallet.Account
import io.provenance.metadata.v1.PartyType
import io.provenance.scope.encryption.ecies.ECUtils
import io.provenance.scope.util.MetadataAddress
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import tech.figure.block.api.proto.BlockOuterClass.Attribute
import tech.figure.block.api.proto.attribute
import tech.figure.block.api.proto.txEvent
import tech.figure.objectstore.gateway.configuration.ProvenanceProperties
import tech.figure.objectstore.gateway.eventstream.BlockApiGatewayEvent
import tech.figure.objectstore.gateway.eventstream.GatewayExpectedAttribute
import tech.figure.objectstore.gateway.eventstream.GatewayExpectedEventType
import tech.figure.objectstore.gateway.helpers.bech32Address
import tech.figure.objectstore.gateway.helpers.genRandomAccount
import tech.figure.objectstore.gateway.helpers.mockScopeResponse
import tech.figure.objectstore.gateway.model.ScopePermission
import tech.figure.objectstore.gateway.model.ScopePermissionsTable
import tech.figure.objectstore.gateway.repository.ScopePermissionsRepository
import tech.figure.objectstore.gateway.util.toByteString
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

@SpringBootTest
class StreamEventHandlerServiceTest {
    val onboardingOwner: Account = genRandomAccount() // Access the standard testnet account address
    val priorityOwnerAddress = genRandomAccount().bech32Address // This is the first value located in ScopePermissionsService.findRegisteredScopeOwnerAddress(), and will be the granter in most circumstances
    val sessionPartyAddress = genRandomAccount().bech32Address
    val dataAccessAddress = genRandomAccount().bech32Address
    val grantee: Account = genRandomAccount()
    val scopeAddress = MetadataAddress.forScope(UUID.randomUUID()).toString()

    lateinit var pbClient: PbClient
    lateinit var provenanceProperties: ProvenanceProperties
    lateinit var addressVerificationService: AddressVerificationService
    lateinit var scopeFetchService: ScopeFetchService
    lateinit var scopePermissionsService: ScopePermissionsService
    lateinit var scopePermissionsRepository: ScopePermissionsRepository
    lateinit var service: StreamEventHandlerService

    @BeforeEach
    fun clearDb() {
        transaction { ScopePermissionsTable.deleteAll() }
    }

    fun setUp(
        vararg watchedAddresses: String = listOf(priorityOwnerAddress, sessionPartyAddress, dataAccessAddress).toTypedArray(),
        txSigner: Account = onboardingOwner,
    ) {
        scopePermissionsRepository = ScopePermissionsRepository()
        pbClient = mockk()
        scopeFetchService = mockk()
        provenanceProperties = mockk()
        addressVerificationService = AddressVerificationService(provenanceProperties = provenanceProperties)

        every { provenanceProperties.mainNet } returns false
        every { scopeFetchService.fetchScope(any(), any(), any()) } returns mockScopeResponse(
            address = scopeAddress,
            owners = setOf(
                PartyType.PARTY_TYPE_OWNER to onboardingOwner.bech32Address,
                PartyType.PARTY_TYPE_AFFILIATE to priorityOwnerAddress,
            ),
            dataAccessAddresses = setOf(dataAccessAddress),
            valueOwnerAddress = onboardingOwner.bech32Address,
            sessionParties = setOf(PartyType.PARTY_TYPE_CUSTODIAN to sessionPartyAddress),
        )
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

        scopePermissionsService = ScopePermissionsService(
            accountAddresses = watchedAddresses.toSet(),
            addressVerificationService = addressVerificationService,
            scopeFetchService = scopeFetchService,
            scopePermissionsRepository = scopePermissionsRepository,
        )
        service = StreamEventHandlerService(
            scopePermissionsService = scopePermissionsService,
            pbClient = pbClient,
            provenanceProperties = provenanceProperties,
        )
    }

    @Test
    fun `StreamEventHandlerService chooses onboarding scopeOwner as granter when that address is watched from gateway grant event`() {
        setUp()

        submitGatewayEvent(GatewayExpectedEventType.ACCESS_GRANT)

        assertEquals(listOf(priorityOwnerAddress), scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, grantee.bech32Address))
    }

    @Test
    fun `StreamEventHandlerService chooses other scopeOwner as granter when that address is watched and onboarding owner is not from gateway grant event`() {
        setUp(priorityOwnerAddress, sessionPartyAddress, dataAccessAddress)

        submitGatewayEvent(GatewayExpectedEventType.ACCESS_GRANT)

        assertEquals(listOf(priorityOwnerAddress), scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, grantee.bech32Address))
    }

    @Test
    fun `StreamEventHandlerService chooses data access address as granter when that address is watched and onboarding, other owners are not from gateway grant event`() {
        setUp(sessionPartyAddress, dataAccessAddress)

        submitGatewayEvent(GatewayExpectedEventType.ACCESS_GRANT)

        assertEquals(listOf(dataAccessAddress), scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, grantee.bech32Address))
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
            expected = listOf(priorityOwnerAddress),
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
            expected = listOf(priorityOwnerAddress),
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
            expected = listOf(priorityOwnerAddress),
            actual = scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, grantee.bech32Address),
            message = "Access should be given to grantee from the scope owner",
        )

        setUp(txSigner = genRandomAccount())

        submitGatewayEvent(GatewayExpectedEventType.ACCESS_REVOKE)

        assertEquals(
            expected = listOf(priorityOwnerAddress),
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
            expected = listOf(priorityOwnerAddress),
            actual = scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, grantee.bech32Address).distinct(),
            message = "All granters should be located for the given combinations, but because they all use the same granter, only one result should be returned",
        )
        assertScopePermissionExists(grantId = firstGrantId)
        assertScopePermissionExists(grantId = secondGrantId)
        assertScopePermissionExists(grantId = null)
    }

    @Test
    fun `StreamEventHandlerService gracefully handles unrelated access revoke`() {
        setUp()

        listOf(
            null to "Unexpected revocation with no target grant id should be handled without error",
            "grantId" to "Unexpected revocation with explicit grant id should be handled without error",
        ).forEach { (grantId, errorMessage) ->
            try {
                submitGatewayEvent(GatewayExpectedEventType.ACCESS_REVOKE, grantId = grantId)
            } catch (e: Exception) {
                fail(message = errorMessage, cause = e)
            }
        }
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
        txHash: String = "txHash",
        eventType: String = "wasm",
        attributes: List<Pair<String, String>>,
    ) {
        service.handleEvent(
            event = txEvent {
                this.height = blockHeight
                this.txHash = txHash
                this.eventType = eventType
                this.attributes.addAll(attributes.map { it.toEvent() })
            }.let(::BlockApiGatewayEvent)
        )
    }

    private fun assertScopePermissionExists(
        targetScopeAddress: String = scopeAddress,
        granterAddress: String = priorityOwnerAddress,
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

    private fun Pair<String, String>.toEvent(): Attribute = attribute {
        this.key = first
        this.value = second
    }
}
