package io.provenance.objectstore.gateway.configuration

import io.provenance.objectstore.gateway.util.CoroutineUtil
import kotlinx.coroutines.CoroutineScope
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class CoroutineConfig {
    @Bean(BeanQualifiers.EVENT_STREAM_COROUTINE_SCOPE_QUALIFIER)
    fun eventStreamScope(eventStreamProperties: EventStreamProperties): CoroutineScope = CoroutineUtil.newSingletonScope(
        scopeName = CoroutineScopeNames.EVENT_STREAM_SCOPE,
        threadCount = eventStreamProperties.threadCount
    )
}

object CoroutineScopeNames {
    const val EVENT_STREAM_SCOPE = "eventStreamCoroutineScope"
}
