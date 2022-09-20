package tech.figure.objectstore.gateway.eventstream

// TODO: Remove this class when asset classification events are no longer consumed
enum class AcContractKey(val eventName: String) {
    EVENT_TYPE("asset_event_type"),
    ASSET_TYPE("asset_type"),
    SCOPE_ADDRESS("asset_scope_address"),
    SCOPE_OWNER_ADDRESS("asset_scope_owner_address"),
    VERIFIER_ADDRESS("asset_verifier_address"),
    NEW_VALUE("asset_new_value"),
    ;
}
