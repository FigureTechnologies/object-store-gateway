package tech.figure.objectstore.gateway.repository

import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Repository
import tech.figure.objectstore.gateway.model.ScopePermission

@Repository
class ScopePermissionsRepository {
    fun addAccessPermission(
        scopeAddress: String,
        granteeAddress: String,
        granterAddress: String,
        grantId: String? = null,
    ) {
        transaction { ScopePermission.new(scopeAddress, granteeAddress, granterAddress, grantId) }
    }

    fun getAccessGranterAddresses(scopeAddress: String, granteeAddress: String): List<String> = transaction {
        ScopePermission.findAllByScopeIdAndGranteeAddress(scopeAddress, granteeAddress).map { it.granterAddress }
    }

    /**
     * Revokes permissions to the given scope address for the target grantee.  Returns the number of permissions that
     * were revoked.
     *
     * @param scopeAddress The bech32 address of the scope for which to revoke access.
     * @param granteeAddress The address that has been granted access to the scope.  All records that link this address
     * to the target scope will be removed.
     * @param grantId An optional parameter that denotes that only records with this specific grant ID should be deleted.
     * If omitted, all records with this scopeAddress and granteeAddress combination will be deleted.
     */
    fun revokeAccessPermission(scopeAddress: String, granteeAddress: String, grantId: String? = null): Int = transaction {
        ScopePermission.revokeAccessPermission(
            scopeAddress = scopeAddress,
            granteeAddress = granteeAddress,
            grantId = grantId,
        )
    }
}
