package io.provenance.objectstore.gateway.configuration

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding
import org.springframework.validation.annotation.Validated
import java.net.URI

@ConstructorBinding
@ConfigurationProperties(prefix = "event.stream")
@Validated
data class EventStreamProperties(
    val websocketUri: URI,
    val rpcUri: URI,
    val epochHeight: Long,
    val enabled: Boolean,
)

@ConstructorBinding
@ConfigurationProperties(prefix = "objectstore")
@Validated
data class ObjectStoreProperties(
    val uri: URI,
    val privateKey: String,
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
@ConfigurationProperties(prefix = "data")
data class DataProperties(
    val type: String,
)

@Validated
@ConstructorBinding
@ConfigurationProperties(prefix = "contract")
data class ContractProperties(
    val address: String,
)
