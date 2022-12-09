package tech.figure.objectstore.gateway.components

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import mu.KLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.stereotype.Component
import tech.figure.eventstream.decoder.moshiDecoderAdapter
import tech.figure.eventstream.net.okHttpNetAdapter
import tech.figure.eventstream.stream.flows.blockDataFlow
import tech.figure.eventstream.stream.models.dateTime
import tech.figure.eventstream.stream.models.txData
import tech.figure.eventstream.stream.models.txEvents
import tech.figure.objectstore.gateway.configuration.BeanQualifiers
import tech.figure.objectstore.gateway.configuration.EventStreamProperties
import tech.figure.objectstore.gateway.repository.BlockHeightRepository
import tech.figure.objectstore.gateway.service.StreamEventHandlerService
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@Component
class EventStreamConsumer(
    private val streamEventHandlerService: StreamEventHandlerService,
    private val blockHeightRepository: BlockHeightRepository,
    private val eventStreamProperties: EventStreamProperties,
    @Qualifier(BeanQualifiers.EVENT_STREAM_COROUTINE_SCOPE_QUALIFIER) private val eventStreamScope: CoroutineScope,
) : ApplicationListener<ApplicationReadyEvent>, HealthIndicator {
    private companion object : KLogging() {
        private var eventStreamRunning = AtomicBoolean(false)
        private val TX_EVENTS = setOf("wasm")
    }

    private val decoderAdapter = moshiDecoderAdapter()

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        if (!eventStreamProperties.enabled) {
            logger.warn("Event stream has been manually disabled! Use a value of `true` for EVENT_STREAM_ENABLED to re-enable it")
            return
        }
        tryStartEventStream {
            eventStreamLoop()
        }
    }

    override fun health(): Health = if (eventStreamRunning.get()) {
        Health.up().build()
    } else {
        Health.down().build()
    }

    private suspend fun eventStreamLoop() {
        val netAdapter = okHttpNetAdapter(eventStreamProperties.websocketUri.toString())
        val lastBlockProcessed = blockHeightRepository.getLastProcessedBlockHeight()
        blockDataFlow(netAdapter, decoderAdapter, from = lastBlockProcessed)
            .collect { block ->
                val lastProcessedHeight = blockHeightRepository.getLastProcessedBlockHeight()

                block.blockResult
                    .txEvents(block.block.header?.dateTime()) { index -> block.block.txData(index) }
                    .filter { TX_EVENTS.contains(it.eventType) } // these are the blocks you are looking for
                    .forEach {
                        streamEventHandlerService.handleEvent(it)
                    }

                if (block.height < lastProcessedHeight) {
                    logger.warn("Received lower block height than last processed (${block.height} vs. $lastProcessedHeight)")
                } else {
                    blockHeightRepository.setLastProcessedBlockHeight(block.height)
                }
            }
        try {
            netAdapter.shutdown()
        } catch (e: Exception) {
            logger.error("Error shutting down netAdatper", e)
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun tryStartEventStream(
        eventStreamFn: suspend CoroutineScope.() -> Unit
    ) {
        logger.info("EVENTSTREAM INIT")
        eventStreamScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    logger.info("EVENTSTREAM START")
                    eventStreamFn()
                    logger.info("EVENTSTREAM END/SUCCESS")
                    eventStreamRunning.set(true)
                } catch (e: Exception) {
                    logger.error("EVENTSTREAM END/FAILURE {}", e.message)
                    eventStreamRunning.set(false)
                    logger.info("Waiting ${eventStreamProperties.restartDelaySeconds} seconds before reconnecting to event stream")
                    delay(eventStreamProperties.restartDelaySeconds.seconds)
                }
            }
        }
    }
}
