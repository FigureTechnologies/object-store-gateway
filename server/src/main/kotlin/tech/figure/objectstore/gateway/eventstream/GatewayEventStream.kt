package tech.figure.objectstore.gateway.eventstream

import kotlinx.coroutines.flow.Flow

interface GatewayEventStream {
    suspend fun streamEvents(startHeight: Long): Flow<HeightAndEvents>
}

data class HeightAndEvents(val height: Long, val events: List<GatewayEvent>)
