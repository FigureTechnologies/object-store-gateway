package tech.figure.objectstore.gateway.model
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.and
import tech.figure.objectstore.gateway.sql.offsetDatetime
import java.time.OffsetDateTime
import java.util.UUID

object ObjectPermissionsTable : UUIDTable("object_permissions", "uuid") {
    val objectHash = text("object_hash").index()
    val granteeAddress = varchar("grantee_address", 44)
    val objectSizeBytes = long("object_size_bytes")
    val created = offsetDatetime("created").clientDefault { OffsetDateTime.now() }

    init {
        uniqueIndex(objectHash, granteeAddress)
    }
}

open class ObjectPermissionClass : UUIDEntityClass<ObjectPermission>(ObjectPermissionsTable) {
    fun new(objectHash: String, granteeAddress: String, objectSizeBytes: Long) = findByObjectHashAndAddress(objectHash, granteeAddress) ?: new() {
        this.objectHash = objectHash
        this.granteeAddress = granteeAddress
        this.objectSizeBytes = objectSizeBytes
    }

    fun findByObjectHashAndAddress(objectHash: String, granteeAddress: String) = find {
        ObjectPermissionsTable.objectHash eq objectHash and
            (ObjectPermissionsTable.granteeAddress eq granteeAddress)
    }.firstOrNull()
}

class ObjectPermission(uuid: EntityID<UUID>) : UUIDEntity(uuid) {
    companion object : ObjectPermissionClass()

    var objectHash by ObjectPermissionsTable.objectHash
    var granteeAddress by ObjectPermissionsTable.granteeAddress
    var objectSizeBytes by ObjectPermissionsTable.objectSizeBytes
    val created by ObjectPermissionsTable.created
}
