package tech.figure.objectstore.gateway.model

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.and
import tech.figure.objectstore.gateway.sql.VarcharEntity
import tech.figure.objectstore.gateway.sql.VarcharEntityClass
import tech.figure.objectstore.gateway.sql.VarcharTable
import tech.figure.objectstore.gateway.sql.offsetDatetime
import java.time.OffsetDateTime

object DataStorageAccountsTable : VarcharTable(
    name = "data_storage_accounts",
    columnName = "account_address",
    columnLength = 44,
) {
    val enabled = bool("enabled").default(true)
    val created = offsetDatetime("created").clientDefault { OffsetDateTime.now() }
}

open class DataStorageAccountsClass : VarcharEntityClass<DataStorageAccount>(DataStorageAccountsTable) {
    fun new(accountAddress: String, enabled: Boolean = true): DataStorageAccount =
        findByAddressOrNull(accountAddress, enabledOnly = false) ?: new(accountAddress) { this.enabled = enabled }

    fun setEnabled(accountAddress: String, enabled: Boolean): DataStorageAccount? =
        findByAddressOrNull(accountAddress = accountAddress, enabledOnly = false)?.apply { this.enabled = enabled }

    fun findByAddressOrNull(
        accountAddress: String,
        enabledOnly: Boolean = true,
    ): DataStorageAccount? = find {
        DataStorageAccountsTable.id.eq(accountAddress).let { query ->
            if (enabledOnly) {
                query.and { DataStorageAccountsTable.enabled.eq(true) }
            } else {
                query
            }
        }
    }.firstOrNull()
}

class DataStorageAccount(accountAddress: EntityID<String>) : VarcharEntity(accountAddress) {
    companion object : DataStorageAccountsClass()

    val accountAddress: String by lazy { id.value }
    var enabled: Boolean by DataStorageAccountsTable.enabled
    val created: OffsetDateTime by DataStorageAccountsTable.created
}
