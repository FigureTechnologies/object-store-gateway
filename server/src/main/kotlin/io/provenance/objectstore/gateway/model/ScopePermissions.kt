package io.provenance.objectstore.gateway.model

import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select

object ScopePermissionsTable : IdTable<String>("scope_permissions") {
    val scopeAddress = varchar("scope_address", 44).index()
    val address = varchar("address", 44)

    override val id: Column<EntityID<String>> = scopeAddress.entityId()

    init {
        uniqueIndex(scopeAddress, address)
    }
}

open class ScopePermissionClass : EntityClass<String, ScopePermission>(ScopePermissionsTable) {
    fun new(scopeAddress: String, address: String) = findByScopeIdAndAddress(scopeAddress, address) ?: new(scopeAddress) {
        this.address = address
    }

    fun findByScopeIdAndAddress(scopeAddress: String, address: String) = find {
        ScopePermissionsTable.scopeAddress eq scopeAddress and(ScopePermissionsTable.address eq address)
    }.firstOrNull()
}

class ScopePermission(id: EntityID<String>) : Entity<String>(id) {
    companion object: ScopePermissionClass()

    var scopeAddress by ScopePermissionsTable.id
    var address by ScopePermissionsTable.address
}
