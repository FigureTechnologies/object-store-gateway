package tech.figure.objectstore.gateway.eventstream

import mu.KotlinLogging
import tech.figure.eventstream.stream.models.TxEvent

/**
 * Wrapper for a wasm event that contains the expected object store gateway attributes to trigger an access grant.
 * This class eagerly loads the given values, throwing an exception when one is missing to ensure that only
 * properly-formed requests are accepted.
 */
class GatewayEvent(sourceEvent: TxEvent) : GatewayEventAdapter(sourceEvent) {
    /**
     * See GatewayExpectedAttribute.EVENT_TYPE and GatewayExpectedEventType for details.
     */
    val eventType: GatewayExpectedEventType = getEventValue(
        key = GatewayExpectedAttribute.EVENT_TYPE.key,
        transform = { eventTypeString -> GatewayExpectedEventType.fromWasmName(eventTypeString) },
    )

    /**
     * See GatewayExpectedAttribute.SCOPE_ADDRESS for details.
     */
    val scopeAddress: String = getEventStringValue(GatewayExpectedAttribute.SCOPE_ADDRESS.key)

    /**
     * See GatewayExpectedAttribute.TARGET_ACCOUNT for details.
     */
    val targetAccount: String = getEventStringValue(GatewayExpectedAttribute.TARGET_ACCOUNT.key)

    /**
     * See GatewayExpectedAttribute.ACCESS_GRANT_ID for details.
     */
    val accessGrantId: String? = getEventStringValueOrNull(GatewayExpectedAttribute.ACCESS_GRANT_ID.key)

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
        fun fromEventOrNull(sourceEvent: TxEvent): GatewayEvent? = try {
            GatewayEvent(sourceEvent)
        } catch (e: Exception) {
            logger.debug("Failed to convert source TxEvent[${sourceEvent.txHash}] to a GatewayGrantEvent")
            null
        }
    }

    override fun toString(): String = "GatewayEvent [$txHash] of type [${eventType.wasmName}] for scope [$scopeAddress] and target [$targetAccount]"
}
