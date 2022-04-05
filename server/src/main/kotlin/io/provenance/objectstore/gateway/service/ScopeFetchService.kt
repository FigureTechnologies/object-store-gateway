package io.provenance.objectstore.gateway.service

import io.provenance.client.grpc.PbClient
import io.provenance.metadata.v1.Party
import io.provenance.metadata.v1.PartyType
import io.provenance.metadata.v1.ScopeRequest
import io.provenance.objectstore.gateway.GatewayOuterClass
import io.provenance.objectstore.gateway.configuration.ProvenanceProperties
import io.provenance.objectstore.gateway.exception.AccessDeniedException
import io.provenance.objectstore.gateway.model.ScopePermissionsTable.granterAddress
import io.provenance.objectstore.gateway.repository.ScopePermissionsRepository
import io.provenance.objectstore.gateway.util.toByteString
import io.provenance.scope.encryption.model.KeyRef
import io.provenance.scope.encryption.util.getAddress
import io.provenance.scope.objectstore.client.CachedOsClient
import io.provenance.scope.objectstore.util.base64Decode
import io.provenance.scope.objectstore.util.toPublicKey
import io.provenance.scope.sdk.extensions.resultType
import mu.KLogging
import org.springframework.stereotype.Component
import java.security.PublicKey

@Component
class ScopeFetchService(
    private val objectStoreClient: CachedOsClient,
    private val pbClient: PbClient,
    private val scopePermissionsRepository: ScopePermissionsRepository,
    private val encryptionKeys: Map<String, KeyRef>,
    private val provenanceProperties: ProvenanceProperties,
) {
    companion object : KLogging()

    fun fetchScope(scopeAddress: String, requesterPublicKey: PublicKey, providedGranterAddress: String?): List<GatewayOuterClass.Record> {
        val requesterAddress = requesterPublicKey.getAddress(provenanceProperties.mainNet)

        val scope = pbClient.metadataClient.scope(ScopeRequest.newBuilder().setScopeId(scopeAddress).setIncludeRecords(true).build())

        val granterAddress = if (scope.scope.scope.ownersList.contains(requesterAddress.toOwnerParty()) || scope.scope.scope.valueOwnerAddress == requesterAddress) {
            // a scope owner can request their own scope data
            logger.debug("Received request for scope data from scope owner [scope: $scopeAddress, owner: $requesterAddress]")
            requesterAddress
        } else {
            // non-scope owners need to have been granted access to this scope
            scopePermissionsRepository.getAccessGranterAddress(scopeAddress, requesterAddress, providedGranterAddress)
        }

        if (granterAddress == null) {
            logger.info("Request for scope data without access received")
            throw AccessDeniedException("Scope access not granted to $requesterAddress")
        }

        val encryptionKey = encryptionKeys[granterAddress]
        if (encryptionKey == null) {
            logger.warn("Valid request for scope data with an unknown key received")
            throw AccessDeniedException("Encryption key for granter $granterAddress not found")
        }

        logger.debug("Valid request for scope data received")
        logger.debug("fetching ${scope.recordsCount} records for scope $scopeAddress")
        return scope.recordsList
            .map { record ->
                GatewayOuterClass.Record.newBuilder()
                    .setName(record.record.name)
                    .addAllInputs(record.record.inputsList.map {
                        GatewayOuterClass.RawObject.newBuilder()
                            .setHash(it.hash)
                            .setType(it.typeName)
                            .setObjectBytes(objectStoreClient.getJar(it.hash.base64Decode(), encryptionKey).get().readAllBytes().toByteString())
                            .build()
                    })
                    .addAllOutputs(record.record.outputsList.map {
                        GatewayOuterClass.RawObject.newBuilder()
                            .setHash(it.hash)
                            .setType(record.record.resultType())
                            .setObjectBytes(objectStoreClient.getJar(it.hash.base64Decode(), encryptionKey).get().readAllBytes().toByteString())
                            .build()
                    })
                    .build()
            }
    }

    private fun String.toOwnerParty() = Party.newBuilder().setAddress(this).setRole(PartyType.PARTY_TYPE_OWNER).build()
}
