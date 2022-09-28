package tech.figure.objectstore.gateway.service

import io.provenance.metadata.v1.ScopeResponse
import mu.KLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import tech.figure.objectstore.gateway.configuration.BeanQualifiers
import tech.figure.objectstore.gateway.repository.ScopePermissionsRepository

@Service
class ScopePermissionsService(
    @Qualifier(BeanQualifiers.OBJECTSTORE_PRIVATE_KEYS) private val accountAddresses: Set<String>,
    private val addressVerificationService: AddressVerificationService,
    private val scopeFetchService: ScopeFetchService,
    private val scopePermissionsRepository: ScopePermissionsRepository,
) {
    private companion object : KLogging()

    /**
     * Attempts to grant permissions to the records on a Provenance Blockchain scope to the specified grantee.  This
     * function does NOT verify the origin of the provided source addresses or authorized addresses.  Code that invokes
     * this function should responsibly determine the origin of all provided addresses before doing so.
     *
     * @param scopeAddress The bech32 Provenance Blockchain Scope address for which to grant access.
     * @param granteeAddress The bech32 Provenance Blockchain Account address to which access will be granted.
     * @param grantSourceAddresses The bech32 Provenance Blockchain Account address or addresses that caused this grant
     * to be requested.
     * @param additionalAuthorizedAddresses Any bech32 Provenance Blockchain Account addresses that should be allowed to
     * grant access to the scope's records.  The default authorized address for making grants is just the value owner
     * of the scope referred to by scopeAddress.  IMPORTANT: Any values passed into this parameter will be assumed to be
     * valid, authorized addresses to grant scope access. Calling into this function and providing unrelated addresses
     * can lead to bad actors gaining access to sensitive data.  BE CAREFUL OR FACE THE CONSEQUENCES!
     * @param grantId A free-form grant identifier that will be appended to the record created in the scope_permissions
     * table for targeted revokes.  If omitted, the record created will have a null grant id.
     * @param sourceDetails Additional metadata that will be appended to the prefix of logging that is produced by this
     * function.  Completely optional.
     */
    fun processAccessGrant(
        scopeAddress: String,
        granteeAddress: String,
        grantSourceAddresses: Set<String>,
        additionalAuthorizedAddresses: Set<String> = emptySet(),
        grantId: String? = null,
        sourceDetails: String? = null,
    ): GrantResponse {
        return try {
            val logPrefix = "[GATEWAY GRANT ${if (sourceDetails != null) "($sourceDetails)" else ""}]:"
            // Verify that the scope address and all provided addresses are valid bech32 before attempting to process
            // then through the remaining portions of the function
            verifyAddressFailures(
                scopeAddress = scopeAddress,
                accountAddresses = listOf(
                    granteeAddress to "Grantee",
                    *grantSourceAddresses.map { it to "Source Address" }.toTypedArray(),
                    *additionalAuthorizedAddresses.map { it to "Additional Authorized Address" }.toTypedArray(),
                ),
            )?.also { failureMessage -> return GrantResponse.Rejected("$logPrefix $failureMessage") }
            val scopeResponse = scopeFetchService.fetchScope(
                scopeAddress = scopeAddress,
                includeSessions = true,
            )
            val granterAddress = findRegisteredScopeOwnerAddress(scopeResponse = scopeResponse)
                ?: return GrantResponse.Rejected("$logPrefix Skipping grant.  No granter is registered for scope [$scopeAddress]")
            // TODO: Add authz reverse lookup to attempt to find additional authorized addresses.  The scope's value owner
            // may have granted other addresses the required privileges that should allow this to proceed
            val authorizedAddresses = additionalAuthorizedAddresses + scopeResponse.scope.scope.valueOwnerAddress
            if (authorizedAddresses.none { it in grantSourceAddresses }) {
                return GrantResponse.Rejected("$logPrefix Skipping grant. None of the authorized addresses $authorizedAddresses for this grant were in the addresses that requested it $grantSourceAddresses")
            }
            logger.info("$logPrefix Adding account [$granteeAddress] to access list for scope [$scopeAddress] with granter [$granterAddress]")
            scopePermissionsRepository.addAccessPermission(
                scopeAddress = scopeAddress,
                granteeAddress = granteeAddress,
                granterAddress = granterAddress,
                grantId = grantId,
            )
            GrantResponse.Accepted(granterAddress = granterAddress)
        } catch (e: Exception) {
            GrantResponse.Error(e)
        }
    }

    /**
     * Attempts to revoke permissions that have been granted to the target Provenance Blockchain Scope for the specified
     * grantee.  This function does NOT verify the origin of the provided source addresses or authorized addresses.
     * Code that invokes this function should responsibly determine the origin of all provided addresses before doing
     * so.
     *
     * @param scopeAddress The bech32 Provenance Blockchain Scope address for which to revoke access.
     * @param granteeAddress The bech32 Provenance Blockchain Account address that currently has access to the scope.
     * @param revokeSourceAddresses The bech32 Provenance Blockchain Account address or addresses that caused this
     * revoke to be requested.
     * @param additionalAuthorizedAddresses Any bech32 Provenance Blockchain Account addresses that should be allowed to
     * revoke access to this scope's records.  The default authorized address for revoking grants is just the value
     * owner of the scope referred to by scopeAddress.  IMPORTANT: Any values passed into this parameter will be assumed
     * to be valid, authorized addresses to revoke scope access.  Calling into this function and providing unrelated
     * addresses can lead to bad actors disrupting data access unnecessarily.  BE CAREFUL OR FACE OBLIVION!
     * @param grantId A free-form grant identifier that will be used to query for existing scope_permissions records.
     * If this value is omitted, all grants for the given scope and grantee combination will be revoked, versus targeting
     * a singular unique record with the given id.
     * @parm sourcDetails Additional metadata that will be appended to the prefix of logging that is produced by this
     * function.  Completely optional.
     */
    fun processAccessRevoke(
        scopeAddress: String,
        granteeAddress: String,
        revokeSourceAddresses: Set<String>,
        additionalAuthorizedAddresses: Set<String> = emptySet(),
        grantId: String? = null,
        sourceDetails: String? = null,
    ): RevokeResponse = try {
        val logPrefix = "[GATEWAY REVOKE ${if (sourceDetails != null) "($sourceDetails)" else ""}]:"
        // Verify that the scope address and all provided addresses are valid bech32 before attempting to process
        // then through the remaining portions of the function
        verifyAddressFailures(
            scopeAddress = scopeAddress,
            accountAddresses = listOf(
                granteeAddress to "Grantee",
                *revokeSourceAddresses.map { it to "Source Address" }.toTypedArray(),
                *additionalAuthorizedAddresses.map { it to "Additional Authorized Address" }.toTypedArray(),
            ),
        )?.also { failureMessage -> return RevokeResponse.Rejected("$logPrefix $failureMessage") }
        val scopeResponse = scopeFetchService.fetchScope(scopeAddress = scopeAddress)
        // TODO: Add authz reverse lookup to attempt to find additional authorized addresses.  The scope's value owner
        // may have granted other addresses the required privileges that should allow this to proceed
        val authorizedAddresses = additionalAuthorizedAddresses + scopeResponse.scope.scope.valueOwnerAddress
        if (authorizedAddresses.none { it in revokeSourceAddresses }) {
            RevokeResponse.Rejected("$logPrefix Skipping revoke.  None of the authorized addresses $authorizedAddresses for this revoke were in the addresses that requested it $revokeSourceAddresses")
        } else {
            logger.info("$logPrefix Revoking grants from grantee [$granteeAddress] for scope [$scopeAddress]${if (grantId != null) " with grant id [$grantId]" else ""}")
            scopePermissionsRepository.revokeAccessPermission(
                scopeAddress = scopeAddress,
                granteeAddress = granteeAddress,
                grantId = grantId,
            ).let(RevokeResponse::Accepted)
        }
    } catch (e: Exception) {
        RevokeResponse.Error(e)
    }

    /**
     * Determines if the target scope contains a registered object store deserialization key.
     *
     * @param scopeResponse The response from the Provenance Blockchain that denotes all the values within a scope.
     */
    private fun findRegisteredScopeOwnerAddress(scopeResponse: ScopeResponse): String? {
        scopeResponse.scope.scope.ownersList.firstOrNull { it.address.isWatchedAddress() }?.also {
            return it.address
        }
        scopeResponse.scope.scope.dataAccessList.firstOrNull { it.isWatchedAddress() }?.also {
            return it
        }
        scopeResponse.sessionsList.flatMap { it.session.partiesList }.firstOrNull { it.address.isWatchedAddress() }
            ?.also {
                return it.address
            }
        return null
    }

    /**
     * Extension function to determine if the value is contained in the registered object store deserialization addresses.
     */
    private fun String?.isWatchedAddress(): Boolean = this in accountAddresses

    /**
     * Verifies that all input addresses are valid bech32.  If any failures are detected, all failure messages are
     * concatenated via CSV (default joinToString separator) and emitted.  If no failures are detected, the response
     * will be null.
     */
    private fun verifyAddressFailures(
        scopeAddress: String,
        accountAddresses: List<Pair<String, String>>,
    ): String? = listOfNotNull(
        formatVerificationFailureOrNull(
            verification = addressVerificationService.verifyScopeAddress(scopeAddress),
            verificationType = "Scope",
        ),
        *accountAddresses.mapNotNull { (accountAddress, verificationType) ->
            formatVerificationFailureOrNull(
                verification = addressVerificationService.verifyAccountAddress(accountAddress),
                verificationType = verificationType,
            )
        }.toTypedArray()
    ).takeIf { it.isNotEmpty() }?.joinToString { it }

    /**
     * Creates a human-readable error message indicating the nature of a bech32 verification failure.  If no failure
     * is detected, null is returned.
     */
    private fun formatVerificationFailureOrNull(
        verification: Bech32Verification,
        verificationType: String,
    ): String? = if (verification is Bech32Verification.Failure) {
        "$verificationType Verification Failed for Address [${verification.address}]: ${verification.message}"
    } else {
        null
    }
}

/**
 * A response value for ScopePermissionService.processAccessGrant, indicating the function's result.
 */
sealed interface GrantResponse {
    /**
     * Indicates that the requested grant was processed and a database entry in the scope_permissions table was created
     * that can be used by the grantee to fetch scope record data.
     *
     * @param granterAddress The bech32 Provenance Blockchain Account address of the account that has permitted access
     * to the requested scope records.
     */
    data class Accepted(val granterAddress: String) : GrantResponse

    /**
     * Indicates that the requested grant was rejected for some reason.  It can be expected that no scope_permissions
     * table record was inserted.
     *
     * @param message A message indicating the reason for the rejection.
     */
    data class Rejected(val message: String) : GrantResponse

    /**
     * Indicates that an exception occurred when the grant was processed.  It can be expected that no scope_permissions
     * table record was inserted.
     *
     * @param cause The exception that caused the function to terminate unexpectedly.
     */
    data class Error(val cause: Throwable) : GrantResponse
}

/**
 * A response value for ScopePermissionsService.processAccessRevoke, indicating the function's result.
 */
sealed interface RevokeResponse {
    /**
     * Indicates that the requested revoke was processed and that one or more access grants were removed from the
     * scope_permissions table.
     *
     * @param revokedGrantsCount The amount of grants that the processed request successfully removed from the table.
     * This value can be zero if the request does not target any existing grants.
     */
    data class Accepted(val revokedGrantsCount: Int) : RevokeResponse

    /**
     * Indicates that the requested revoke was rejected for some reason.  It can be expected that no scope_permissions
     * table records were removed.
     *
     * @param message A message indicating the reason for the rejection.
     */
    data class Rejected(val message: String) : RevokeResponse

    /**
     * Indicates that an exception occurred when the revoke was processed.  It can be expected that no scope_permissions
     * table records were removed.
     *
     * @param cause The exception that caused the function to terminate unexpectedly.
     */
    data class Error(val cause: Throwable) : RevokeResponse
}
