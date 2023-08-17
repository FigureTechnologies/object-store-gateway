package tech.figure.objectstore.gateway.eventstream

/**
 * Wrapper for a wasm event that contains the expected object store gateway attributes to trigger an access grant.
 * This class eagerly loads the given values, throwing an exception when one is missing to ensure that only
 * properly-formed requests are accepted.
 */
open class GatewayEvent(
    attributeMap: Map<String, String>,
    eventType: String,
    txHash: String,
) : GatewayEventAdapter(
    attributeMap = attributeMap,
    eventType = eventType,
    txHash = txHash,
) {
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
}
