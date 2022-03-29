package io.provenance.objectstore.gateway.eventstream

enum class ContractKey(val eventName: String) {
    EVENT_TYPE("asset_event_type"),
    ASSET_TYPE("asset_type"),
    SCOPE_ADDRESS("asset_scope_address"),
    SCOPE_OWNER_ADDRESS("asset_scope_owner_address"),
    VERIFIER_ADDRESS("asset_verifier_address"),
    NEW_VALUE("asset_new_value"),
    ;
}

