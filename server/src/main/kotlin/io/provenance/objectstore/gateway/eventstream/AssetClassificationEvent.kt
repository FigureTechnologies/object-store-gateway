package io.provenance.objectstore.gateway.eventstream

import io.provenance.eventstream.stream.models.TxEvent

// TODO: Remove this class when asset classification events are no longer consumed
class AssetClassificationEvent(sourceEvent: TxEvent) : GatewayEventAdapter(sourceEvent) {
    val eventType: AcContractEvent? by lazy { this.getEventValueOrNull(AcContractKey.EVENT_TYPE.eventName) { AcContractEvent.forContractName(it) } }
    val assetType: String? by lazy { this.getEventStringValueOrNull(AcContractKey.ASSET_TYPE.eventName) }
    val scopeAddress: String? by lazy { this.getEventStringValueOrNull(AcContractKey.SCOPE_ADDRESS.eventName) }
    val scopeOwnerAddress: String? by lazy { this.getEventStringValueOrNull(AcContractKey.SCOPE_OWNER_ADDRESS.eventName) }
    val verifierAddress: String? by lazy { this.getEventStringValueOrNull(AcContractKey.VERIFIER_ADDRESS.eventName) }
    val newValue: String? by lazy { this.getEventStringValueOrNull(AcContractKey.NEW_VALUE.eventName) }

    override fun toString(): String = "AssetClassificationEvent[" +
        "eventType=$eventType, " +
        "assetType=$assetType, " +
        "scopeAddress=$scopeAddress, " +
        "verifierAddress=$verifierAddress, " +
        "newValue=$newValue]"
}
