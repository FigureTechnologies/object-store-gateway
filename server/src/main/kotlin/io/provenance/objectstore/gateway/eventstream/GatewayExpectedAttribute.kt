package io.provenance.objectstore.gateway.eventstream

/**
 * All attribute values contained in a wasm event that are expected to be emitted in order to request that permission
 * is granted on a scope to a bech32 address.
 *
 * @param key The attribute's key that should relate to the target value.
 */
enum class GatewayExpectedAttribute(val key: String) {
    /**
     * The type of event to execute upon receipt of this attribute.
     */
    EVENT_TYPE("object_store_gateway_event_type"),

    /**
     * The bech32 Provenance Blockchain scope address to which the event is related.  This will cause the gateway to
     * look up the target scope and verify its contents based on the event type.
     */
    SCOPE_ADDRESS("object_store_gateway_target_scope_address"),

    /**
     * The bech32 address of the account for which to execute the event actions.
     */
    TARGET_ACCOUNT("object_store_gateway_target_account_address"),
}
