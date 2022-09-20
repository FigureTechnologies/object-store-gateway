package tech.figure.objectstore.gateway.util

import io.micrometer.core.instrument.util.NamedThreadFactory
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import mu.KotlinLogging
import java.util.concurrent.Executors

object CoroutineUtil {
    private val logger = KotlinLogging.logger {}

    fun newSingletonScope(
        scopeName: String,
        threadCount: Int,
    ): CoroutineScope = Executors.newFixedThreadPool(threadCount, NamedThreadFactory(scopeName))
        .asCoroutineDispatcher()
        .plus(CoroutineExceptionHandler { _, throwable -> logger.error("Coroutine execution failed with an exception", throwable) })
        .plus(SupervisorJob())
        .let(::CoroutineScope)
}
