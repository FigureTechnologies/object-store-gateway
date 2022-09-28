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

/**
 * Test each database migration for a given set of properties, indicating the database to which connections will be made
 * for the tests.
 *
 * @param databaseProperties The configurations that will be leveraged to simulate the application's database
 * connections.
 * @param addDefaultHooks If true, default checks to verify record integrity will be added after various migrations.
 */
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
                // This ensures that after migration 3.1, which adds a new grant_id column to the scope_permissions
                // table, that the record inserted after migration 1.1 is not negatively affected.
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

    /**
     * Adds an action to be taken before a specific version of a migration runs.  Only one hook may be added per version.
     *
     * @param version The Flyway-formatted version of the migration file to target.  Example: To target a migration
     * named V5_3__My_Cool_Migration.sql, input: 5.3
     * @param name The name qualifier for this hook.  This value will be displayed in test logs.
     * @param action The code to run before the migration is applied.
     */
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

    /**
     * Adds an action to be taken after a specific version of a migration runs.  Only one hook may be added per version.
     *
     * @param version The Flyway-formatted version of the migration file to target.  Example: To target a migration
     * named V10_11__A_Migration_To_End_All_Migrations.sql, input: 10.11
     * @param name The name qualifier for this hook.  This value will be displayed in test logs.
     * @param action The code to run after the migration is applied.
     */
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

    /**
     * Adds an action to be taken after all migrations have been successfully applied.  Any number of "after all" hooks
     * may be added as long as their names are unique.
     *
     * @param name The name qualifier for this hook.  This value must be unique to "after all" hooks and will be
     * displayed in test logs.
     * @param priority The order in which this hook should be run in relation to other "after all" hooks.  If no value
     * is provided, this hook will be established to run after all other previously-added "after all" hooks before it.
     * @param action The code to run after all migrations are applied.
     */
    fun addAfterAllHook(
        name: String,
        priority: Int = afterAllHooks().maxOfOrNull { it.priority }?.let { it + 1 } ?: 1,
        action: () -> Unit,
    ): DbMigrationTester = this.apply {
        assertTrue(
            actual = afterAllHooks().none { it.name == name },
            message = "An after all migrations hook with name [$name] has already been registered",
        )
        migrationHooks += AfterAllMigrations(name = name, priority = priority, action = action)
    }

    /**
     * The main function of this class.  Establishes a connection to the database and runs all migrations, one by one,
     * ensuring that before and after hooks are honored throughout the process.  After all migrations and hooks are
     * run, the "after all" migration hooks are run in order of their given priorities.  Finally, the database
     * connected to is then disconnected and removed from the app, ensuring that normal SpringBootTest tests that
     * hook into an in-memory Sqlite database can run without conflict.
     */
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

    data class AfterAllMigrations(
        override val name: String,
        val priority: Int,
        override val action: () -> Unit,
    ) : DbMigrationHook
}
