package tech.figure.objectstore.gateway.model
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.select
import tech.figure.objectstore.gateway.model.ObjectPermissionsTable.isObjectWithMeta
import tech.figure.objectstore.gateway.model.ObjectPermissionsTable.storageKeyAddress
import tech.figure.objectstore.gateway.sql.offsetDatetime
import java.time.OffsetDateTime
import java.util.UUID

object ObjectPermissionsTable : UUIDTable("object_permissions", "uuid") {
    val objectHash = text("object_hash").index()
    val granterAddress = varchar("granter_address", 44)
    val granteeAddress = varchar("grantee_address", 44)
    val storageKeyAddress = varchar("storage_key_address", 44)
    val objectSizeBytes = long("object_size_bytes")
    val isObjectWithMeta = bool("is_object_with_meta")
    val created = offsetDatetime("created").clientDefault { OffsetDateTime.now() }

    init {
        uniqueIndex(objectHash, granteeAddress)
    }
}

open class ObjectPermissionClass : UUIDEntityClass<ObjectPermission>(ObjectPermissionsTable) {
    fun new(objectHash: String, granterAddress: String, granteeAddress: String, storageKeyAddress: String, objectSizeBytes: Long, isObjectWithMeta: Boolean) = findByObjectDetails(
        objectHash = objectHash,
        granterAddress = granterAddress,
        granteeAddress = granteeAddress,
        storageKeyAddress = storageKeyAddress,
        isObjectWithMeta = isObjectWithMeta
    ) ?: new() {
        this.objectHash = objectHash
        this.granterAddress = granterAddress
        this.granteeAddress = granteeAddress
        this.storageKeyAddress = storageKeyAddress
        this.objectSizeBytes = objectSizeBytes
        this.isObjectWithMeta = isObjectWithMeta
    }

    fun findByObjectDetails(objectHash: String, granterAddress: String, granteeAddress: String, storageKeyAddress: String, isObjectWithMeta: Boolean) = find {
        ObjectPermissionsTable.objectHash eq objectHash and
            (ObjectPermissionsTable.granterAddress eq granterAddress) and
            (ObjectPermissionsTable.granteeAddress eq granteeAddress) and
            (ObjectPermissionsTable.storageKeyAddress eq storageKeyAddress) and
            (ObjectPermissionsTable.isObjectWithMeta eq isObjectWithMeta)
    }.firstOrNull()

    fun findByObjectHashAndGranteeAddress(objectHash: String, granteeAddress: String) = find {
        ObjectPermissionsTable.objectHash eq objectHash and
            (ObjectPermissionsTable.granteeAddress eq granteeAddress)
    }.firstOrNull()

    fun findByObjectHashAndGranterAddress(objectHash: String, granterAddress: String): List<ObjectPermission> = findByObjectHashesAndGranterAddress(
        objectHashes = listOf(objectHash),
        granterAddress = granterAddress,
    )[objectHash] ?: emptyList()

    fun findByObjectHashesAndGranterAddress(objectHashes: Collection<String>, granterAddress: String): Map<String, List<ObjectPermission>> = find {
        ObjectPermissionsTable.objectHash inList objectHashes and
            (ObjectPermissionsTable.granterAddress eq granterAddress)
    }.groupBy { it.objectHash }

    fun findHashesByGranterAddress(granterAddress: String, excludedGrantees: Collection<String> = emptyList()): Set<String> = ObjectPermissionsTable
        .slice(ObjectPermissionsTable.objectHash)
        .select { ObjectPermissionsTable.granterAddress eq granterAddress }
        .map { it[ObjectPermissionsTable.objectHash] }
        .toSet()
        .let { allGranterHashes ->
            if (excludedGrantees.isEmpty()) {
                allGranterHashes
            } else {
                ObjectPermissionsTable
                    .slice(ObjectPermissionsTable.objectHash)
                    .select { ObjectPermissionsTable.granteeAddress inList excludedGrantees }
                    .andWhere { ObjectPermissionsTable.objectHash inList allGranterHashes }
                    .map { it[ObjectPermissionsTable.objectHash] }
                    .toSet()
                    .let { hashesToExclude -> allGranterHashes - hashesToExclude }
            }
        }

    fun deleteByObjectHashGranterAndGranteeAddresses(objectHash: String, granterAddress: String, granteeAddresses: List<String>) = ObjectPermissionsTable.deleteWhere {
        ObjectPermissionsTable.objectHash eq objectHash and
            (ObjectPermissionsTable.granterAddress eq granterAddress) and
            (ObjectPermissionsTable.granteeAddress inList granteeAddresses)
    }
}

class ObjectPermission(uuid: EntityID<UUID>) : UUIDEntity(uuid) {
    companion object : ObjectPermissionClass()

    var objectHash by ObjectPermissionsTable.objectHash
    var granterAddress by ObjectPermissionsTable.granterAddress
    var granteeAddress by ObjectPermissionsTable.granteeAddress
    var storageKeyAddress by ObjectPermissionsTable.storageKeyAddress
    var objectSizeBytes by ObjectPermissionsTable.objectSizeBytes
    var isObjectWithMeta by ObjectPermissionsTable.isObjectWithMeta
    val created by ObjectPermissionsTable.created
}
