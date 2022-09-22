package tech.figure.objectstore.gateway.service

import io.mockk.every
import io.mockk.mockk
import io.provenance.hdwallet.wallet.Account
import io.provenance.metadata.v1.PartyType
import io.provenance.metadata.v1.ScopeResponse
import io.provenance.scope.util.MetadataAddress
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import tech.figure.objectstore.gateway.helpers.bech32Address
import tech.figure.objectstore.gateway.helpers.genRandomAccount
import tech.figure.objectstore.gateway.helpers.mockScopeResponse
import tech.figure.objectstore.gateway.helpers.queryGrantCount
import tech.figure.objectstore.gateway.repository.ScopePermissionsRepository
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
class ScopePermissionsServiceTest {
    // Services
    lateinit var scopeFetchService: ScopeFetchService
    lateinit var scopePermissionsRepository: ScopePermissionsRepository
    lateinit var service: ScopePermissionsService

    // Addresses
    private val scopeOwner: Account = genRandomAccount()
    private val scopeAddress: String = MetadataAddress.forScope(UUID.randomUUID()).toString()
    private val ownerGranter: String = "ownerGranter"
    private val dataAccessGranter: String = "dataAccessGranter"
    private val sessionGranter: String = "sessionGranter"
    private val defaultGrantee: String = "grantee"

    fun setUp(
        accountAddresses: Set<String> = setOf(ownerGranter),
        scopeResponse: ScopeResponse = mockScopeResponse(
            address = scopeAddress,
            owners = setOf(
                PartyType.PARTY_TYPE_OWNER to scopeOwner.bech32Address,
                PartyType.PARTY_TYPE_INVESTOR to ownerGranter,
            ),
            dataAccessAddresses = setOf(dataAccessGranter),
            valueOwnerAddress = scopeOwner.bech32Address,
            sessionParties = setOf(PartyType.PARTY_TYPE_ORIGINATOR to sessionGranter),
        ),
    ) {
        scopeFetchService = mockk()
        scopePermissionsRepository = ScopePermissionsRepository()
        every { scopeFetchService.fetchScope(any(), any(), any()) } returns scopeResponse
        service = ScopePermissionsService(
            accountAddresses = accountAddresses,
            scopeFetchService = scopeFetchService,
            scopePermissionsRepository = scopePermissionsRepository,
        )
    }

    @Test
    fun `processAccessGrant rejects request that has no registered owner`() {
        // Establish the registered accounts as a set of a random address unrelated to anything else
        setUp(accountAddresses = setOf(genRandomAccount().bech32Address))
        val response = service.processAccessGrant(
            scopeAddress = scopeAddress,
            granteeAddress = defaultGrantee,
            grantSourceAddresses = setOf(scopeOwner.bech32Address),
        )
        assertTrue(
            actual = response is GrantResponse.Rejected,
            message = "The response should be a rejected response, but got type: ${response::class.qualifiedName}",
        )
        assertTrue(
            actual = "Skipping grant.  No granter is registered for scope [$scopeAddress]" in response.message,
            message = "Expected the correct rejection message to be included, but got: ${response.message}",
        )
    }

    @Test
    fun `processAccessGrant rejects request from unauthorized source`() {
        setUp()
        val response = service.processAccessGrant(
            scopeAddress = scopeAddress,
            granteeAddress = defaultGrantee,
            grantSourceAddresses = setOf(genRandomAccount().bech32Address),
        )
        assertTrue(
            response is GrantResponse.Rejected,
            message = "The response should be a rejected response, but got type: ${response::class.qualifiedName}",
        )
        assertTrue(
            actual = "Skipping grant. None of the authorized addresses [${scopeOwner.bech32Address}] for this grant were in the addresses that requested it" in response.message,
            message = "Expected the correct rejection message to be included, but got: ${response.message}",
        )
    }

    @Test
    fun `processAccessGrant returns error when an exception is thrown`() {
        setUp()
        val expectedException = IllegalStateException("Oh, no! A thing happened!")
        every { scopeFetchService.fetchScope(any(), any(), any()) } throws expectedException
        val response = service.processAccessGrant(
            scopeAddress = scopeAddress,
            granteeAddress = defaultGrantee,
            grantSourceAddresses = setOf(scopeOwner.bech32Address),
        )
        assertTrue(
            actual = response is GrantResponse.Error,
            message = "The response should be an error response, but got type: ${response::class.qualifiedName}",
        )
        assertEquals(
            expected = expectedException,
            actual = response.cause,
            message = "The returned exception should be the expected value",
        )
    }

    @Test
    fun `processAccessGrant accepts request from scope owner`() {
        setUp()
        val response = service.processAccessGrant(
            scopeAddress = scopeAddress,
            granteeAddress = defaultGrantee,
            grantSourceAddresses = setOf(scopeOwner.bech32Address),
        )
        assertGrantResponseAccepted(response)
    }

    @Test
    fun `processAccessGrant accepts request from additional authorized address`() {
        setUp()
        val authorizedAccount = genRandomAccount()
        val response = service.processAccessGrant(
            scopeAddress = scopeAddress,
            granteeAddress = defaultGrantee,
            grantSourceAddresses = setOf(authorizedAccount.bech32Address),
            additionalAuthorizedAddresses = setOf(authorizedAccount.bech32Address),
        )
        assertGrantResponseAccepted(response)
    }

    @Test
    fun `processAccessGrant accepts request with grant id and creates proper record`() {
        setUp()
        val response = service.processAccessGrant(
            scopeAddress = scopeAddress,
            granteeAddress = defaultGrantee,
            grantSourceAddresses = setOf(scopeOwner.bech32Address),
            grantId = "my-grant-id",
        )
        assertGrantResponseAccepted(response = response, expectedGrantId = "my-grant-id")
    }

    @Test
    fun `processAccessRevoke rejects request from unauthorized source`() {
        setUp()
        val response = service.processAccessRevoke(
            scopeAddress = scopeAddress,
            granteeAddress = defaultGrantee,
            revokeSourceAddresses = setOf(genRandomAccount().bech32Address),
        )
        assertTrue(
            actual = response is RevokeResponse.Rejected,
            message = "The response should be a rejected response, but got type: ${response::class.qualifiedName}",
        )
        assertTrue(
            actual = "Skipping revoke.  None of the authorized addresses [${scopeOwner.bech32Address}] for this revoke were in the addresses that requested it" in response.message,
            message = "Expected the correct rejection message to be included, but got: ${response.message}",
        )
    }

    @Test
    fun `processAccessRevoke returns error when an exception is thrown`() {
        setUp()
        val expectedException = IllegalArgumentException("Stop arguing with me")
        every { scopeFetchService.fetchScope(any(), any(), any()) } throws expectedException
        val response = service.processAccessRevoke(
            scopeAddress = scopeAddress,
            granteeAddress = defaultGrantee,
            revokeSourceAddresses = setOf(scopeOwner.bech32Address),
        )
        assertTrue(
            actual = response is RevokeResponse.Error,
            message = "The response should be an error response, but got type: ${response::class.qualifiedName}",
        )
        assertEquals(
            expected = expectedException,
            actual = response.cause,
            message = "The returned exception should be the expected value",
        )
    }

    @Test
    fun `processAccessRevoke accepts revoke from scope owner`() {
        setUp()
        service.processAccessGrant(
            scopeAddress = scopeAddress,
            granteeAddress = defaultGrantee,
            grantSourceAddresses = setOf(scopeOwner.bech32Address),
        ).also(::assertGrantResponseAccepted)
        service.processAccessRevoke(
            scopeAddress = scopeAddress,
            granteeAddress = defaultGrantee,
            revokeSourceAddresses = setOf(scopeOwner.bech32Address),
        ).also(::assertRevokeResponseAccepted)
    }

    private fun assertGrantResponseAccepted(
        response: GrantResponse,
        expectedGranter: String = ownerGranter,
        expectedScopeAddress: String = scopeAddress,
        expectedGrantee: String = defaultGrantee,
        expectedGrantId: String? = null,
    ) {
        assertTrue(
            actual = response is GrantResponse.Accepted,
            message = "The response should be an accepted response, but got type: ${response::class.qualifiedName}",
        )
        assertEquals(
            expected = expectedGranter,
            actual = response.granterAddress,
            message = "The correct granter should be used",
        )
        assertEquals(
            expected = 1,
            actual = getGrantCount(
                scopeAddr = expectedScopeAddress,
                grantee = expectedGrantee,
                granter = expectedGranter,
                grantId = expectedGrantId,
            ),
            message = "Expected a grant with the proper specifications to be created after an accepted request",
        )
    }

    private fun assertRevokeResponseAccepted(
        response: RevokeResponse,
        grantScopeAddress: String = scopeAddress,
        granteeAddress: String = defaultGrantee,
        expectedRevokedGrantsCount: Int = 1,
    ) {
        assertTrue(
            actual = response is RevokeResponse.Accepted,
            message = "The response should be an accepted response, but got type: ${response::class.qualifiedName}",
        )
        assertEquals(
            expected = expectedRevokedGrantsCount,
            actual = response.revokedGrantsCount,
            message = "Expected a different number of grants to be revoked",
        )
        assertEquals(
            expected = 0,
            actual = queryGrantCount(scopeAddr = grantScopeAddress, grantee = granteeAddress),
            message = "Expected no grants with the specified scope address to remain",
        )
    }

    private fun getGrantCount(
        scopeAddr: String = scopeAddress,
        grantee: String = defaultGrantee,
        granter: String = ownerGranter,
        grantId: String? = null,
    ): Long = queryGrantCount(
        scopeAddr = scopeAddr,
        grantee = grantee,
        granter = granter,
        grantId = grantId,
    )
}
