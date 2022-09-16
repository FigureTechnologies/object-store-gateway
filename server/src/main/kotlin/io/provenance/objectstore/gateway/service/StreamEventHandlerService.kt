package io.provenance.objectstore.gateway.service

import cosmos.crypto.secp256k1.Keys.PubKey
import cosmos.tx.v1beta1.ServiceOuterClass.GetTxRequest
import io.provenance.client.grpc.PbClient
import io.provenance.eventstream.stream.models.TxEvent
import io.provenance.metadata.v1.ScopeRequest
import io.provenance.metadata.v1.ScopeResponse
import io.provenance.objectstore.gateway.configuration.ProvenanceProperties
import io.provenance.objectstore.gateway.eventstream.AssetClassificationEvent
import io.provenance.objectstore.gateway.eventstream.ContractEvent
import io.provenance.objectstore.gateway.eventstream.GatewayGrantEvent
import io.provenance.objectstore.gateway.extensions.checkNotNull
import io.provenance.objectstore.gateway.repository.ScopePermissionsRepository
import io.provenance.scope.encryption.ecies.ECUtils
import io.provenance.scope.encryption.util.getAddress
import mu.KLogging
import org.springframework.stereotype.Service
import java.security.PublicKey

@Service
class StreamEventHandlerService(
    private val accountAddresses: Set<String>,
    private val scopePermissionsRepository: ScopePermissionsRepository,
    private val pbClient: PbClient,
    private val provenanceProperties: ProvenanceProperties,
) {
    private companion object : KLogging()

    fun handleEvent(event: TxEvent) {
        // Try first to intercept incoming events as Gateway Grant events, but fallback to asset classification if
        // the event does not fit the structure
        GatewayGrantEvent.fromEventOrNull(event)
            ?.also(::handleGatewayGrant)
            ?: handleEvent(AssetClassificationEvent(event))
    }

    fun handleGatewayGrant(gatewayGrant: GatewayGrantEvent) {
        val scopeResponse = lookupScope(gatewayGrant.scopeAddress)
        val granterAddress = findRegisteredScopeOwnerAddress(scopeResponse) ?: run {
            logger.info("Skipping event [${gatewayGrant.txHash}] for unrelated scope [${gatewayGrant.scopeAddress}]")
            return
        }
        if (scopeResponse.scope.scope.valueOwnerAddress !in getSignersForTx(gatewayGrant.txHash).map { it.second }) {
            logger.info("Skipping event [${gatewayGrant.txHash}] signed by non scope owner")
            return
        }
        handleAccessGrant(
            txHash = gatewayGrant.txHash,
            scopeAddress = gatewayGrant.scopeAddress,
            granterAddress = granterAddress,
            granteeAddress = gatewayGrant.grantedAccount,
        )
    }

    private fun getSignersForTx(txHash: String): List<Pair<PublicKey, String>> = pbClient
        .cosmosService
        .getTx(GetTxRequest.newBuilder().setHash(txHash).build())
        .tx
        .authInfo
        .signerInfosList
        .map { signerInfo -> signerInfo.publicKey.unpack(PubKey::class.java).key.toByteArray() }
        .map(ECUtils::convertBytesToPublicKey)
        .map { publicKey -> publicKey to publicKey.getAddress(provenanceProperties.mainNet) }

    fun handleEvent(event: AssetClassificationEvent) {
        // This will be extremely common - we cannot filter events upfront in the event stream code, so this check
        // throws away everything not emitted by the asset classification smart contract
        if (event.eventType == null) {
            logger.debug("Skipping unrelated wasm event with tx hash [${event.sourceEvent.txHash}]")
            return
        }
        // Only regard expected event types
        if (event.eventType !in ContractEvent.HANDLED_EVENTS) {
            logger.info("Skipping unsupported event type [${event.eventType}] from asset classification contract")
            return
        }
        // This will commonly happen - the contract emits events that don't target the verifier at all, but they'll
        // still pass through here
        if (event.verifierAddress == null) {
            logger.debug("No way to determine which verifier event at tx hash [${event.sourceEvent.txHash}] was targeting. The address was null")
            return
        }
        // only handle events onboarded by registered key
        val registeredAddress = event.findRegisteredScopeOwnerAddress()
        if (registeredAddress == null) {
            logger.info("Skipping event of type [${event.eventType}] for unrelated scope owner [${event.scopeOwnerAddress}]")
            return
        }
        when (event.eventType) {
            ContractEvent.ONBOARD_ASSET -> handleOnboardAsset(event, registeredAddress)
            else -> throw IllegalStateException("After all event checks, an unexpected event was attempted for processing. Tx hash: [${event.sourceEvent.txHash}], event type: [${event.eventType}]")
        }
    }

    private fun lookupScope(scopeAddress: String): ScopeResponse = pbClient
        .metadataClient
        .scope(ScopeRequest.newBuilder().setScopeId(scopeAddress).setIncludeSessions(true).build())

    private fun AssetClassificationEvent.findRegisteredScopeOwnerAddress(): String? = if (scopeOwnerAddress.isWatchedAddress()) {
        scopeOwnerAddress
    } else {
        findRegisteredScopeOwnerAddress(
            scopeResponse = lookupScope(
                scopeAddress = scopeAddress
                    ?: error("Asset Classification Event [${this.sourceEvent.txHash}] did not include a scope address"),
            )
        )
    }

    private fun findRegisteredScopeOwnerAddress(scopeResponse: ScopeResponse): String? {
        scopeResponse.scope.scope.ownersList.firstOrNull { it.address.isWatchedAddress() }?.also {
            return it.address
        }
        scopeResponse.scope.scope.dataAccessList.firstOrNull { it.isWatchedAddress() }?.also {
            return it
        }
        scopeResponse.sessionsList.flatMap { it.session.partiesList }.firstOrNull { it.address.isWatchedAddress() }
            ?.also {
                return it.address
            }
        return null
    }

    private fun String?.isWatchedAddress(): Boolean = this != null && accountAddresses.contains(this)

    private fun handleOnboardAsset(event: AssetClassificationEvent, registeredAddress: String) = handleAccessGrant(
        txHash = event.sourceEvent.txHash,
        scopeAddress = event.scopeAddress.checkNotNull { "[ONBOARD ASSET | Tx: ${event.sourceEvent.txHash}]: Expected the onboard asset event to include a scope address" },
        granteeAddress = event.verifierAddress!!,
        granterAddress = registeredAddress,
    )

    private fun handleAccessGrant(
        txHash: String,
        scopeAddress: String,
        granteeAddress: String,
        granterAddress: String,
    ) {
        // fetch scope and add all hashes to lookup? Or just add scope to lookup? (probably do all hashes in v2)
        logger.info("[ACCESS GRANT | Tx: $txHash]: Adding verifier to access list for scope $scopeAddress and grantee $granteeAddress with granter $granterAddress")
        scopePermissionsRepository.addAccessPermission(
            scopeAddress = scopeAddress,
            granteeAddress = granteeAddress,
            granterAddress = granterAddress,
        )
    }
}
