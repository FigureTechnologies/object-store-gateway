package io.provenance.objectstore.gateway.configuration

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.provenance.objectstore.gateway.model.BlockHeightTable
import io.provenance.objectstore.gateway.model.ScopePermissionsTable
import mu.KLogging
import org.bouncycastle.asn1.x500.style.RFC4519Style.name
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.vendors.PostgreSQLDialect
import org.jetbrains.exposed.sql.vendors.SQLiteDialect
import org.postgresql.Driver
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
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
            }
            logger.info("Connecting to {}", jdbcUrl)
            username = databaseProperties.username
            password = databaseProperties.password
            schema = databaseProperties.schema
            maximumPoolSize = databaseProperties.connectionPoolSize.toInt()
            minimumIdle = databaseProperties.connectionPoolSize.toInt().div(2).takeIf { it > 0 } ?: 1
            addDataSourceProperty("protocol.io.threads", "12")
        }.let { HikariDataSource(it) }
    }
}

@Component
class DataMigration(dataSource: DataSource) {
    init {
        Database.connect(dataSource)
        Database.registerDialect("pgsql") { PostgreSQLDialect() }
        Database.registerDialect("sqlite") { SQLiteDialect() }
        transaction {
            SchemaUtils.create(
                ScopePermissionsTable,
                BlockHeightTable,
            )
        }
    }
}
