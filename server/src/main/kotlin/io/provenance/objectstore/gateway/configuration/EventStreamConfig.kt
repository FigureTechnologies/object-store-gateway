package io.provenance.objectstore.gateway.configuration

import com.squareup.moshi.Moshi
import com.tinder.scarlet.Scarlet
import com.tinder.scarlet.messageadapter.moshi.MoshiMessageAdapter
import com.tinder.scarlet.websocket.okhttp.newWebSocketFactory
import com.tinder.streamadapter.coroutines.CoroutinesStreamAdapterFactory
import io.provenance.eventstream.adapter.json.decoder.MoshiDecoderEngine
import io.provenance.eventstream.config.BatchConfig
import io.provenance.eventstream.config.Config
import io.provenance.eventstream.config.EventStreamConfig
import io.provenance.eventstream.config.HeightConfig
import io.provenance.eventstream.config.RpcStreamConfig
import io.provenance.eventstream.config.StreamEventsFilterConfig
import io.provenance.eventstream.config.UploadConfig
import io.provenance.eventstream.config.WebsocketStreamConfig
import io.provenance.eventstream.stream.BlockStreamFactory
import io.provenance.eventstream.stream.DefaultBlockStreamFactory
import io.provenance.eventstream.stream.clients.TendermintBlockFetcher
import io.provenance.eventstream.stream.clients.TendermintServiceOpenApiClient
import okhttp3.OkHttpClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.TimeUnit

@Configuration
class EventStreamConfig {
    @Bean
    fun blockStreamFactory(esProps: EventStreamProperties): BlockStreamFactory = DefaultBlockStreamFactory(
        config = Config(
            verbose = false,
            eventStream = EventStreamConfig(
                websocket = WebsocketStreamConfig(uri = esProps.websocketUri.toString()),
                rpc = RpcStreamConfig(uri = esProps.rpcUri.toString()),
                batch = BatchConfig(size = 10, timeoutMillis = 10000L),
                filter = StreamEventsFilterConfig(),
                height = HeightConfig(from = esProps.epochHeight),
                ordered = false,
                skipEmptyBlocks = true,
            ),
            upload = UploadConfig.empty,
            node = esProps.websocketUri.toString(),
        ),
        decoderEngine = MoshiDecoderEngine(Moshi.Builder().build()),
        eventStreamBuilder = Scarlet.Builder().webSocketFactory(
            OkHttpClient.Builder()
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
                .newWebSocketFactory("${esProps.websocketUri.scheme}://${esProps.websocketUri.host}:${esProps.websocketUri.port}/websocket")
        )
            .addMessageAdapterFactory(MoshiMessageAdapter.Factory())
            .addStreamAdapterFactory(CoroutinesStreamAdapterFactory()),
        blockFetcher = TendermintBlockFetcher(TendermintServiceOpenApiClient(esProps.rpcUri.toString()))
    )
}
