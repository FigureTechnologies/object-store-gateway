package io.provenance.objectstore.gateway.eventstream

import io.provenance.eventstream.stream.models.TxEvent
import mu.KLogging
import mu.KotlinLogging

abstract class GatewayEventAdapter(val sourceEvent: TxEvent) {
    private companion object : KLogging()

    private val attributeMap: Map<String, TxAttribute> by lazy { TxAttribute.parseTxEventMap(sourceEvent) }

    protected fun getEventStringValueOrNull(key: String): String? = attributeMap[key]?.value

    protected fun getEventStringValue(key: String): String = getEventStringValueOrNull(key)
        ?: error("Could not get String value [$key] for event [${sourceEvent.txHash}]")

    protected inline fun <reified T> getEventValueOrNull(key: String, transform: (String) -> T): T? = try {
        getEventStringValueOrNull(key)?.let(transform)
    } catch (e: Exception) {
        KotlinLogging.logger {}.warn("Failed to convert derived value for event key [$key] to type [${T::class.qualifiedName}]", e)
        null
    }

    protected inline fun <reified T> getEventValue(key: String, transform: (String) -> T): T = getEventValueOrNull(
        key = key,
        transform = transform,
    ) ?: error("Could not get key [$key] for event [${sourceEvent.txHash}]")
}
