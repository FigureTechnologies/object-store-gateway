package tech.figure.objectstore.gateway.service

import io.provenance.metadata.v1.ScopeResponse
import mu.KLogging
import org.springframework.stereotype.Service
import tech.figure.objectstore.gateway.repository.ScopePermissionsRepository

@Service
class ScopePermissionsService(
    private val accountAddresses: Set<String>,
    private val scopeFetchService: ScopeFetchService,
    private val scopePermissionsRepository: ScopePermissionsRepository,
) {
    private companion object : KLogging()

    fun processAccessGrant(
        scopeAddress: String,
        granteeAddress: String,
        grantSourceAddresses: List<String>,
        additionalAuthorizedAddresses: List<String> = emptyList(),
        grantId: String? = null,
        providedScope: ScopeResponse? = null,
        sourceDetails: String? = null,
    ) {
        val logPrefix = "[GATEWAY GRANT (${sourceDetails ?: ""})]:"
        val scopeResponse = providedScope ?: scopeFetchService.fetchScope(
            scopeAddress = scopeAddress,
            includeSessions = true,
        )
        val granterAddress = findRegisteredScopeOwnerAddress(scopeResponse = scopeResponse) ?: run {
            logger.info("$logPrefix Skipping grant.  No granter is registered for scope [$scopeAddress]")
            return
        }
        // TODO: Add authz reverse lookup to attempt to find additional authorized addresses.  The scope's value owner
        // may have granted other addresses the required privileges that should allow this to proceed
        val authorizedAddresses = additionalAuthorizedAddresses + scopeResponse.scope.scope.valueOwnerAddress
        if (authorizedAddresses.none { it in grantSourceAddresses }) {
            logger.warn("$logPrefix Skipping grant. None of the authorized addresses $authorizedAddresses for this grant were in the addresses that requested it $grantSourceAddresses")
            return
        }
        logger.info("$logPrefix Adding account [$granteeAddress] to access list for scope [$scopeAddress] with granter [$granterAddress]")
        scopePermissionsRepository.addAccessPermission(
            scopeAddress = scopeAddress,
            granteeAddress = granteeAddress,
            granterAddress = granterAddress,
            grantId = grantId,
        )
    }

    fun processAccessRevoke(
        scopeAddress: String,
        granteeAddress: String,
        revokeSourceAddresses: List<String>,
        additionalAuthorizedAddresses: List<String> = emptyList(),
        grantId: String? = null,
        providedScope: ScopeResponse? = null,
        sourceDetails: String? = null,
    ) {
        val logPrefix = "[GATEWAY REVOKE (${sourceDetails ?: ""})]:"
        val scopeResponse = providedScope ?: scopeFetchService.fetchScope(scopeAddress = scopeAddress)
        // TODO: Add authz reverse lookup to attempt to find additional authorized addresses.  The scope's value owner
        // may have granted other addresses the required privileges that should allow this to proceed
        val authorizedAddresses = additionalAuthorizedAddresses + scopeResponse.scope.scope.valueOwnerAddress
        if (authorizedAddresses.none { it in revokeSourceAddresses }) {
            logger.info("$logPrefix Skipping revoke.None of the authorized addresses $authorizedAddresses for this revoke were in the addresses that requested it $revokeSourceAddresses")
            return
        }
        logger.info("$logPrefix Revoking grants from grantee [$granteeAddress] for scope [$scopeAddress]${if (grantId != null) " with grant id [$grantId]" else ""}")
        scopePermissionsRepository.revokeAccessPermission(
            scopeAddress = scopeAddress,
            granteeAddress = granteeAddress,
            grantId = grantId,
        )
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
}
