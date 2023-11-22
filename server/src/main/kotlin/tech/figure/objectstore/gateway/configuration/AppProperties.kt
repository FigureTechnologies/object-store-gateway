package tech.figure.objectstore.gateway.configuration

import org.intellij.lang.annotations.Pattern
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.ConstructorBinding
import org.springframework.validation.annotation.Validated
import java.net.URI
import java.util.UUID

@ConfigurationProperties(prefix = "batch")
@Validated
data class BatchProperties @ConstructorBinding constructor(
    val maxProvidedRecords: Int,
)

@ConfigurationProperties(prefix = "blockstream")
@Validated
data class BlockStreamProperties @ConstructorBinding constructor(
    val type: String,
    val uri: URI,
    val apiKey: String? = null,
    val epochHeight: Long,
    val enabled: Boolean,
    val blockHeightTrackingUuid: UUID,
    val threadCount: Int = 10,
    val restartDelaySeconds: Long = 10
) {
    enum class StreamType(val label: String) {
        Provenance("provenance"),
        BlockApi("blockapi"),
    }

    val streamType = StreamType.entries.find { it.label == type }
        ?: throw IllegalArgumentException("Unsupported block stream type [$type]")
}

@ConfigurationProperties(prefix = "objectstore")
@Validated
data class ObjectStoreProperties @ConstructorBinding constructor(
    val uri: URI,
    val privateKeys: List<String>,
    val masterKey: String,
    val decryptionWorkerThreads: Short = 10,
    val concurrencySize: Short = 10,
    val cacheRecordSizeBytes: Long = 10_000_000,
    val cacheJarSizeBytes: Long = 1_000_000
)

@Validated
@ConfigurationProperties(prefix = "provenance")
data class ProvenanceProperties @ConstructorBinding constructor(
    val mainNet: Boolean,
    val chainId: String,
    val channelUri: URI,
)

@Validated
@ConfigurationProperties(prefix = "db")
data class DatabaseProperties @ConstructorBinding constructor(
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
