package io.provenance.objectstore.gateway.eventstream

/**
 * This enum drives functionality to enact when these values are emitted by a smart contract wasm that conforms to the
 * gateway event attribute structure.
 */
enum class GatewayExpectedEventType(val wasmName: String) {
    /**
     * When this event type is encountered, the gateway will do the following:
     * - Fetch the scope related to the SCOPE_ADDRESS event value.
     * - Verify that the fetched scope's value owner is included in the event's signers list.
     * - Grant access to the scope's underlying data to the target account.
     */
    ACCESS_GRANT("access_grant"),

    /**
     * When this event type is encountered, the gateway will do the following:
     * - Fetch the scope related to the SCOPE_ADDRESS event value.
     * - Verify that the fetched scope's value owner OR the target address is included in the event's signers list. The
     * target account is allowed as a signer because data accessors should be able to revoke their own access.
     * - Revoke access to the scope's underlying data from the target account.
     */
    ACCESS_REVOKE("access_revoke");

    companion object {
        private val WASM_NAME_MAP: Map<String, GatewayExpectedEventType> by lazy { values().associateBy { it.wasmName } }

        fun fromWasmName(wasmName: String): GatewayExpectedEventType = WASM_NAME_MAP[wasmName]
            ?: throw IllegalArgumentException("No [${this::class.qualifiedName}] value exists for wasm event type value [$wasmName]")
    }
}
