package tech.figure.objectstore.gateway.repository

import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Repository
import tech.figure.objectstore.gateway.configuration.BlockapiProperties
import tech.figure.objectstore.gateway.model.BlockHeight

@Repository
class BlockHeightRepository(private val blockapiProperties: BlockapiProperties) {
    private val blockHeightUuid = blockapiProperties.blockHeightTrackingUuid

    fun getLastProcessedBlockHeight(): Long = transaction {
        BlockHeight.findById(blockHeightUuid)?.height
            ?: BlockHeight.setBlockHeight(blockHeightUuid, blockapiProperties.epochHeight).height
    }

    fun setLastProcessedBlockHeight(height: Long) {
        transaction {
            BlockHeight.setBlockHeight(blockHeightUuid, height)
        }
    }
}
