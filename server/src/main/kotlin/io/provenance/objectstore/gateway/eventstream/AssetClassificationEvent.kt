package io.provenance.objectstore.gateway.eventstream

import io.provenance.eventstream.stream.models.TxEvent

class AssetClassificationEvent(sourceEvent: TxEvent) : GatewayEventAdapter(sourceEvent) {
    val eventType: ContractEvent? by lazy { this.getEventValue(ContractKey.EVENT_TYPE.eventName) { ContractEvent.forContractName(it) } }
    val assetType: String? by lazy { this.getEventStringValue(ContractKey.ASSET_TYPE.eventName) }
    val scopeAddress: String? by lazy { this.getEventStringValue(ContractKey.SCOPE_ADDRESS.eventName) }
    val scopeOwnerAddress: String? by lazy { this.getEventStringValue(ContractKey.SCOPE_OWNER_ADDRESS.eventName) }
    val verifierAddress: String? by lazy { this.getEventStringValue(ContractKey.VERIFIER_ADDRESS.eventName) }
    val newValue: String? by lazy { this.getEventStringValue(ContractKey.NEW_VALUE.eventName) }

    override fun toString(): String = "AssetClassificationEvent[" +
        "eventType=$eventType, " +
        "assetType=$assetType, " +
        "scopeAddress=$scopeAddress, " +
        "verifierAddress=$verifierAddress, " +
        "newValue=$newValue]"
}
