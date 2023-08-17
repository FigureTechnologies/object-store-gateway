package tech.figure.objectstore.gateway.configuration

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tech.figure.block.api.client.BlockAPIClient
import tech.figure.block.api.client.Protocol
import tech.figure.block.api.client.withApiKey
import tech.figure.block.api.client.withProtocol
import tech.figure.block.api.proto.BlockServiceOuterClass
import tech.figure.objectstore.gateway.eventstream.BlockApiGatewayEventStream
import tech.figure.objectstore.gateway.eventstream.GatewayEventStream
import tech.figure.objectstore.gateway.eventstream.ProvenanceGatewayEventStream

@Configuration
class BlockStreamConfig {

    @Bean
    fun gatewayEventStream(blockStreamProperties: BlockStreamProperties): GatewayEventStream = when (blockStreamProperties.streamType) {
        BlockStreamProperties.StreamType.Provenance -> ProvenanceGatewayEventStream(blockStreamProperties.uri.toString())
        BlockStreamProperties.StreamType.BlockApi -> BlockApiGatewayEventStream(
            BlockAPIClient(
                host = blockStreamProperties.uri.host,
                port = blockStreamProperties.uri.port,
                *listOfNotNull(
                    withProtocol(if (blockStreamProperties.uri.scheme.endsWith('s')) Protocol.TLS else Protocol.PLAINTEXT),
                    if (blockStreamProperties.apiKey?.takeIf { it.isNotBlank() } != null) withApiKey(blockStreamProperties.apiKey!!) else null
                ).toTypedArray(),
                defaultTxVerbosity = BlockServiceOuterClass.PREFER.TX_EVENTS,
            )
        )
    }
}
