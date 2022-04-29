package io.provenance.objectstore.gateway.components

import io.provenance.eventstream.decoder.moshiDecoderAdapter
import io.provenance.eventstream.net.okHttpNetAdapter
import io.provenance.eventstream.stream.BlockStreamFactory
import io.provenance.eventstream.stream.flows.blockDataFlow
import io.provenance.eventstream.stream.models.extensions.dateTime
import io.provenance.eventstream.stream.models.extensions.txData
import io.provenance.eventstream.stream.models.extensions.txEvents
import io.provenance.eventstream.stream.withFromHeight
import io.provenance.eventstream.stream.withOrdered
import io.provenance.objectstore.gateway.configuration.BeanQualifiers
import io.provenance.objectstore.gateway.configuration.EventStreamProperties
import io.provenance.objectstore.gateway.repository.BlockHeightRepository
import io.provenance.objectstore.gateway.service.StreamEventHandlerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@Component
class EventStreamConsumer(
    private val streamEventHandlerService: StreamEventHandlerService,
    private val blockHeightRepository: BlockHeightRepository,
    private val eventStreamProperties: EventStreamProperties,
    @Qualifier(BeanQualifiers.EVENT_STREAM_COROUTINE_SCOPE_QUALIFIER) private val eventStreamScope: CoroutineScope,
): ApplicationListener<ApplicationReadyEvent>, HealthIndicator {
    private companion object: KLogging() {
        private var eventStreamRunning = AtomicBoolean(false)
        private val TX_EVENTS = setOf("wasm")
    }

    private val decoderAdapter = moshiDecoderAdapter()

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
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
            .collect {  block ->
                val lastProcessedHeight = blockHeightRepository.getLastProcessedBlockHeight()

                block.blockResult
                    .txEvents(block.block.header?.dateTime()) { index -> block.block.txData(index) }
                    .filter { TX_EVENTS.contains(it.eventType) }  // these are the blocks you are looking for
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
