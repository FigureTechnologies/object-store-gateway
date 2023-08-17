package tech.figure.objectstore.gateway.repository

import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Repository
import tech.figure.objectstore.gateway.configuration.BlockStreamProperties
import tech.figure.objectstore.gateway.model.BlockHeight

@Repository
class BlockHeightRepository(private val blockStreamProperties: BlockStreamProperties) {
    private val blockHeightUuid = blockStreamProperties.blockHeightTrackingUuid

    fun getLastProcessedBlockHeight(): Long = transaction {
        BlockHeight.findById(blockHeightUuid)?.height
            ?: BlockHeight.setBlockHeight(blockHeightUuid, blockStreamProperties.epochHeight).height
    }

    fun setLastProcessedBlockHeight(height: Long) {
        transaction {
            BlockHeight.setBlockHeight(blockHeightUuid, height)
        }
    }
}
