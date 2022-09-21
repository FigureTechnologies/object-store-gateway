package tech.figure.objectstore.gateway.model

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.SizedIterable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import tech.figure.objectstore.gateway.sql.offsetDatetime
import java.time.OffsetDateTime

object ScopePermissionsTable : IntIdTable("scope_permissions", "id") {
    val scopeAddress = varchar("scope_address", 44).index()
    val granteeAddress = varchar("grantee_address", 44)
    val granterAddress = varchar("granter_address", 44)
    val created = offsetDatetime("created").clientDefault { OffsetDateTime.now() }
    val grantId = text("grant_id").nullable()

    init {
        uniqueIndex(scopeAddress, granteeAddress, granterAddress, grantId)
    }
}

open class ScopePermissionClass : IntEntityClass<ScopePermission>(ScopePermissionsTable) {
    fun new(
        scopeAddress: String,
        granteeAddress: String,
        granterAddress: String,
        grantId: String? = null,
    ): ScopePermission = findByScopeIdAndAddresses(
        scopeAddress = scopeAddress,
        granteeAddress = granteeAddress,
        granterAddress = granterAddress,
        grantId = grantId,
    ) ?: new {
        this.scopeAddress = scopeAddress
        this.granteeAddress = granteeAddress
        this.granterAddress = granterAddress
        this.grantId = grantId
    }

    private fun findByScopeIdAndAddresses(
        scopeAddress: String,
        granteeAddress: String,
        granterAddress: String,
        grantId: String?,
    ) = find {
        ScopePermissionsTable.scopeAddress.eq(scopeAddress)
            .and { ScopePermissionsTable.granteeAddress eq granteeAddress }
            .and { ScopePermissionsTable.granterAddress eq granterAddress }
            .and { ScopePermissionsTable.grantId eq grantId }
    }.firstOrNull()

    fun findAllByScopeIdAndGranteeAddress(scopeAddress: String, granteeAddress: String): SizedIterable<ScopePermission> = find {
        ScopePermissionsTable.scopeAddress eq scopeAddress and (ScopePermissionsTable.granteeAddress eq granteeAddress)
    }

    fun revokeAccessPermission(
        scopeAddress: String,
        granteeAddress: String,
        grantId: String? = null,
    ): Int = ScopePermissionsTable.deleteWhere {
        ScopePermissionsTable.scopeAddress.eq(scopeAddress)
            .and { ScopePermissionsTable.granteeAddress eq granteeAddress }
            .let { query ->
                if (grantId != null) {
                    query.and { ScopePermissionsTable.grantId eq grantId }
                } else {
                    query
                }
            }
    }
}

class ScopePermission(id: EntityID<Int>) : IntEntity(id) {
    companion object : ScopePermissionClass()

    var scopeAddress: String by ScopePermissionsTable.scopeAddress
    var granteeAddress: String by ScopePermissionsTable.granteeAddress
    var granterAddress: String by ScopePermissionsTable.granterAddress
    val created: OffsetDateTime by ScopePermissionsTable.created
    var grantId: String? by ScopePermissionsTable.grantId
}
