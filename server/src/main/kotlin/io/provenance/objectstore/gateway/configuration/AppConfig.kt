package io.provenance.objectstore.gateway.configuration

import io.provenance.client.grpc.GasEstimationMethod
import io.provenance.client.grpc.PbClient
import io.provenance.scope.encryption.model.DirectKeyRef
import io.provenance.scope.encryption.model.KeyRef
import io.provenance.scope.encryption.util.getAddress
import io.provenance.scope.encryption.util.toJavaPrivateKey
import io.provenance.scope.encryption.util.toKeyPair
import io.provenance.scope.objectstore.client.CachedOsClient
import io.provenance.scope.objectstore.client.OsClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AppConfig {
    @Bean
    fun objectStoreClient(objectStoreProperties: ObjectStoreProperties, provenanceProperties: ProvenanceProperties): CachedOsClient {
        val osClient = OsClient(objectStoreProperties.uri, 30000)

        return CachedOsClient(osClient, objectStoreProperties.decryptionWorkerThreads, objectStoreProperties.concurrencySize, objectStoreProperties.cacheRecordSizeBytes)
    }

    @Bean
    fun encryptionKeys(provenanceProperties: ProvenanceProperties, objectStoreProperties: ObjectStoreProperties): Map<String, KeyRef> = objectStoreProperties.privateKeys.map {
        it.toJavaPrivateKey().toKeyPair().let { keyPair ->
            keyPair.public.getAddress(provenanceProperties.mainNet) to DirectKeyRef(keyPair)
        }
    }.toMap()

    @Bean
    fun pbClient(provenanceProperties: ProvenanceProperties): PbClient = PbClient(
        chainId = provenanceProperties.chainId,
        channelUri = provenanceProperties.channelUri,
        gasEstimationMethod = GasEstimationMethod.MSG_FEE_CALCULATION,
    )

    @Bean
    fun accountAddresses(encryptionKeys: Map<String, KeyRef>): Set<String> = encryptionKeys.keys
}
