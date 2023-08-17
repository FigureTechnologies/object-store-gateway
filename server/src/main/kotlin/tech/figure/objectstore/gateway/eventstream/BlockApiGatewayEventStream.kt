package tech.figure.objectstore.gateway.eventstream

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tech.figure.block.api.client.BlockAPIClient
import tech.figure.block.api.proto.BlockServiceOuterClass

class BlockApiGatewayEventStream(
    private val blockAPIClient: BlockAPIClient
) : GatewayEventStream {
    override suspend fun streamEvents(startHeight: Long): Flow<HeightAndEvents> = blockAPIClient.streamBlocks(
        start = startHeight,
        preference = BlockServiceOuterClass.PREFER.TX_EVENTS
    ).map {
        HeightAndEvents(
            it.blockResult.block.height,
            it.blockResult.block.transactionsList.flatMap { tx ->
                tx.eventsList.mapNotNull { event -> BlockApiGatewayEvent.fromEventOrNull(event) }
            }
        )
    }
}
