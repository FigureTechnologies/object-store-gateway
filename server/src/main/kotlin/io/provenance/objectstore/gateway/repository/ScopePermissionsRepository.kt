package io.provenance.objectstore.gateway.repository

import io.provenance.objectstore.gateway.model.ScopePermission
import io.provenance.objectstore.gateway.model.ScopePermissionsTable.granterAddress
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
}
