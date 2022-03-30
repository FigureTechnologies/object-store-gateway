package io.provenance.objectstore.gateway.repository

import io.provenance.objectstore.gateway.configuration.EventStreamProperties
import io.provenance.objectstore.gateway.model.BlockHeight
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Service

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
