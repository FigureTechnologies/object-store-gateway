package tech.figure.objectstore.gateway.eventstream

import mu.KotlinLogging
import tech.figure.eventstream.decodeBase64
import tech.figure.eventstream.stream.models.Event
import tech.figure.eventstream.stream.models.TxEvent

data class TxAttribute(val key: String, val value: String) {
    companion object {
        private val logger = KotlinLogging.logger {}

        fun parseTxEventMap(txEvent: TxEvent): Map<String, TxAttribute> = txEvent
            .attributes
            .mapNotNull(TxAttribute::fromEventOrNull)
            .associateBy { it.key }

        fun fromEventOrNull(event: Event): TxAttribute? =
            decodeValue(event.key)?.let { key ->
                decodeValue(event.value)?.let { value ->
                    TxAttribute(key.lowercase(), value)
                }
            }

        private fun decodeValue(value: String? = null): String? = try {
            value?.decodeBase64()
        } catch (e: Exception) {
            logger.warn("Failed to decode base64 value in tx event", e)
            null
        }
    }
}
