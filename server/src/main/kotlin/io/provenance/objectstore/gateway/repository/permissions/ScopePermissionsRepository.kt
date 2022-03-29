package io.provenance.objectstore.gateway.repository.permissions

interface ScopePermissionsRepository {
    fun addAccessPermission(scopeAddress: String, address: String)
    fun hasAccessPermission(scopeAddress: String, address: String): Boolean
}
