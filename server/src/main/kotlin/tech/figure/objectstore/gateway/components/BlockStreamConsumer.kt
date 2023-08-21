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
import tech.figure.objectstore.gateway.configuration.BeanQualifiers
import tech.figure.objectstore.gateway.configuration.BlockStreamProperties
import tech.figure.objectstore.gateway.eventstream.GatewayEventStream
import tech.figure.objectstore.gateway.repository.BlockHeightRepository
import tech.figure.objectstore.gateway.service.StreamEventHandlerService
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@Component
class BlockStreamConsumer(
    private val streamEventHandlerService: StreamEventHandlerService,
    private val blockHeightRepository: BlockHeightRepository,
    private val blockStreamProperties: BlockStreamProperties,
    private val gatewayEventStream: GatewayEventStream,
    @Qualifier(BeanQualifiers.BLOCK_STREAM_COROUTINE_SCOPE_QUALIFIER) private val blockStreamScope: CoroutineScope,
) : ApplicationListener<ApplicationReadyEvent>, HealthIndicator {
    private companion object : KLogging() {
        private var blockStreamRunning = AtomicBoolean(false)
        private val TX_EVENTS = setOf("wasm")
    }

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        if (!blockStreamProperties.enabled) {
            logger.warn("Block stream has been manually disabled! Use a value of `true` for BLOCKSTREAM_ENABLED to re-enable it")
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
        gatewayEventStream.streamEvents(
            startHeight = lastBlockProcessed,
        ).collect { block ->
            val lastProcessedHeight = blockHeightRepository.getLastProcessedBlockHeight()

            block.events
                .filter { TX_EVENTS.contains(it.eventType) } // these are the blocks you are looking for
                .forEach {
                    try {
                        streamEventHandlerService.handleEvent(it)
                    } catch (e: Exception) {
                        // If exceptions are simply thrown without any additional logging, the errors appear to be
                        // event stream related, which would not be the case if they occur in this block
                        logger.error("Failed to process event with hash ${it.txHash} at height ${block.height}", e)
                        throw e
                    }
                }

            block.height.let { blockHeight ->
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
                    logger.info("Waiting ${blockStreamProperties.restartDelaySeconds} seconds before reconnecting to block stream")
                    delay(blockStreamProperties.restartDelaySeconds.seconds)
                }
            }
        }
    }
}
