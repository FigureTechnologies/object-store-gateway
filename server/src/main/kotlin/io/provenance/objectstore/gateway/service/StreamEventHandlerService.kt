package io.provenance.objectstore.gateway.service

import io.provenance.client.grpc.PbClient
import io.provenance.eventstream.stream.models.TxEvent
import io.provenance.metadata.v1.ScopeRequest
import io.provenance.objectstore.gateway.eventstream.AssetClassificationEvent
import io.provenance.objectstore.gateway.eventstream.ContractEvent
import io.provenance.objectstore.gateway.extensions.checkNotNull
import io.provenance.objectstore.gateway.repository.ScopePermissionsRepository
import mu.KLogging
import org.springframework.stereotype.Service

@Service
class StreamEventHandlerService(
    private val accountAddresses: Set<String>,
    private val scopePermissionsRepository: ScopePermissionsRepository,
    private val pbClient: PbClient,
) {
    private companion object : KLogging()

    fun handleEvent(event: TxEvent) {
        handleEvent(AssetClassificationEvent(event))
    }

    fun handleEvent(event: AssetClassificationEvent) {
        // This will be extremely common - we cannot filter events upfront in the event stream code, so this check
        // throws away everything not emitted by the asset classification smart contract
        if (event.eventType == null) {
            logger.debug("Skipping unrelated wasm event with tx hash [${event.sourceEvent.txHash}]")
            return
        }
        // Only handle events
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

    private fun AssetClassificationEvent.findRegisteredScopeOwnerAddress(): String? {
        if (scopeOwnerAddress.isWatchedAddress()) {
            return scopeOwnerAddress
        }

        pbClient.metadataClient.scope(ScopeRequest.newBuilder().setScopeId(scopeAddress).setIncludeSessions(true).build()).also { scopeResponse ->
            scopeResponse.scope.scope.ownersList.firstOrNull { it.address.isWatchedAddress() }?.also {
                return it.address
            }

            scopeResponse.scope.scope.dataAccessList.firstOrNull { it.isWatchedAddress() }?.also {
                return it
            }

            scopeResponse.sessionsList
                .flatMap { it.session.partiesList }
                .firstOrNull { it.address.isWatchedAddress() }?.also {
                    return it.address
                }
        }

        return null
    }

    private fun String?.isWatchedAddress(): Boolean = this != null && accountAddresses.contains(this)

    private fun handleOnboardAsset(event: AssetClassificationEvent, registeredAddress: String) {
        // fetch scope and add all hashes to lookup? Or just add scope to lookup?
        val logPrefix = "[ONBOARD ASSET | Tx: ${event.sourceEvent.txHash}]:"
        val scopeAddress = event.scopeAddress.checkNotNull { "$logPrefix Expected the onboard asset event to include a scope address" }

        logger.info("$logPrefix Adding verifier to access list for scope $scopeAddress with granter $registeredAddress")
        scopePermissionsRepository.addAccessPermission(event.scopeAddress!!, event.verifierAddress!!, registeredAddress)
    }
}
