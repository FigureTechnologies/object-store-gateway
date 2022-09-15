package io.provenance.objectstore.gateway.eventstream

import io.provenance.eventstream.stream.models.TxEvent
import mu.KLogging
import mu.KotlinLogging

abstract class GatewayEventAdapter(val sourceEvent: TxEvent) {
    private companion object : KLogging()

    private val attributeMap: Map<String, TxAttribute> by lazy { TxAttribute.parseTxEventMap(sourceEvent) }

    protected fun getEventStringValue(key: String): String? = attributeMap[key]?.value

    protected inline fun <reified T> getEventValue(key: String, transform: (String) -> T): T? = try {
        getEventStringValue(key)?.let(transform)
    } catch (e: Exception) {
        KotlinLogging.logger {}.warn("Failed to convert derived value for event key [$key] to type [${T::class.qualifiedName}]", e)
        null
    }
}
