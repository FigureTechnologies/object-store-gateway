package tech.figure.objectstore.gateway.db

import mu.KLogging
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import tech.figure.objectstore.gateway.configuration.DatabaseProperties
import tech.figure.objectstore.gateway.helpers.runRawSqlUpdate
import kotlin.test.Test

class MemoryMigrationsTest {
    private companion object : KLogging()

    @Test
    fun `test memory migrations`() {
        val properties = DatabaseProperties(
            type = "memory",
            name = "object-store-gateway",
            username = "user",
            password = "pass",
            host = "migrationTest",
            port = "",
            schema = "object-store-gateway",
            connectionPoolSize = "1",
            baselineOnMigrate = false,
        )
        DbMigrationTester(properties).removeDatabaseTables().testMigrations()
    }

    /**
     * The in-memory configuration uses a shared cache, which means the tables will remain in existence when other
     * tests start running.  This will cause Flyway issues when other in-memory config tests start up.  To remedy this,
     * just remove all data that the tests caused to be created by clearing all tables.
     */
    private fun DbMigrationTester.removeDatabaseTables(): DbMigrationTester = this.addAfterAllHook(name = "remove in-memory tables") {
        val tableNames = transaction { TransactionManager.current().db.dialect.allTablesNames() }.distinct()
        logger.info("Successfully fetched [${tableNames.size}] table names to delete")
        tableNames.forEach { tableName ->
            logger.info("DROPPING TABLE $tableName")
            runRawSqlUpdate("drop table $tableName")
        }
    }
}
