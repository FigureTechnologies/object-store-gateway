package io.provenance.objectstore.gateway.model

import io.provenance.objectstore.gateway.sql.offsetDatetime
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.and
import java.time.OffsetDateTime

object ScopePermissionsTable : IntIdTable("scope_permissions", "id") {
    val scopeAddress = varchar("scope_address", 44).index()
    val granteeAddress = varchar("grantee_address", 44)
    val granterAddress = varchar("granter_address", 44)
    val created = offsetDatetime("created").clientDefault { OffsetDateTime.now() }

    init {
        uniqueIndex(scopeAddress, granteeAddress, granterAddress)
    }
}

open class ScopePermissionClass : IntEntityClass<ScopePermission>(ScopePermissionsTable) {
    fun new(scopeAddress: String, granteeAddress: String, granterAddress: String) = findByScopeIdAndAddresses(scopeAddress, granteeAddress, granterAddress) ?: new() {
        this.scopeAddress = scopeAddress
        this.granteeAddress = granteeAddress
        this.granterAddress = granterAddress
    }

    private fun findByScopeIdAndAddresses(scopeAddress: String, granteeAddress: String, granterAddress: String) = find {
        ScopePermissionsTable.scopeAddress eq scopeAddress and
            (ScopePermissionsTable.granteeAddress eq granteeAddress) and
            (ScopePermissionsTable.granterAddress eq granterAddress)
    }.firstOrNull()

    fun findAllByScopeIdAndGranteeAddress(scopeAddress: String, granteeAddress: String) = find {
        ScopePermissionsTable.scopeAddress eq scopeAddress and (ScopePermissionsTable.granteeAddress eq granteeAddress)
    }
}

class ScopePermission(id: EntityID<Int>) : IntEntity(id) {
    companion object : ScopePermissionClass()

    var scopeAddress by ScopePermissionsTable.scopeAddress
    var granteeAddress by ScopePermissionsTable.granteeAddress
    var granterAddress by ScopePermissionsTable.granterAddress
    val created by ScopePermissionsTable.created
}
