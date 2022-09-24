package tech.figure.objectstore.gateway.service

import io.provenance.client.grpc.PbClient
import io.provenance.metadata.v1.ScopeRequest
import io.provenance.metadata.v1.ScopeResponse
import io.provenance.scope.encryption.model.KeyRef
import io.provenance.scope.encryption.util.getAddress
import io.provenance.scope.objectstore.client.CachedOsClient
import io.provenance.scope.objectstore.util.base64Decode
import io.provenance.scope.sdk.extensions.resultType
import mu.KLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import tech.figure.objectstore.gateway.GatewayOuterClass
import tech.figure.objectstore.gateway.configuration.BeanQualifiers
import tech.figure.objectstore.gateway.configuration.ProvenanceProperties
import tech.figure.objectstore.gateway.exception.AccessDeniedException
import tech.figure.objectstore.gateway.repository.ScopePermissionsRepository
import tech.figure.objectstore.gateway.util.toByteString
import tech.figure.objectstore.gateway.util.toOwnerParty
import java.security.PublicKey

@Component
class ScopeFetchService(
    private val objectStoreClient: CachedOsClient,
    private val pbClient: PbClient,
    private val scopePermissionsRepository: ScopePermissionsRepository,
    @Qualifier(BeanQualifiers.OBJECTSTORE_ENCRYPTION_KEYS) private val encryptionKeys: Map<String, KeyRef>,
    private val provenanceProperties: ProvenanceProperties,
) {
    companion object : KLogging()

    /**
     * Fetches scope details for a given bech32 Provenance Blockchain scope address.
     *
     * @param scopeAddress The unique bech32 address that points to a pbc scope.
     * @param includeRecords If true, record values for the scope will be fetched from the chain.  Useful for handling
     * record details for object store interactions.
     * @param includeSessions If true, session values for the scope will be fetched from the chain.  Useful for scope
     * owner linking to the registered object store keys for deserialization during scope fetches.
     */
    fun fetchScope(
        scopeAddress: String,
        includeRecords: Boolean = false,
        includeSessions: Boolean = false,
    ): ScopeResponse = pbClient
        .metadataClient
        .scope(
            ScopeRequest.newBuilder().also { scopeRequest ->
                scopeRequest.scopeId = scopeAddress
                scopeRequest.includeRecords = includeRecords
                scopeRequest.includeSessions = includeSessions
            }.build()
        )

    fun fetchScopeForGrantee(
        scopeAddress: String,
        requesterPublicKey: PublicKey,
        providedGranterAddress: String?,
    ): List<GatewayOuterClass.Record> {
        val requesterAddress = requesterPublicKey.getAddress(provenanceProperties.mainNet)

        val scopeResponse = fetchScope(scopeAddress = scopeAddress, includeRecords = true)

        // If the requester is registered in the service and they own the scope, there's no reason they can't decrypt their own
        // data
        val encryptionKey = encryptionKeys[requesterAddress]
            // a scope owner can request their own scope data
            ?.takeIf { scopeResponse.scope.scope.ownersList.contains(requesterAddress.toOwnerParty()) || scopeResponse.scope.scope.valueOwnerAddress == requesterAddress }
            ?.also { logger.debug("Received request for scope data from scope owner [scope: $scopeAddress, owner: $requesterAddress]") }
            // If the requester does not own the scope and/or their encryption key is not stored, attempt to find an access grant
            ?: run {
                val granterAddress = scopePermissionsRepository.getAccessGranterAddresses(scopeAddress, requesterAddress)
                    // If a granter is provided and not in the list of stored granters, the request is invalid
                    .takeIf { addresses -> providedGranterAddress == null || providedGranterAddress in addresses }
                    // Always use the provided granter if it was specified, otherwise just use any of the available granters
                    ?.let { addresses -> providedGranterAddress ?: addresses.firstOrNull() }
                    ?: run {
                        logger.info("Request for scope data without access received")
                        throw AccessDeniedException("Scope access not granted to $requesterAddress")
                    }
                encryptionKeys[granterAddress]
                    ?: run {
                        logger.warn("Valid request for scope data with an unknown key received")
                        throw AccessDeniedException("Encryption key for granter $granterAddress not found")
                    }
            }

        logger.debug("Valid request for scope data received")
        logger.debug("fetching ${scopeResponse.recordsCount} records for scope $scopeAddress")
        return scopeResponse.recordsList
            .map { record ->
                GatewayOuterClass.Record.newBuilder()
                    .setName(record.record.name)
                    .addAllInputs(
                        record.record.inputsList.map {
                            GatewayOuterClass.RecordObject.newBuilder()
                                .setHash(it.hash)
                                .setType(it.typeName)
                                .setObjectBytes(objectStoreClient.getJar(it.hash.base64Decode(), encryptionKey).get().readAllBytes().toByteString())
                                .build()
                        }
                    )
                    .addAllOutputs(
                        record.record.outputsList.map {
                            GatewayOuterClass.RecordObject.newBuilder()
                                .setHash(it.hash)
                                .setType(record.record.resultType())
                                .setObjectBytes(objectStoreClient.getJar(it.hash.base64Decode(), encryptionKey).get().readAllBytes().toByteString())
                                .build()
                        }
                    )
                    .build()
            }
    }
}
