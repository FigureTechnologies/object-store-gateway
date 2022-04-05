package io.provenance.objectstore.gateway.repository

import io.provenance.objectstore.gateway.model.ScopePermission
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Service

@Service
class ScopePermissionsRepository {
    fun addAccessPermission(scopeAddress: String, granteeAddress: String, granterAddress: String) {
        transaction { ScopePermission.new(scopeAddress, granteeAddress, granterAddress) }
    }

    fun getAccessGranterAddress(scopeAddress: String, granteeAddress: String, granterAddress: String?): String? = transaction {
        if (granterAddress != null) {
            ScopePermission.findByScopeIdAndAddresses(scopeAddress, granteeAddress, granterAddress)
        } else {
            ScopePermission.findFirstByScopeIdAndGranteeAddress(scopeAddress, granteeAddress)
        }
    }?.granterAddress
}
