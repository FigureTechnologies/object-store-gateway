package tech.figure.objectstore.gateway.eventstream

/**
 * All attribute values contained in a wasm event that are expected to be emitted in order to request that permission
 * is granted on a scope to a bech32 address.
 *
 * @param key The attribute's key that should relate to the target value.
 */
enum class GatewayExpectedAttribute(val key: String) {
    /**
     * An optional free-form text parameter that specifies an additional unique ID for a scope permission record. If
     * specified, access revocations can directly target this record for removal.
     */
    ACCESS_GRANT_ID("object_store_gateway_access_grant_id"),

    /**
     * The type of event to execute upon receipt of this attribute.
     */
    EVENT_TYPE("object_store_gateway_event_type"),

    /**
     * The bech32 Provenance Blockchain scope address to which the event is related.  This will cause the gateway to
     * look up the target scope and verify its contents based on the event type.
     */
    SCOPE_ADDRESS("object_store_gateway_scope_address"),

    /**
     * The bech32 address of the account for which to execute the event actions.
     */
    TARGET_ACCOUNT("object_store_gateway_target_account_address"),
}
