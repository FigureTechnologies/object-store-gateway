package tech.figure.objectstore.gateway.service

import io.mockk.every
import io.mockk.mockk
import io.provenance.hdwallet.wallet.Account
import io.provenance.metadata.v1.PartyType
import io.provenance.metadata.v1.ScopeResponse
import io.provenance.scope.util.MetadataAddress
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import tech.figure.objectstore.gateway.helpers.bech32Address
import tech.figure.objectstore.gateway.helpers.genRandomAccount
import tech.figure.objectstore.gateway.helpers.mockScopeResponse
import tech.figure.objectstore.gateway.helpers.queryGrantCount
import tech.figure.objectstore.gateway.model.ScopePermission
import tech.figure.objectstore.gateway.model.ScopePermissionsTable
import tech.figure.objectstore.gateway.repository.ScopePermissionsRepository
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        val response = doAccessGrant()
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
        val response = doAccessGrant(sources = setOf(genRandomAccount().bech32Address))
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
        val response = doAccessGrant()
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
        val response = doAccessGrant()
        assertGrantResponseAccepted(response)
    }

    @Test
    fun `processAccessGrant accepts request from additional authorized address`() {
        setUp()
        val authorizedAccount = genRandomAccount()
        val response = doAccessGrant(
            sources = setOf(authorizedAccount.bech32Address),
            additionalAddresses = setOf(authorizedAccount.bech32Address),
        )
        assertGrantResponseAccepted(response)
    }

    @Test
    fun `processAccessGrant accepts request with grant id and creates proper record`() {
        setUp()
        val response = doAccessGrant(grantId = "my-grant-id")
        assertGrantResponseAccepted(response = response, expectedGrantId = "my-grant-id")
    }

    @Test
    fun `processAccessRevoke rejects request from unauthorized source`() {
        setUp()
        val response = doAccessRevoke(sources = setOf(genRandomAccount().bech32Address))
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
        val response = doAccessRevoke()
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
        doAccessGrant().also(::assertGrantResponseAccepted)
        doAccessRevoke().also(::assertRevokeResponseAccepted)
    }

    @Test
    fun `processAccessRevoke accepts revoke from additional authorized address`() {
        setUp()
        doAccessGrant().also(::assertGrantResponseAccepted)
        val authorizedAccount = genRandomAccount()
        doAccessRevoke(
            sources = setOf(authorizedAccount.bech32Address),
            additionalAddresses = setOf(authorizedAccount.bech32Address),
        ).also(::assertRevokeResponseAccepted)
    }

    @Test
    fun `processAccessRevoke removes any and disregards grant id when revoke expression does not include it`() {
        setUp()

        val firstGrantId = "the best grant ever"
        val secondGrantId = "the betterest grant ever"
        val thirdGrantId = "I don't even have a fake adjective for this one"

        doAccessGrant(grantId = firstGrantId).also { assertGrantResponseAccepted(it, expectedGrantId = firstGrantId) }
        doAccessGrant(grantId = secondGrantId).also { assertGrantResponseAccepted(it, expectedGrantId = secondGrantId) }
        doAccessGrant(grantId = thirdGrantId).also { assertGrantResponseAccepted(it, expectedGrantId = thirdGrantId) }
        doAccessGrant().also(::assertGrantResponseAccepted)

        doAccessRevoke().also { revokeResponse ->
            assertRevokeResponseAccepted(
                response = revokeResponse,
                expectedRevokedGrantsCount = 4,
                expectedRemainingGrantsCount = 0,
            )
        }
    }

    @Test
    fun `StreamEventHandlerService removes grant id specifically upon request`() {
        setUp()

        val firstGrantId = "first-grant"
        val secondGrantId = "second-grant"

        doAccessGrant(grantId = firstGrantId).also { assertGrantResponseAccepted(it, expectedGrantId = firstGrantId) }
        doAccessGrant(grantId = secondGrantId).also { assertGrantResponseAccepted(it, expectedGrantId = secondGrantId) }
        doAccessGrant().also(::assertGrantResponseAccepted)

        doAccessRevoke(grantId = "other grant id").also {
            assertRevokeResponseAccepted(
                response = it,
                expectedRevokedGrantsCount = 0,
                expectedRemainingGrantsCount = 3,
            )
        }

        assertScopePermissionExists(grantId = firstGrantId, messagePrefix = "First grant should still exist after targeting a nonexistent grant id")
        assertScopePermissionExists(grantId = secondGrantId, messagePrefix = "Second grant should still exist after targeting a nonexistent grant id")
        assertScopePermissionExists(grantId = null, messagePrefix = "Null id grant should still exist after targeting a nonexistent grant id")

        doAccessRevoke(grantId = firstGrantId).also {
            assertRevokeResponseAccepted(
                response = it,
                expectedRevokedGrantsCount = 1,
                expectedRemainingGrantsCount = 2,
            )
        }

        assertScopePermissionDoesNotExist(grantId = firstGrantId, messagePrefix = "First grant should be deleted after an access revoke requested it")
        assertScopePermissionExists(grantId = secondGrantId, messagePrefix = "Second grant should still exist after only deleting the first grant id's record")
        assertScopePermissionExists(grantId = null, messagePrefix = "Null id grant should still exist after only deleting the first grant id's record")

        doAccessRevoke(grantId = secondGrantId).also {
            assertRevokeResponseAccepted(
                response = it,
                expectedRevokedGrantsCount = 1,
                expectedRemainingGrantsCount = 1,
            )
        }

        assertScopePermissionDoesNotExist(grantId = firstGrantId, messagePrefix = "First grant should remain deleted after deleting the second grant")
        assertScopePermissionDoesNotExist(grantId = secondGrantId, messagePrefix = "Second grant should be deleted after an access revoke requested it")
        assertScopePermissionExists(grantId = null, messagePrefix = "Null id grant should still exist after only deleting the second grant id's record")

        doAccessRevoke(grantId = null).also {
            assertRevokeResponseAccepted(
                response = it,
                expectedRevokedGrantsCount = 1,
                expectedRemainingGrantsCount = 0,
            )
        }

        assertScopePermissionDoesNotExist(grantId = firstGrantId, messagePrefix = "First grant should remain deleted after removing grants with a null grant id")
        assertScopePermissionDoesNotExist(grantId = secondGrantId, messagePrefix = "Second grant should remain deleted after removing grants with a null grant id")
        assertScopePermissionDoesNotExist(grantId = null, messagePrefix = "Null id grant should be deleted after an access revoke requested all grants to be deleted")

        assertEquals(
            expected = emptyList(),
            actual = scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, defaultGrantee),
            message = "After all grants have been deleted, no granter addresses should be available to the grantee",
        )
    }

    private fun doAccessGrant(
        scopeAddr: String = scopeAddress,
        grantee: String = defaultGrantee,
        sources: Set<String> = setOf(scopeOwner.bech32Address),
        additionalAddresses: Set<String> = emptySet(),
        grantId: String? = null,
    ): GrantResponse = service.processAccessGrant(
        scopeAddress = scopeAddr,
        granteeAddress = grantee,
        grantSourceAddresses = sources,
        additionalAuthorizedAddresses = additionalAddresses,
        grantId = grantId,
    )

    private fun doAccessRevoke(
        scopeAddr: String = scopeAddress,
        grantee: String = defaultGrantee,
        sources: Set<String> = setOf(scopeOwner.bech32Address),
        additionalAddresses: Set<String> = emptySet(),
        grantId: String? = null,
    ): RevokeResponse = service.processAccessRevoke(
        scopeAddress = scopeAddr,
        granteeAddress = grantee,
        revokeSourceAddresses = sources,
        additionalAuthorizedAddresses = additionalAddresses,
        grantId = grantId,
    )

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
        grantId: String? = null,
        expectedRevokedGrantsCount: Int = 1,
        expectedRemainingGrantsCount: Int = 0
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
            expected = expectedRemainingGrantsCount.toLong(),
            actual = queryGrantCount(scopeAddr = grantScopeAddress, grantee = granteeAddress, grantId = grantId),
            message = "Expected the correct number of grants to remain",
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

    private fun assertScopePermissionExists(
        targetScopeAddress: String = scopeAddress,
        granterAddress: String = ownerGranter,
        granteeAddress: String = defaultGrantee,
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
        granterAddress: String = ownerGranter,
        granteeAddress: String = defaultGrantee,
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
}
