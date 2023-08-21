package tech.figure.objectstore.gateway.eventstream

import mu.KotlinLogging
import tech.figure.block.api.proto.BlockOuterClass.TxEvent

/**
 * Wrapper for a wasm event received from the Figure Block Api.
 *
 * @param sourceEvent The event encountered by the blockapi library that may or may not contain expected values in
 * its attributes.
 */
class BlockApiGatewayEvent(sourceEvent: TxEvent) : GatewayEvent(
    attributeMap = sourceEvent.attributesList.associate { it.key to it.value },
    txHash = sourceEvent.txHash,
    eventType = sourceEvent.eventType
) {
    companion object {
        private val logger = KotlinLogging.logger {}

        /**
         * Attempts to instantiate a GatewayEvent with the given TxEvent.  Each value derivation will throw an exception
         * when the value is not found, which will result in the catch being used to return a null value.
         *
         * @param sourceEvent The event-stream TxEvent to use as the instantiation source for the GatewayEvent.  Values
         * are eagerly pulled from the contained attributes, which will either result in a fully-formed GatewayEvent,
         * or an exception.
         */
        fun fromEventOrNull(sourceEvent: TxEvent): BlockApiGatewayEvent? = try {
            BlockApiGatewayEvent(sourceEvent)
        } catch (e: Exception) {
            logger.debug("Failed to convert source TxEvent[${sourceEvent.txHash}] to a GatewayGrantEvent")
            null
        }
    }

    override fun toString(): String = "BlockApiGatewayEvent [$txHash] of type [${gatewayEventType.wasmName}] for scope [$scopeAddress] and target [$targetAccount]"
}
