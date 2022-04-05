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
    private val scopeFetchService: ScopeFetchService,
): GatewayGrpc.GatewayImplBase() {

    companion object : KLogging()

    override fun fetchObject(
        request: GatewayOuterClass.FetchObjectRequest,
        responseObserver: StreamObserver<GatewayOuterClass.FetchObjectResponse>
    ) {
        if (request.params.expiration.isNotSet() || request.params.expiration.toOffsetDateTime().isBefore(OffsetDateTime.now())) {
            logger.info("Request with invalid expiration received")
            throw TimestampValidationException("${if (request.params.expiration.isNotSet()) "Missing" else "Expired"} expiration")
        }

        if (!request.validateSignature()) {
            logger.info("Request with invalid signature received")
            throw SignatureValidationException("Invalid signature")
        }

        scopeFetchService.fetchScope(request.params.scopeAddress, request.signature.signer.signingPublicKey.toPublicKey(), request.params.granterAddress.takeIf { it.isNotBlank() }).let {
            responseObserver.onNext(GatewayOuterClass.FetchObjectResponse.newBuilder()
                .setScopeId(request.params.scopeAddress)
                .addAllRecords(it)
                .build()
            )
        }
        responseObserver.onCompleted()
    }
}
