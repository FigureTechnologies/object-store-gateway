package tech.figure.objectstore.gateway.db

import mu.KLogging
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.transactions.TransactionManager
import tech.figure.objectstore.gateway.configuration.DataConfig
import tech.figure.objectstore.gateway.configuration.DatabaseProperties
import tech.figure.objectstore.gateway.db.DbMigrationHook.AfterAllMigrations
import tech.figure.objectstore.gateway.db.DbMigrationHook.AfterMigration
import tech.figure.objectstore.gateway.db.DbMigrationHook.BeforeMigration
import tech.figure.objectstore.gateway.helpers.runRawSqlQuery
import tech.figure.objectstore.gateway.helpers.runRawSqlUpdate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DbMigrationTester(
    private val databaseProperties: DatabaseProperties,
    addDefaultHooks: Boolean = true,
) {
    private companion object : KLogging()
    private val migrationHooks: MutableSet<DbMigrationHook> = mutableSetOf()

    init {
        if (addDefaultHooks) {
            addAfterHook(version = "1.1", name = "Insert dummy scope_permissions record") {
                // This ensures that transformations on this table don't cause exceptions after later migrations
                // Runs raw sql because the latest exposed entity classes in the application don't support the old
                // table format
                runRawSqlUpdate(
                    """
                    INSERT INTO scope_permissions (id, scope_address, grantee_address, granter_address, created)
                    VALUES (1, 'scope-addr', 'grantee-addr', 'granter-addr', '2021-01-01T12:00Z');
                    """.trimIndent()
                )
            }
            addAfterHook(version = "3.1", name = "Verify dummy record") {
                runRawSqlQuery("select scope_address, grantee_address, granter_address, grant_id from scope_permissions where id = 1") { resultSet ->
                    resultSet.next()
                    assertEquals(
                        expected = "scope-addr",
                        actual = resultSet.getString("scope_address"),
                        message = "The scope_address column value of the dummy record should not be affected by the grant id migration",
                    )
                    assertEquals(
                        expected = "grantee-addr",
                        actual = resultSet.getString("grantee_address"),
                        message = "The grantee_address column value of the dummy record should not be affected by the grant id migration",
                    )
                    assertEquals(
                        expected = "granter-addr",
                        actual = resultSet.getString("granter_address"),
                        message = "The granter_address column value of the dummy record should not be affected by the grant id migration",
                    )
                    assertNull(
                        actual = resultSet.getString("grant_id"),
                        message = "The default grant id value should be null on the dummy record",
                    )
                }
            }
        }
    }

    fun addBeforeHook(
        version: String,
        name: String = "$version-beforeHook",
        action: () -> Unit,
    ): DbMigrationTester = this.apply {
        assertTrue(
            actual = beforeHooks().none { it.version == version },
            message = "A before hook for migration version [$version] has already been registered",
        )
        migrationHooks += BeforeMigration(
            name = name,
            version = version,
            action = action,
        )
    }

    fun addAfterHook(
        version: String,
        name: String = "$version-afterHook",
        action: () -> Unit,
    ): DbMigrationTester = this.apply {
        assertTrue(
            actual = afterHooks().none { it.version == version },
            message = "An after hook for migration version [$version] has already been registered",
        )
        migrationHooks += AfterMigration(
            name = name,
            version = version,
            action = action,
        )
    }

    fun addAfterAllHook(
        name: String,
        action: () -> Unit,
    ): DbMigrationTester = this.apply {
        assertTrue(
            actual = afterAllHooks().none { it.name == name },
            message = "An after all migrations hook with name [$name] has already been registered",
        )
        migrationHooks += AfterAllMigrations(name = name, action = action)
    }

    fun testMigrations() {
        val dataConfig = DataConfig()
        val dataSource = dataConfig.dataSource(databaseProperties)
        // Init exposed to ensure transactions run in this process are able to do things without blowing up
        val database = dataConfig.databaseConnect(dataSource, databaseProperties)
        val flywayConfig = dataConfig.flywayConfig(dataSource, databaseProperties)
        Flyway(flywayConfig).info().all().forEach { migration ->
            val migrationPrefix = "[MIGRATION ${migration.version}: ${migration.description}]"
            logger.info("$migrationPrefix START PROCESS")
            beforeHookOrNull(migration.version.version)?.also { beforeHook ->
                logger.info("$migrationPrefix Running BEFORE hook [${beforeHook.name}]")
                beforeHook.action()
            }
            logger.info("$migrationPrefix RUNNING MIGRATION")
            Flyway(flywayConfig.target(migration.version)).migrate()
            logger.info("$migrationPrefix MIGRATION COMPLETED")
            afterHookOrNull(migration.version.version)?.also { afterHook ->
                logger.info("$migrationPrefix Running AFTER hook [${afterHook.name}]")
                afterHook.action()
            }
        }
        afterAllHooks().forEach { afterAllHook ->
            val hookPrefix = "[AFTER ALL HOOK ${afterAllHook.name}]"
            logger.info("$hookPrefix STARTING HOOK")
            afterAllHook.action()
            logger.info("$hookPrefix COMPLETED HOOK")
        }
        // Unhook the database used for the test to ensure no conflicts with other tests occur
        TransactionManager.closeAndUnregister(database)
    }

    private fun beforeHooks(): List<BeforeMigration> = migrationHooks.filterIsInstance<BeforeMigration>()
    private fun afterHooks(): List<AfterMigration> = migrationHooks.filterIsInstance<AfterMigration>()
    private fun afterAllHooks(): List<AfterAllMigrations> = migrationHooks.filterIsInstance<AfterAllMigrations>()

    private fun beforeHookOrNull(version: String): DbMigrationHook? = beforeHooks()
        .singleOrNull { it.version == version }

    private fun afterHookOrNull(version: String): DbMigrationHook? = afterHooks()
        .singleOrNull { it.version == version }
}

sealed interface DbMigrationHook {
    val name: String
    val action: () -> Unit

    data class BeforeMigration(
        override val name: String,
        val version: String,
        override val action: () -> Unit,
    ) : DbMigrationHook

    data class AfterMigration(
        override val name: String,
        val version: String,
        override val action: () -> Unit,
    ) : DbMigrationHook

    data class AfterAllMigrations(override val name: String, override val action: () -> Unit) : DbMigrationHook
}
