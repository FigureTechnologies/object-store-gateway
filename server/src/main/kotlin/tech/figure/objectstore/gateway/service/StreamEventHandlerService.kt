package tech.figure.objectstore.gateway.service

import cosmos.crypto.secp256k1.Keys.PubKey
import cosmos.tx.v1beta1.ServiceOuterClass.GetTxRequest
import io.provenance.client.grpc.PbClient
import io.provenance.scope.encryption.ecies.ECUtils
import io.provenance.scope.encryption.util.getAddress
import mu.KLogging
import org.springframework.stereotype.Service
import tech.figure.objectstore.gateway.configuration.ProvenanceProperties
import tech.figure.objectstore.gateway.eventstream.GatewayEvent
import tech.figure.objectstore.gateway.eventstream.GatewayExpectedEventType

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
    fun handleEvent(event: GatewayEvent) {
        when (event.gatewayEventType) {
            GatewayExpectedEventType.ACCESS_GRANT -> scopePermissionsService.processAccessGrant(
                scopeAddress = event.scopeAddress,
                granteeAddress = event.targetAccount,
                grantSourceAddresses = getSignerAddressesForTx(event.txHash),
                grantId = event.accessGrantId,
                sourceDetails = "source: $event",
            ).also { grantResponse ->
                when (grantResponse) {
                    is GrantResponse.Accepted -> logger.info("$event succeeded in creating grant with granter [${grantResponse.granterAddress}]")
                    is GrantResponse.Rejected -> logger.warn("$event rejected: ${grantResponse.message}")
                    is GrantResponse.Error -> logger.error("$event failed with error", grantResponse.cause)
                }
            }
            GatewayExpectedEventType.ACCESS_REVOKE -> scopePermissionsService.processAccessRevoke(
                scopeAddress = event.scopeAddress,
                granteeAddress = event.targetAccount,
                revokeSourceAddresses = getSignerAddressesForTx(event.txHash),
                // Include the target account as an account that can revoke permissions.  Any account should be able
                // to revoke its own grants
                additionalAuthorizedAddresses = setOf(event.targetAccount),
                grantId = event.accessGrantId,
                sourceDetails = "source: $event",
            ).also { revokeResponse ->
                when (revokeResponse) {
                    is RevokeResponse.Accepted -> logger.info("$event succeeded in revoking ${revokeResponse.revokedGrantsCount} grant(s)")
                    is RevokeResponse.Rejected -> logger.warn("$event rejected: ${revokeResponse.message}")
                    is RevokeResponse.Error -> logger.error("$event failed with error", revokeResponse.cause)
                }
            }
        }
    }

    /**
     * Queries for the given transaction using the registered cosmos service, inspects the auth info for signers,
     * and converts the public keys to Provenance Blockchain bech32 addresses.
     *
     * @param txHash The hashed output for the transaction that emitted the given event.
     */
    private fun getSignerAddressesForTx(txHash: String): Set<String> = pbClient
        .cosmosService
        .getTx(GetTxRequest.newBuilder().setHash(txHash).build())
        .tx
        .authInfo
        .signerInfosList
        .map { signerInfo -> signerInfo.publicKey.unpack(PubKey::class.java).key.toByteArray() }
        .map(ECUtils::convertBytesToPublicKey)
        .map { publicKey -> publicKey.getAddress(provenanceProperties.mainNet) }
        .toSet()
}
