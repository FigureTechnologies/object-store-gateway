package tech.figure.objectstore.gateway.eventstream

import mu.KotlinLogging
import tech.figure.eventstream.stream.models.TxEvent

/**
 * Wrapper for a wasm event received from the Provenance Event Stream.
 *
 * @param sourceEvent The event encountered by the event-stream library that may or may not contain expected values in
 * its attributes.
 */
class ProvenanceGatewayEvent(sourceEvent: TxEvent) : GatewayEvent(
    attributeMap = TxAttribute.parseTxEventMap(sourceEvent).mapValues { it.value.value },
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
        fun fromEventOrNull(sourceEvent: TxEvent): ProvenanceGatewayEvent? = try {
            ProvenanceGatewayEvent(sourceEvent)
        } catch (e: Exception) {
            logger.debug("Failed to convert source TxEvent[${sourceEvent.txHash}] to a GatewayGrantEvent")
            null
        }
    }

    override fun toString(): String = "ProvenanceGatewayEvent [$txHash] of type [${gatewayEventType.wasmName}] for scope [$scopeAddress] and target [$targetAccount]"
}
