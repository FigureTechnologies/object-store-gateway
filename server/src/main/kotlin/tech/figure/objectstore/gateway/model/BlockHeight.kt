package tech.figure.objectstore.gateway.model

import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import tech.figure.objectstore.gateway.sql.offsetDatetime
import java.time.OffsetDateTime
import java.util.UUID

object BlockHeightTable : UUIDTable("block_height", "uuid") {
    val height = long("height")
    val timestamp = offsetDatetime("timestamp")
}

open class BlockHeightClass : UUIDEntityClass<BlockHeight>(BlockHeightTable) {
    fun setBlockHeight(uuid: UUID, height: Long) = findById(uuid)?.apply {
        this.height = height
        this.timestamp = OffsetDateTime.now()
    } ?: new(uuid) {
        this.height = height
        this.timestamp = OffsetDateTime.now()
    }
}

class BlockHeight(uuid: EntityID<UUID>) : UUIDEntity(uuid) {
    companion object : BlockHeightClass()

    var uuid by BlockHeightTable.id
    var height by BlockHeightTable.height
    var timestamp by BlockHeightTable.timestamp
}
