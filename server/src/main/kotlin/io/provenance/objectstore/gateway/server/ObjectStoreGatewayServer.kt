package io.provenance.objectstore.gateway.server

import com.google.protobuf.ByteString
import com.google.protobuf.Timestamp
import io.grpc.stub.StreamObserver
import io.provenance.client.protobuf.extensions.isNotSet
import io.provenance.objectstore.gateway.GatewayGrpc
import io.provenance.objectstore.gateway.GatewayOuterClass
import io.provenance.objectstore.gateway.configuration.ProvenanceProperties
import io.provenance.objectstore.gateway.exception.AccessDeniedException
import io.provenance.objectstore.gateway.exception.SignatureValidationException
import io.provenance.objectstore.gateway.exception.TimestampValidationException
import io.provenance.objectstore.gateway.repository.permissions.ScopePermissionsRepository
import io.provenance.objectstore.gateway.service.ScopeFetchService
import io.provenance.objectstore.gateway.util.toByteString
import io.provenance.objectstore.gateway.util.validateSignature
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
): GatewayGrpc.GatewayImplBase() {

    companion object : KLogging()

    override fun fetchObject(
        request: GatewayOuterClass.FetchObjectRequest,
        responseObserver: StreamObserver<GatewayOuterClass.FetchObjectResponse>
    ) {
        val requesterPublicKey = request.signature.publicKey.toPublicKey()

        if (request.params.expiration.isNotSet() || request.params.expiration.toOffsetDateTime().isBefore(OffsetDateTime.now())) {
            logger.info("Request with invalid expiration received")
            responseObserver.onError(TimestampValidationException())
        } else if (!request.validateSignature()) {
            logger.info("Request with invalid signature received")
            responseObserver.onError(SignatureValidationException())
        } else if (!scopePermissionsRepository.hasAccessPermission(request.params.scopeAddress, requesterPublicKey.getAddress(provenanceProperties.mainNet))) {
            logger.info("Request for scope data without access received")
            responseObserver.onError(AccessDeniedException())
        } else {
            logger.info("Valid request for scope data received")
            scopeFetchService.fetchScope(request.params.scopeAddress).let {
                responseObserver.onNext(GatewayOuterClass.FetchObjectResponse.newBuilder()
                    .setScopeId(request.params.scopeAddress)
                    .addAllRecords(it)
                    .build()
                )
            }
            responseObserver.onCompleted()
        }

    }
}
