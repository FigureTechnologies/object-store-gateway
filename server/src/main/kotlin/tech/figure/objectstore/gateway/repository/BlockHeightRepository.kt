package tech.figure.objectstore.gateway.repository

import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Service
import tech.figure.objectstore.gateway.configuration.EventStreamProperties
import tech.figure.objectstore.gateway.model.BlockHeight

@Service
class BlockHeightRepository(private val eventStreamProperties: EventStreamProperties) {
    private val blockHeightUuid = eventStreamProperties.blockHeightTrackingUuid

    fun getLastProcessedBlockHeight(): Long = transaction {
        BlockHeight.findById(blockHeightUuid)?.height
            ?: BlockHeight.setBlockHeight(blockHeightUuid, eventStreamProperties.epochHeight).height
    }

    fun setLastProcessedBlockHeight(height: Long) {
        transaction {
            BlockHeight.setBlockHeight(blockHeightUuid, height)
        }
    }
}
