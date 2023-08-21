package tech.figure.objectstore.gateway.eventstream

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tech.figure.eventstream.decoder.moshiDecoderAdapter
import tech.figure.eventstream.net.okHttpNetAdapter
import tech.figure.eventstream.stream.flows.blockDataFlow

class ProvenanceGatewayEventStream(
    private val uri: String
) : GatewayEventStream {

    private val decoderAdapter = moshiDecoderAdapter()

    override suspend fun streamEvents(startHeight: Long): Flow<HeightAndEvents> {
        val netAdapter = okHttpNetAdapter(uri)
        return blockDataFlow(netAdapter, decoderAdapter, from = startHeight)
            .map {
                HeightAndEvents(it.height, it.txEvents().mapNotNull { event -> ProvenanceGatewayEvent.fromEventOrNull(event) })
            }
    }
}
