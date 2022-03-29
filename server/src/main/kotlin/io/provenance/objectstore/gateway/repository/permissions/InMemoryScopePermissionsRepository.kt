package io.provenance.objectstore.gateway.repository.permissions

data class PermissionsKey(val scopeId: String, val address: String)

class InMemoryScopePermissionsRepository: ScopePermissionsRepository {
    private val permissions = mutableSetOf<PermissionsKey>()

    override fun addAccessPermission(scopeAddress: String, address: String) {
        permissions.add(PermissionsKey(scopeAddress, address))
        println("permissions: $permissions") // todo: Remove
    }

    override fun hasAccessPermission(scopeAddress: String, address: String): Boolean = permissions.contains(PermissionsKey(scopeAddress, address))
}
