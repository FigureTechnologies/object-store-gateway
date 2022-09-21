package tech.figure.objectstore.gateway.service

import cosmos.crypto.secp256k1.Keys.PubKey
import cosmos.tx.v1beta1.ServiceOuterClass.GetTxRequest
import io.provenance.client.grpc.PbClient
import io.provenance.eventstream.stream.models.TxEvent
import io.provenance.scope.encryption.ecies.ECUtils
import io.provenance.scope.encryption.util.getAddress
import mu.KLogging
import org.springframework.stereotype.Service
import tech.figure.objectstore.gateway.configuration.ProvenanceProperties
import tech.figure.objectstore.gateway.eventstream.AcContractEvent
import tech.figure.objectstore.gateway.eventstream.AssetClassificationEvent
import tech.figure.objectstore.gateway.eventstream.GatewayEvent
import tech.figure.objectstore.gateway.eventstream.GatewayExpectedEventType
import tech.figure.objectstore.gateway.extensions.checkNotNull

@Service
class StreamEventHandlerService(
    private val scopePermissionsService: ScopePermissionsService,
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
        GatewayEvent.fromEventOrNull(event)?.let { gatewayEvent ->
            when (gatewayEvent.eventType) {
                GatewayExpectedEventType.ACCESS_GRANT -> scopePermissionsService.processAccessGrant(
                    scopeAddress = gatewayEvent.scopeAddress,
                    granteeAddress = gatewayEvent.targetAccount,
                    grantSourceAddresses = getSignerAddressesForTx(gatewayEvent.txHash),
                    grantId = gatewayEvent.accessGrantId,
                    sourceDetails = "source: $gatewayEvent",
                ).also { grantResponse ->
                    when (grantResponse) {
                        is GrantResponse.Accepted -> logger.info("$gatewayEvent succeeded in creating grant with granter [${grantResponse.granterAddress}]")
                        is GrantResponse.Rejected -> logger.warn("$gatewayEvent rejected: ${grantResponse.message}")
                        is GrantResponse.Error -> logger.error("$gatewayEvent failed with error", grantResponse.cause)
                    }
                }
                GatewayExpectedEventType.ACCESS_REVOKE -> scopePermissionsService.processAccessRevoke(
                    scopeAddress = gatewayEvent.scopeAddress,
                    granteeAddress = gatewayEvent.targetAccount,
                    revokeSourceAddresses = getSignerAddressesForTx(gatewayEvent.txHash),
                    // Include the target account as an account that can revoke permissions.  Any account should be able
                    // to revoke its own grants
                    additionalAuthorizedAddresses = listOf(gatewayEvent.targetAccount),
                    grantId = gatewayEvent.accessGrantId,
                    sourceDetails = "source: $gatewayEvent",
                ).also { revokeResponse ->
                    when (revokeResponse) {
                        is RevokeResponse.Accepted -> logger.info("$gatewayEvent succeeded in revoking ${revokeResponse.revokedGrantsCount} grant(s)")
                        is RevokeResponse.Rejected -> logger.warn("$gatewayEvent rejected: ${revokeResponse.message}")
                        is RevokeResponse.Error -> logger.error("$gatewayEvent failed with error", revokeResponse.cause)
                    }
                }
            }
        } ?: handleAssetClassificationEvent(AssetClassificationEvent(event))
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
        when (event.eventType) {
            AcContractEvent.ONBOARD_ASSET -> scopePermissionsService.processAccessGrant(
                scopeAddress = event.scopeAddress.checkNotNull { "[ONBOARD ASSET | Tx: ${event.txHash}]: Expected the onboard asset event to include a scope address" },
                granteeAddress = event.verifierAddress.checkNotNull { "[ONBOARD ASSET | Tx: ${event.txHash}]: Expected the onboard asset event to include a verifier address" },
                // The scope owner address is established in the contract by directly using the sender of the contract message,
                // making this relatively safe.
                grantSourceAddresses = listOfNotNull(event.scopeOwnerAddress),
                sourceDetails = "Asset Classification Event from tx [${event.txHash}]",
            )
            else -> throw IllegalStateException("After all event checks, an unexpected event was attempted for processing. Tx hash: [${event.txHash}], event type: [${event.eventType}]")
        }
    }
}
