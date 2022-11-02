package tech.figure.objectstore.gateway.db

import tech.figure.objectstore.gateway.configuration.DatabaseProperties
import java.nio.file.Files
import kotlin.io.path.pathString
import kotlin.test.Test

class SqliteMigrationsTest {
    @Test
    fun `test sqlite migrations`() {
        val dbDirectory = Files.createTempDirectory("os-gateway-sqlite")
        val properties = DatabaseProperties(
            type = "sqlite",
            name = "object-store-gateway",
            username = "user",
            password = "pass",
            host = dbDirectory.pathString,
            port = "",
            schema = "object-store-gateway",
            connectionPoolSize = "1",
            baselineOnMigrate = false,
            repairFlywayChecksums = false,
        )
        DbMigrationTester(properties).testMigrations()
    }
}
