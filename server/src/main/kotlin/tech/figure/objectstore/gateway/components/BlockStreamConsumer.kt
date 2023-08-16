package tech.figure.objectstore.gateway.components

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.KLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.stereotype.Component
import tech.figure.block.api.client.BlockAPIClient
import tech.figure.block.api.proto.BlockServiceOuterClass
import tech.figure.objectstore.gateway.configuration.BeanQualifiers
import tech.figure.objectstore.gateway.configuration.BlockapiProperties
import tech.figure.objectstore.gateway.repository.BlockHeightRepository
import tech.figure.objectstore.gateway.service.TxEventHandlerService
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@Component
class BlockStreamConsumer(
    private val txEventHandlerService: TxEventHandlerService,
    private val blockHeightRepository: BlockHeightRepository,
    private val blockapiProperties: BlockapiProperties,
    private val blockAPIClient: BlockAPIClient,
    @Qualifier(BeanQualifiers.BLOCK_STREAM_COROUTINE_SCOPE_QUALIFIER) private val blockStreamScope: CoroutineScope,
) : ApplicationListener<ApplicationReadyEvent>, HealthIndicator {
    private companion object : KLogging() {
        private var blockStreamRunning = AtomicBoolean(false)
        private val TX_EVENTS = setOf("wasm")
    }

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        if (!blockapiProperties.enabled) {
            logger.warn("Event stream has been manually disabled! Use a value of `true` for EVENT_STREAM_ENABLED to re-enable it")
            return
        }
        tryStartBlockStream {
            blockStreamLoop()
        }
    }

    override fun health(): Health = if (blockStreamRunning.get()) {
        Health.up().build()
    } else {
        Health.down().build()
    }

    private suspend fun blockStreamLoop() {
        val lastBlockProcessed = blockHeightRepository.getLastProcessedBlockHeight()
        blockAPIClient.streamBlocks(
            start = lastBlockProcessed,
            preference = BlockServiceOuterClass.PREFER.TX_EVENTS
        ).collect { block ->
            val lastProcessedHeight = blockHeightRepository.getLastProcessedBlockHeight()

            block.blockResult.block.transactionsList.flatMap { it.eventsList }
                .filter { TX_EVENTS.contains(it.eventType) } // these are the blocks you are looking for
                .forEach {
                    logger.info { "got event $it" }
                    try {
                        txEventHandlerService.handleEvent(it)
                    } catch (e: Exception) {
                        // If exceptions are simply thrown without any additional logging, the errors appear to be
                        // event stream related, which would not be the case if they occur in this block
                        logger.error("Failed to process event with hash ${it.txHash} at height ${it.height}", e)
                        throw e
                    }
                }

            block.blockResult.block.height.let { blockHeight ->
                if (blockHeight < lastProcessedHeight) {
                    logger.warn("Received lower block height than last processed ($blockHeight vs. $lastProcessedHeight)")
                } else {
                    blockHeightRepository.setLastProcessedBlockHeight(blockHeight)
                }
            }
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun tryStartBlockStream(
        eventStreamFn: suspend CoroutineScope.() -> Unit
    ) {
        logger.info("BLOCKSTREAM INIT")
        blockStreamScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    logger.info("BLOCKSTREAM START")
                    eventStreamFn()
                    logger.info("BLOCKSTREAM END/SUCCESS")
                    blockStreamRunning.set(true)
                } catch (e: Exception) {
                    logger.error("BLOCKSTREAM END/FAILURE {}", e.message)
                    blockStreamRunning.set(false)
                    logger.info("Waiting ${blockapiProperties.restartDelaySeconds} seconds before reconnecting to block stream")
                    delay(blockapiProperties.restartDelaySeconds.seconds)
                }
            }
        }
    }
}
