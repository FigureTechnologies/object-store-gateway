package tech.figure.objectstore.gateway.repository

import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Repository
import tech.figure.objectstore.gateway.model.DataStorageAccount

@Repository
class DataStorageAccountsRepository {
    fun addDataStorageAccount(accountAddress: String): DataStorageAccount = transaction {
        DataStorageAccount.new(accountAddress)
    }

    fun setStorageAccountEnabled(accountAddress: String, enabled: Boolean): DataStorageAccount = transaction {
        DataStorageAccount.setEnabled(accountAddress = accountAddress, enabled = enabled)
    } ?: error("No data storage account existed for address [$accountAddress]")

    fun isAddressEnabled(accountAddress: String): Boolean = transaction {
        DataStorageAccount.findByAddressOrNull(accountAddress, enabledOnly = true)
    } != null
}
