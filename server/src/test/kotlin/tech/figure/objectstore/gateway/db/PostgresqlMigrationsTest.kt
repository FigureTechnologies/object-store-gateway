package tech.figure.objectstore.gateway.db

import mu.KLogging
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.Network
import org.testcontainers.containers.PostgreSQLContainer
import tech.figure.objectstore.gateway.configuration.DatabaseProperties
import java.util.UUID
import kotlin.test.fail

class PostgresqlMigrationsTest {
    private companion object : KLogging()

    lateinit var network: Network

    @BeforeEach
    fun setupTestContainers() {
        network = Network.builder().createNetworkCmdModifier {
            it.withName("postgresql-tests-network-${UUID.randomUUID()}")
        }.build()
    }

    @Test
    fun `test postgresql 11 migrations`() {
        val properties = getPostgresqlContainer("11-alpine").toDatabaseProperties()
        DbMigrationTester(properties).testMigrations()
    }

    @Test
    fun `test postgresql 13 migrations`() {
        val properties = getPostgresqlContainer("13-alpine").toDatabaseProperties()
        DbMigrationTester(properties).testMigrations()
    }

    private fun getPostgresqlContainer(version: String): PostgreSQLContainer<*> = PostgreSQLContainer("postgres:$version")
        .withNetwork(network)
        .withNetworkMode(network.id)
        .withNetworkAliases("postgres")
        .withDatabaseName("object-store-gateway")
        .withUsername("user")
        .withPassword("pass")
        .withCommand("postgres", "-c", "integrationtest.safe=1", "-c", "fsync=off")
        .also {
            try {
                logger.info("Starting PostgreSQL container with version [$version]")
                it.start()
                logger.info("Successfully started PostgreSQL container with version [$version]")
            } catch (e: Exception) {
                fail("Failed to start PostgreSQL container with version [$version]", e)
            }
        }

    private fun PostgreSQLContainer<*>.toDatabaseProperties(): DatabaseProperties = DatabaseProperties(
        type = "postgresql",
        name = this.databaseName,
        username = this.username,
        password = this.password,
        host = this.host,
        port = this.getMappedPort(5432).toString(),
        schema = "object-store-gateway",
        connectionPoolSize = "1",
        baselineOnMigrate = false,
        repairFlywayChecksums = false,
    )
}
