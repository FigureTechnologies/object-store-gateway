package io.provenance.objectstore.gateway.server

import io.grpc.stub.StreamObserver
import io.provenance.client.protobuf.extensions.isNotSet
import io.provenance.objectstore.gateway.GatewayGrpc
import io.provenance.objectstore.gateway.GatewayOuterClass
import io.provenance.objectstore.gateway.configuration.ProvenanceProperties
import io.provenance.objectstore.gateway.exception.AccessDeniedException
import io.provenance.objectstore.gateway.exception.SignatureValidationException
import io.provenance.objectstore.gateway.exception.TimestampValidationException
import io.provenance.objectstore.gateway.repository.ScopePermissionsRepository
import io.provenance.objectstore.gateway.service.ScopeFetchService
import io.provenance.objectstore.gateway.util.validateSignature
import io.provenance.scope.encryption.model.KeyRef
import io.provenance.scope.encryption.util.getAddress
import io.provenance.scope.objectstore.util.toPublicKey
import io.provenance.scope.util.toOffsetDateTime
import mu.KLogging
import org.lognet.springboot.grpc.GRpcService
import java.time.OffsetDateTime

@GRpcService()
class ObjectStoreGatewayServer(
    private val scopePermissionsRepository: ScopePermissionsRepository,
    private val scopeFetchService: ScopeFetchService,
    private val provenanceProperties: ProvenanceProperties,
    private val encryptionKeys: Map<String, KeyRef>
): GatewayGrpc.GatewayImplBase() {

    companion object : KLogging()

    override fun fetchObject(
        request: GatewayOuterClass.FetchObjectRequest,
        responseObserver: StreamObserver<GatewayOuterClass.FetchObjectResponse>
    ) {
        val requesterPublicKey = request.signature.signer.signingPublicKey.toPublicKey()

        if (request.params.expiration.isNotSet() || request.params.expiration.toOffsetDateTime().isBefore(OffsetDateTime.now())) {
            logger.info("Request with invalid expiration received")
            throw TimestampValidationException("${if (request.params.expiration.isNotSet()) "Missing" else "Expired"} expiration")
        }

        if (!request.validateSignature()) {
            logger.info("Request with invalid signature received")
            throw SignatureValidationException("Invalid signature")
        }

        val requesterAddress = requesterPublicKey.getAddress(provenanceProperties.mainNet)
        val granterAddress = scopePermissionsRepository.getAccessGranterAddress(request.params.scopeAddress, requesterAddress, request.params.granterAddress.takeIf { it.isNotBlank() })
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
        scopeFetchService.fetchScope(request.params.scopeAddress, encryptionKey).let {
            responseObserver.onNext(GatewayOuterClass.FetchObjectResponse.newBuilder()
                .setScopeId(request.params.scopeAddress)
                .addAllRecords(it)
                .build()
            )
        }
        responseObserver.onCompleted()
    }
}
