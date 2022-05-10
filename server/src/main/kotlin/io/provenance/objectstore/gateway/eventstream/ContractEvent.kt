package io.provenance.objectstore.gateway.eventstream

enum class ContractEvent(val contractName: String, val isHandled: Boolean = false) {
    INSTANTIATE_CONTRACT("instantiate_contract"),
    MIGRATE_CONTRACT("migrate_contract"),
    ONBOARD_ASSET("onboard_asset", isHandled = true),
    VERIFY_ASSET("verify_asset"),
    ADD_ASSET_DEFINITION("add_asset_definition"),
    UPDATE_ASSET_DEFINITION("update_asset_definition"),
    TOGGLE_ASSET_DEFINITION("toggle_asset_definition"),
    ADD_ASSET_VERIFIER("add_asset_verifier"),
    UPDATE_ASSET_VERIFIER("update_asset_verifier"),
    BIND_CONTRACT_ALIAS("bind_contract_alias"),
    ;

    companion object {
        private val CONTRACT_NAME_MAP: Map<String, ContractEvent> by lazy { values().associateBy { it.contractName } }
        val HANDLED_EVENTS: Set<ContractEvent> by lazy { values().filter { it.isHandled }.toSet() }
        val HANDLED_EVENT_NAMES: Set<String> by lazy { HANDLED_EVENTS.map { it.contractName }.toSet() }

        fun forContractName(name: String): ContractEvent = CONTRACT_NAME_MAP[name] ?: throw IllegalArgumentException("Unknown ContractEvent variant [$name]")
    }
}
