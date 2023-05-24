package tech.figure.objectstore.gateway.configuration

import kotlinx.coroutines.CoroutineScope
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tech.figure.objectstore.gateway.util.CoroutineUtil

@Configuration
class CoroutineConfig {
    @Bean(BeanQualifiers.EVENT_STREAM_COROUTINE_SCOPE_QUALIFIER)
    fun eventStreamScope(eventStreamProperties: EventStreamProperties): CoroutineScope = CoroutineUtil.newSingletonScope(
        scopeName = CoroutineScopeNames.EVENT_STREAM_SCOPE,
        threadCount = eventStreamProperties.threadCount
    )
}

object CoroutineScopeNames {
    const val BATCH_PROCESS_SCOPE = "batchProcessCoroutineScope"
    const val EVENT_STREAM_SCOPE = "eventStreamCoroutineScope"
}
