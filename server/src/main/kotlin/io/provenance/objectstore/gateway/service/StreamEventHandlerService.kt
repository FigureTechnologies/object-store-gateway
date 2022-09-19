package io.provenance.objectstore.gateway.service

import cosmos.crypto.secp256k1.Keys.PubKey
import cosmos.tx.v1beta1.ServiceOuterClass.GetTxRequest
import io.provenance.client.grpc.PbClient
import io.provenance.eventstream.stream.models.TxEvent
import io.provenance.metadata.v1.ScopeRequest
import io.provenance.metadata.v1.ScopeResponse
import io.provenance.objectstore.gateway.configuration.ProvenanceProperties
import io.provenance.objectstore.gateway.eventstream.AcContractEvent
import io.provenance.objectstore.gateway.eventstream.AssetClassificationEvent
import io.provenance.objectstore.gateway.eventstream.GatewayEvent
import io.provenance.objectstore.gateway.eventstream.GatewayExpectedEventType
import io.provenance.objectstore.gateway.extensions.checkNotNull
import io.provenance.objectstore.gateway.repository.ScopePermissionsRepository
import io.provenance.scope.encryption.ecies.ECUtils
import io.provenance.scope.encryption.util.getAddress
import mu.KLogging
import org.springframework.stereotype.Service

@Service
class StreamEventHandlerService(
    private val accountAddresses: Set<String>,
    private val scopePermissionsRepository: ScopePermissionsRepository,
    private val pbClient: PbClient,
    private val provenanceProperties: ProvenanceProperties,
) {
    private companion object : KLogging()

    /**
     * The core entrypoint for this service.  Consumes an event and attempts to parse its values as gateway-mandated
     * values for taking specified actions.
     *
     * @param event Any event emitted by a wasm smart contract that may or may not conform to the specified event structure
     * of object store gateway.
     */
    fun handleEvent(event: TxEvent) {
        // Try first to intercept incoming events as Gateway events, but fallback to asset classification if the event
        // does not fit the expected gateway event structure
        GatewayEvent.fromEventOrNull(event)?.also { gatewayEvent ->
            when (gatewayEvent.eventType) {
                GatewayExpectedEventType.ACCESS_GRANT -> handleGatewayGrant(gatewayEvent)
                GatewayExpectedEventType.ACCESS_REVOKE -> handleGatewayRevoke(gatewayEvent)
            }
        } ?: handleAssetClassificationEvent(AssetClassificationEvent(event))
    }

    /**
     * Uses a successfully-parsed gateway grant event to grant access to a scope's underlying records if all validation
     * for the event's formation passes.
     *
     * @param gatewayEvent A wasm-emitted event that uses the gateway access grant event type.
     */
    private fun handleGatewayGrant(gatewayEvent: GatewayEvent) {
        val scopeResponse = lookupScope(gatewayEvent.scopeAddress)
        // Ensure that the referenced scope is owned in some way by one of the gateway's registered keys.
        // This ensures that properly-stored scopes will be able to be successfully decrypted in future requests by
        // the granted address.
        val granterAddress = findRegisteredScopeOwnerAddress(scopeResponse) ?: run {
            logger.info("Skipping $gatewayEvent not owned by any registered granters")
            return
        }
        // Ensure that the scope's value owner signed for the transaction that emitted this wasm event.  This ensures
        // that this access grant has been safely requested and should be made
        getSignerAddressesForTx(gatewayEvent.txHash).also { signerAddresses ->
            if (scopeResponse.scope.scope.valueOwnerAddress !in signerAddresses) {
                // This is a warning because an event attempted to grant access without actually owning the reference
                // scope.  This indicates a bad configuration or an attempt to hijack data access.
                logger.warn("Skipping $gatewayEvent due to unrelated signers: $signerAddresses")
                return
            }
        }
        handleAccessGrant(
            txHash = gatewayEvent.txHash,
            scopeAddress = gatewayEvent.scopeAddress,
            granterAddress = granterAddress,
            granteeAddress = gatewayEvent.targetAccount,
        )
    }

    /**
     * Uses a successfully-parsed gateway revoke event to revoke access to a scope's underlying records from a target
     * bech32 address if all validation for the event's formation passes.
     *
     * @param gatewayEvent A wasm-emitted event that uses the gateway access revoke event type.
     */
    private fun handleGatewayRevoke(gatewayEvent: GatewayEvent) {
        // Ensure that the event was in a transaction signed by either the target account or the scope owner.  This
        // validates that either the scope owner no longer wishes for the target account to have a grant, or that the
        // target account itself wishes to remove its own access
        getSignerAddressesForTx(gatewayEvent.txHash).also { signerAddresses ->
            logger.info("Found signers for tx [${gatewayEvent.txHash}]: $signerAddresses")
            if (
                gatewayEvent.targetAccount !in signerAddresses &&
                lookupScope(gatewayEvent.scopeAddress, includeSessions = false).scope.scope.valueOwnerAddress !in signerAddresses
            ) {
                logger.warn("Skipping $gatewayEvent for unrelated signers (not scope value owner or target address). Signers: $signerAddresses")
                return
            }
        }
        logger.info("[ACCESS REVOKE | Tx: ${gatewayEvent.txHash}]: Revoking account [${gatewayEvent.targetAccount}] from access list for scope [${gatewayEvent.scopeAddress}]")
        scopePermissionsRepository.revokeAccessPermission(
            scopeAddress = gatewayEvent.scopeAddress,
            granteeAddress = gatewayEvent.targetAccount,
        )
    }

    /**
     * Queries for the given transaction using the registered cosmos service, inspects the auth info for signers,
     * and converts the public keys to Provenance Blockchain bech32 addresses.
     *
     * @param txHash The hashed output for the transaction that emitted the given event.
     */
    private fun getSignerAddressesForTx(txHash: String): List<String> = pbClient
        .cosmosService
        .getTx(GetTxRequest.newBuilder().setHash(txHash).build())
        .tx
        .authInfo
        .signerInfosList
        .map { signerInfo -> signerInfo.publicKey.unpack(PubKey::class.java).key.toByteArray() }
        .map(ECUtils::convertBytesToPublicKey)
        .map { publicKey -> publicKey.getAddress(provenanceProperties.mainNet) }

    /**
     * Handles a given asset classification smart contract event.  This is a legacy handling function and should be
     * removed when the asset classification smart contract is coded to properly include the gateway grant event
     * structure.
     *
     * TODO: Remove after the asset classification smart contract does not any longer require backwards compatibility.
     */
    private fun handleAssetClassificationEvent(event: AssetClassificationEvent) {
        // This will be extremely common - we cannot filter events upfront in the event stream code, so this check
        // throws away everything not emitted by the asset classification smart contract
        if (event.eventType == null) {
            logger.debug("Skipping unrelated wasm event with tx hash [${event.txHash}]")
            return
        }
        // Only regard expected event types
        if (event.eventType !in AcContractEvent.HANDLED_EVENTS) {
            logger.info("Skipping unsupported event type [${event.eventType}] from asset classification contract")
            return
        }
        // This will commonly happen - the contract emits events that don't target the verifier at all, but they'll
        // still pass through here
        if (event.verifierAddress == null) {
            logger.debug("No way to determine which verifier event at tx hash [${event.txHash}] was targeting. The address was null")
            return
        }
        // only handle events onboarded by registered key
        val registeredAddress = event.findRegisteredScopeOwnerAddress()
        if (registeredAddress == null) {
            logger.info("Skipping event of type [${event.eventType}] for unrelated scope owner [${event.scopeOwnerAddress}]")
            return
        }
        when (event.eventType) {
            AcContractEvent.ONBOARD_ASSET -> handleAccessGrant(
                txHash = event.txHash,
                scopeAddress = event.scopeAddress.checkNotNull { "[ONBOARD ASSET | Tx: ${event.txHash}]: Expected the onboard asset event to include a scope address" },
                granteeAddress = event.verifierAddress!!,
                granterAddress = registeredAddress,
            )
            else -> throw IllegalStateException("After all event checks, an unexpected event was attempted for processing. Tx hash: [${event.txHash}], event type: [${event.eventType}]")
        }
    }

    /**
     * Fetches scope details for a given bech32 Provenance Blockchain scope address.
     *
     * @param scopeAddress The unique bech32 address that points to a pbc scope.
     * @param includeSessions If true, session value for the scope will be fetched from the chain.  Useful for scope
     * owner linking to the registered object store keys for deserialization during scope fetches.
     */
    private fun lookupScope(scopeAddress: String, includeSessions: Boolean = true): ScopeResponse = pbClient
        .metadataClient
        .scope(ScopeRequest.newBuilder().setScopeId(scopeAddress).setIncludeSessions(includeSessions).build())

    private fun AssetClassificationEvent.findRegisteredScopeOwnerAddress(): String? = if (scopeOwnerAddress.isWatchedAddress()) {
        scopeOwnerAddress
    } else {
        findRegisteredScopeOwnerAddress(
            scopeResponse = lookupScope(
                scopeAddress = scopeAddress
                    ?: error("Asset Classification Event [${this.txHash}] did not include a scope address"),
            )
        )
    }

    /**
     * Determines if the target scope contains a registered object store deserialization key.
     *
     * @param scopeResponse The response from the Provenance Blockchain that denotes all the values within a scope.
     */
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

    /**
     * Extension function to determine if the value is contained in the registered object store deserialization addresses.
     */
    private fun String?.isWatchedAddress(): Boolean = this in accountAddresses

    /**
     * Inserts the specified access information into the database.  This grants unfettered access to the given grantee
     * for the target scope, so this function should be used only after verifying that this action was safely and
     * properly requested.
     *
     * @param txHash The hash of the transaction that contained the event that caused this grant.
     * @param scopeAddress The bech32 address of the target scope for which to grant access.
     * @param granteeAddress The bech32 address of the target account for which to permit scope record access.
     * @param granterAddress The bech32 address of the key that will be used to deserialize object store scope record data
     * upon requests made by the grantee.
     */
    private fun handleAccessGrant(
        txHash: String,
        scopeAddress: String,
        granteeAddress: String,
        granterAddress: String,
    ) {
        // fetch scope and add all hashes to lookup? Or just add scope to lookup? (probably do all hashes in v2)
        logger.info("[ACCESS GRANT | Tx: $txHash]: Adding account [$granteeAddress] to access list for scope [$scopeAddress] with granter [$granterAddress]")
        scopePermissionsRepository.addAccessPermission(
            scopeAddress = scopeAddress,
            granteeAddress = granteeAddress,
            granterAddress = granterAddress,
        )
    }
}
