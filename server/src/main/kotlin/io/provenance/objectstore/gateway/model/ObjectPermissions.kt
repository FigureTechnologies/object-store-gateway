package io.provenance.objectstore.gateway.model
import io.provenance.objectstore.gateway.sql.offsetDatetime
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import java.time.OffsetDateTime

object ObjectPermissionsTable : IntIdTable("object_permissions", "id") {
    val objectHash = text("object_hash").index()
    val granteeAddress = varchar("grantee_address", 44)
    val created = offsetDatetime("created").clientDefault { OffsetDateTime.now() }

    init {
        uniqueIndex(objectHash, granteeAddress)
    }
}

open class ObjectPermissionClass : IntEntityClass<ObjectPermission>(ObjectPermissionsTable) {
    fun new(objectHash: String, granteeAddress: String) = findByObjectHashAndAddress(objectHash, granteeAddress) ?: new() {
        this.objectHash = objectHash
        this.granteeAddress = granteeAddress
    }

    fun findByObjectHashAndAddress(objectHash: String, granteeAddress: String) = find {
        ObjectPermissionsTable.objectHash eq objectHash and
            (ObjectPermissionsTable.granteeAddress eq granteeAddress)
    }.firstOrNull()
}

class ObjectPermission(id: EntityID<Int>) : IntEntity(id) {
    companion object : ObjectPermissionClass()

    var objectHash by ObjectPermissionsTable.objectHash
    var granteeAddress by ObjectPermissionsTable.granteeAddress
    val created by ObjectPermissionsTable.created
}
