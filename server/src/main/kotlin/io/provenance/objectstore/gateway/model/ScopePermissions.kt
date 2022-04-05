package io.provenance.objectstore.gateway.model

import io.provenance.objectstore.gateway.model.ScopePermissionsTable.granterAddress
import io.provenance.objectstore.gateway.sql.offsetDatetime
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.and
import java.time.OffsetDateTime

object ScopePermissionsTable : IdTable<String>("scope_permissions") {
    val scopeAddress = varchar("scope_address", 44).index()
    val granteeAddress = varchar("grantee_address", 44)
    val granterAddress = varchar("granter_address", 44)
    val created = offsetDatetime("created").clientDefault { OffsetDateTime.now() }

    override val id: Column<EntityID<String>> = scopeAddress.entityId()

    init {
        uniqueIndex(scopeAddress, granteeAddress, granterAddress)
    }
}

open class ScopePermissionClass : EntityClass<String, ScopePermission>(ScopePermissionsTable) {
    fun new(scopeAddress: String, granteeAddress: String, granterAddress: String) = findByScopeIdAndAddresses(scopeAddress, granteeAddress, granterAddress) ?: new(scopeAddress) {
        this.granteeAddress = granteeAddress
        this.granterAddress = granterAddress
    }

    fun findByScopeIdAndAddresses(scopeAddress: String, granteeAddress: String, granterAddress: String) = find {
        ScopePermissionsTable.scopeAddress eq scopeAddress and
            (ScopePermissionsTable.granteeAddress eq granteeAddress) and
            (ScopePermissionsTable.granterAddress eq granterAddress)
    }.firstOrNull()

    fun findFirstByScopeIdAndGranteeAddress(scopeAddress: String, granteeAddress: String) = find {
        ScopePermissionsTable.scopeAddress eq scopeAddress and (ScopePermissionsTable.granteeAddress eq granteeAddress)
    }.firstOrNull()
}

class ScopePermission(id: EntityID<String>) : Entity<String>(id) {
    companion object: ScopePermissionClass()

    var scopeAddress by ScopePermissionsTable.id
    var granteeAddress by ScopePermissionsTable.granteeAddress
    var granterAddress by ScopePermissionsTable.granterAddress
    val created by ScopePermissionsTable.created
}
