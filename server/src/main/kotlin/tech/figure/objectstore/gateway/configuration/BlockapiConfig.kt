package tech.figure.objectstore.gateway.configuration

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tech.figure.block.api.client.BlockAPIClient
import tech.figure.block.api.client.Protocol
import tech.figure.block.api.client.withProtocol
import tech.figure.block.api.proto.BlockServiceOuterClass

@Configuration
class BlockapiConfig {
    @Bean
    fun blockapiClient(blockapiProperties: BlockapiProperties): BlockAPIClient = BlockAPIClient(
        host = blockapiProperties.uri.host,
        port = blockapiProperties.uri.port,
        withProtocol(if (blockapiProperties.uri.scheme.endsWith('s')) Protocol.TLS else Protocol.PLAINTEXT),
        defaultTxVerbosity = BlockServiceOuterClass.PREFER.TX_EVENTS,
    )
}
