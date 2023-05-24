package tech.figure.objectstore.gateway.configuration

import org.intellij.lang.annotations.Pattern
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding
import org.springframework.validation.annotation.Validated
import java.net.URI
import java.util.UUID

@ConstructorBinding
@ConfigurationProperties(prefix = "batch")
@Validated
data class BatchProperties(
    val maxProvidedRecords: Int,
)

@ConstructorBinding
@ConfigurationProperties(prefix = "event.stream")
@Validated
data class EventStreamProperties(
    val websocketUri: URI,
    val epochHeight: Long,
    val enabled: Boolean,
    val blockHeightTrackingUuid: UUID,
    val threadCount: Int = 10,
    val restartDelaySeconds: Long = 10
)

@ConstructorBinding
@ConfigurationProperties(prefix = "objectstore")
@Validated
data class ObjectStoreProperties(
    val uri: URI,
    val privateKeys: List<String>,
    val masterKey: String,
    val decryptionWorkerThreads: Short = 10,
    val concurrencySize: Short = 10,
    val cacheRecordSizeBytes: Long = 10_000_000
)

@Validated
@ConstructorBinding
@ConfigurationProperties(prefix = "provenance")
data class ProvenanceProperties(
    val mainNet: Boolean,
    val chainId: String,
    val channelUri: URI,
)

@Validated
@ConstructorBinding
@ConfigurationProperties(prefix = "db")
data class DatabaseProperties(
    val type: String,
    val name: String,
    val username: String,
    val password: String,
    val host: String,
    val port: String,
    val schema: String,
    @Pattern(value = "\\d{1,2}") val connectionPoolSize: String,
    val baselineOnMigrate: Boolean = false,
    val repairFlywayChecksums: Boolean = false,
)
