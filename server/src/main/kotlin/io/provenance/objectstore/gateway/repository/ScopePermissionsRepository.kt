package io.provenance.objectstore.gateway.repository

import io.provenance.objectstore.gateway.model.ScopePermission
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Service

@Service
class ScopePermissionsRepository {
    fun addAccessPermission(scopeAddress: String, address: String) {
        transaction { ScopePermission.new(scopeAddress, address) }
    }

    fun hasAccessPermission(scopeAddress: String, address: String): Boolean = transaction { ScopePermission.findByScopeIdAndAddress(scopeAddress, address) } != null
}
