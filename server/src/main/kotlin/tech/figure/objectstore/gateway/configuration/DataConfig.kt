package tech.figure.objectstore.gateway.configuration

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mu.KLogging
import org.bouncycastle.asn1.x500.style.RFC4519Style.name
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.configuration.FluentConfiguration
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Schema
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.vendors.PostgreSQLDialect
import org.jetbrains.exposed.sql.vendors.SQLiteDialect
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.sql.Connection
import javax.sql.DataSource

@Configuration
class DataConfig {
    companion object : KLogging()

    @Primary
    @Bean
    fun dataSource(databaseProperties: DatabaseProperties): DataSource {
        return HikariConfig().apply {
            when (databaseProperties.type) {
                "postgresql" -> {
                    jdbcUrl =
                        "jdbc:${databaseProperties.type}://${databaseProperties.host}:${databaseProperties.port}/${databaseProperties.name}?prepareThreshold=0"
                    TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_READ_COMMITTED
                }
                "memory" -> {
                    jdbcUrl = "jdbc:sqlite:file:test?mode=memory&cache=shared"
                    TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
                }
                "sqlite" -> {
                    jdbcUrl = "jdbc:sqlite:${databaseProperties.host.trimEnd('/')}/${databaseProperties.name}.db"
                    TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
                }
                else -> throw IllegalArgumentException("Only the values [postgresql, memory, sqlite] are allowed for DB_TYPE")
            }
            logger.info("Connecting to {} on schema {} with user {}", jdbcUrl, databaseProperties.schema, databaseProperties.username)
            username = databaseProperties.username
            password = databaseProperties.password
            schema = databaseProperties.schema
            maximumPoolSize = databaseProperties.connectionPoolSize.toInt()
            minimumIdle = databaseProperties.connectionPoolSize.toInt().div(2).takeIf { it > 0 } ?: 1
            addDataSourceProperty("protocol.io.threads", "12")
        }.let { HikariDataSource(it) }
    }

    @Bean("databaseConnect")
    fun databaseConnect(dataSource: DataSource, databaseProperties: DatabaseProperties): Database = Database.connect(dataSource)
        .also {
            TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_READ_COMMITTED
            Database.registerDialect("pgsql") { PostgreSQLDialect() }
            Database.registerDialect("sqlite") { SQLiteDialect() }
            transaction {
                if (databaseProperties.type == "postgresql") {
                    try {
                        SchemaUtils.createSchema(Schema(databaseProperties.schema))
                    } catch (e: Exception) {
                        logger.warn("Exception creating schema [${databaseProperties.schema}]: ${e.message}")
                        logger.warn("attempting to continue as this may be just a permissions issue (the schema may already exist or you might need to create it manually)")
                    }
                }
            }
        }

    @Bean
    fun flyway(dataSource: DataSource, databaseProperties: DatabaseProperties): Flyway = Flyway(
        FluentConfiguration().dataSource(dataSource).baselineOnMigrate(databaseProperties.baselineOnMigrate)
            // Dynamically set the location for migration files based on the database type, allowing for multiple
            // dialects to specify their own migrations as necessary
            .locations("db/migration/common", "db/migration/${databaseProperties.type}")
    ).apply {
        if (databaseProperties.type != "postgresql") {
            baseline()
        }
    }

    @Bean
    fun flywayInitializer(flyway: Flyway): FlywayMigrationInitializer = FlywayMigrationInitializer(flyway)

    @Bean("MigrationsExecuted")
    fun flywayMigration(dataSource: DataSource, flyway: Flyway): Int {
        flyway.info().all().forEach { logger.info("Flyway migration: ${it.script}") }
        return flyway.migrate().migrationsExecuted
    }
}
