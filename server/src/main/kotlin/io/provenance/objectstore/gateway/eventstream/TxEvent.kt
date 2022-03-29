package io.provenance.objectstore.gateway.eventstream

import io.provenance.eventstream.extensions.decodeBase64
import io.provenance.eventstream.stream.models.Event
import io.provenance.eventstream.stream.models.TxEvent
import mu.KLogging


class AssetClassificationEvent(val sourceEvent: TxEvent) {
    private companion object : KLogging()

    private val attributeMap: Map<String, TxAttribute> by lazy {
        sourceEvent
            .attributes
            .mapNotNull(TxAttribute::fromEventOrNull)
            .associateBy { it.key }
    }

    private fun getEventStringValue(key: ContractKey): String? = attributeMap[key.eventName]?.value

    private inline fun <reified T> getEventValue(key: ContractKey, transform: (String) -> T): T? = try {
        getEventStringValue(key)?.let(transform)
    } catch (e: Exception) {
        logger.warn("Failed to convert derived value for event key [${key.eventName}] to type [${T::class.qualifiedName}]", e)
        null
    }


    val eventType: ContractEvent? by lazy { this.getEventValue(ContractKey.EVENT_TYPE) { ContractEvent.forContractName(it) } }
    val assetType: String? by lazy { this.getEventStringValue(ContractKey.ASSET_TYPE) }
    val scopeAddress: String? by lazy { this.getEventStringValue(ContractKey.SCOPE_ADDRESS) }
    val scopeOwnerAddress: String? by lazy { this.getEventStringValue(ContractKey.SCOPE_OWNER_ADDRESS) }
    val verifierAddress: String? by lazy { this.getEventStringValue(ContractKey.VERIFIER_ADDRESS) }
    val newValue: String? by lazy { this.getEventStringValue(ContractKey.NEW_VALUE) }

    private data class TxAttribute(val key: String, val value: String) {
        companion object {
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

    override fun toString(): String = "AssetClassificationEvent[" +
            "eventType=$eventType, " +
            "assetType=$assetType, " +
            "scopeAddress=$scopeAddress, " +
            "verifierAddress=$verifierAddress, " +
            "newValue=$newValue]"
}
