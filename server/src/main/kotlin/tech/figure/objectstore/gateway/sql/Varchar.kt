package tech.figure.objectstore.gateway.sql

import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column

open class VarcharTable(name: String, columnName: String, columnLength: Int) : IdTable<String>(name) {
    override val id: Column<EntityID<String>> = varchar(name = columnName, length = columnLength).entityId()
    override val primaryKey by lazy { super.primaryKey ?: PrimaryKey(id) }
}

abstract class VarcharEntity(id: EntityID<String>) : Entity<String>(id)

abstract class VarcharEntityClass<out E : Entity<String>>(table: IdTable<String>, entityType: Class<E>? = null) :
    EntityClass<String, E>(table, entityType)
