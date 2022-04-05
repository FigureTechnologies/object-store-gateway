package io.provenance.objectstore.gateway.components

import io.provenance.eventstream.stream.BlockStreamFactory
import io.provenance.eventstream.stream.withFromHeight
import io.provenance.eventstream.stream.withOrdered
import io.provenance.objectstore.gateway.repository.BlockHeightRepository
import io.provenance.objectstore.gateway.service.StreamEventHandlerService
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.runBlocking
import mu.KLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

@Component
class EventStreamConsumer(
    private val blockStreamFactory: BlockStreamFactory,
    private val streamEventHandlerService: StreamEventHandlerService,
    private val blockHeightRepository: BlockHeightRepository,
) {
    private companion object: KLogging() {
        private var eventStreamRunning = AtomicBoolean(false)
        private val TX_EVENTS = setOf("wasm")
    }

    @Scheduled(fixedDelay = 10_000)
    fun listen() {
        if (eventStreamRunning.get()) {
            logger.debug("Event stream already running - exiting out")
            return
        }

        eventStreamRunning.set(true)
        logger.info("Starting up event stream")

        try {
            runBlocking {
                blockStreamFactory.createSource(
                    withFromHeight(blockHeightRepository.getLastProcessedBlockHeight().also {
                        logger.info("Streaming from height $it")
                    }),
                    withOrdered(true),
                )
                .streamBlocks()
                .onCompletion { optionalException ->
                    optionalException?.also {
                        logger.error("Block stream completed with an exception", it)
                    } ?: logger.warn("Block stream completed early")
                    eventStreamRunning.set(false)
                }
                .catch { e ->
                    logger.error("Failed on event stream processing", e)
                    eventStreamRunning.set(false)
                }
                .collect { block ->
                    if (block.height == null) {
                        logger.error("Received block with null height")
                        return@collect
                    }

                    val lastProcessedHeight = blockHeightRepository.getLastProcessedBlockHeight()

                    if (block.txEvents.any { TX_EVENTS.contains(it.eventType) }) { // these are the blocks you are looking for
                        block.txEvents.forEach {
                            streamEventHandlerService.handleEvent(it)
                        }
                    }

                    if (block.height!! < lastProcessedHeight) {
                        logger.warn("Received lower block height than last processed (${block.height} vs. $lastProcessedHeight)")
                    } else {
                        blockHeightRepository.setLastProcessedBlockHeight(block.height!!)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Event stream failed", e)
            eventStreamRunning.set(false)
        }
    }
}
