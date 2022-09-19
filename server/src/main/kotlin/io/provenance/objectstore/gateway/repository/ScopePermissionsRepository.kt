package io.provenance.objectstore.gateway.repository

import io.provenance.objectstore.gateway.model.ScopePermission
import io.provenance.objectstore.gateway.model.ScopePermissionsTable
import io.provenance.objectstore.gateway.model.ScopePermissionsTable.granterAddress
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Service

@Service
class ScopePermissionsRepository {
    fun addAccessPermission(scopeAddress: String, granteeAddress: String, granterAddress: String) {
        transaction { ScopePermission.new(scopeAddress, granteeAddress, granterAddress) }
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
     */
    fun revokeAccessPermission(scopeAddress: String, granteeAddress: String): Int = transaction {
        ScopePermissionsTable.deleteWhere {
            ScopePermissionsTable.scopeAddress.eq(scopeAddress)
                .and { ScopePermissionsTable.granteeAddress.eq(granteeAddress) }
        }
    }
}
