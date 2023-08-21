package tech.figure.objectstore.gateway.configuration

import kotlinx.coroutines.CoroutineScope
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tech.figure.objectstore.gateway.util.CoroutineUtil

@Configuration
class CoroutineConfig {
    @Bean(BeanQualifiers.BLOCK_STREAM_COROUTINE_SCOPE_QUALIFIER)
    fun blockStreamScope(blockStreamProperties: BlockStreamProperties): CoroutineScope = CoroutineUtil.newSingletonScope(
        scopeName = CoroutineScopeNames.BLOCK_STREAM_SCOPE,
        threadCount = blockStreamProperties.threadCount
    )
}

object CoroutineScopeNames {
    const val BLOCK_STREAM_SCOPE = "blockStreamCoroutineScope"
}
