package io.provenance.objectstore.gateway.eventstream

import io.provenance.eventstream.stream.models.TxEvent
import mu.KotlinLogging

class GatewayGrantEvent(sourceEvent: TxEvent) : GatewayEventAdapter(sourceEvent) {
    val txHash: String = sourceEvent.txHash
    // TODO: Maybe create constants for these values like with the AssetClassificationEvent for a sense of completion
    val scopeAddress: String = getEventStringValue("object_store_gateway_registered_scope")
    val grantedAccount: String = getEventStringValue("object_store_gateway_granted_account")

    companion object {
        private val logger = KotlinLogging.logger {}

        fun fromEventOrNull(sourceEvent: TxEvent): GatewayGrantEvent? = try {
            GatewayGrantEvent(sourceEvent)
        } catch (e: Exception) {
            logger.debug("Failed to convert source TxEvent[${sourceEvent.txHash}] to a GatewayGrantEvent")
            null
        }
    }
}
