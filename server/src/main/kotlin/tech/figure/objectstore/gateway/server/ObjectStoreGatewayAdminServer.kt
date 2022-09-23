package tech.figure.objectstore.gateway.server

import com.google.protobuf.Message
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import io.provenance.scope.encryption.model.KeyRef
import io.provenance.scope.encryption.util.getAddress
import mu.KLogging
import org.lognet.springboot.grpc.GRpcService
import tech.figure.objectstore.gateway.address
import tech.figure.objectstore.gateway.admin.Admin.FetchDataStorageAccountRequest
import tech.figure.objectstore.gateway.admin.Admin.FetchDataStorageAccountResponse
import tech.figure.objectstore.gateway.admin.Admin.PutDataStorageAccountRequest
import tech.figure.objectstore.gateway.admin.Admin.PutDataStorageAccountResponse
import tech.figure.objectstore.gateway.admin.GatewayAdminGrpc.GatewayAdminImplBase
import tech.figure.objectstore.gateway.configuration.ProvenanceProperties
import tech.figure.objectstore.gateway.exception.AccessDeniedException
import tech.figure.objectstore.gateway.exception.NotFoundException
import tech.figure.objectstore.gateway.repository.DataStorageAccountsRepository
import tech.figure.objectstore.gateway.server.interceptor.JwtServerInterceptor

@GRpcService(interceptors = [JwtServerInterceptor::class])
class ObjectStoreGatewayAdminServer(
    private val accountsRepository: DataStorageAccountsRepository,
    private val masterKey: KeyRef,
    private val provenanceProperties: ProvenanceProperties,
) : GatewayAdminImplBase() {
    private companion object : KLogging()

    override fun putDataStorageAccount(
        request: PutDataStorageAccountRequest,
        responseObserver: StreamObserver<PutDataStorageAccountResponse>,
    ) = responseObserver.doAdminOnlyRequest {
        // Update if exists, create if not
        val account = if (accountsRepository.findDataStorageAccountOrNull(request.address, enabledOnly = false) != null) {
            accountsRepository.setStorageAccountEnabled(accountAddress = request.address, enabled = request.enabled)
        } else {
            accountsRepository.addDataStorageAccount(accountAddress = request.address, enabled = request.enabled)
        }
        PutDataStorageAccountResponse.newBuilder().also { response ->
            response.account = account.toProto()
        }.build()
    }

    override fun fetchDataStorageAccount(
        request: FetchDataStorageAccountRequest,
        responseObserver: StreamObserver<FetchDataStorageAccountResponse>,
    ) = responseObserver.doAdminOnlyRequest {
        accountsRepository.findDataStorageAccountOrNull(accountAddress = request.address, enabledOnly = false)
            ?.let { account ->
                FetchDataStorageAccountResponse.newBuilder().also { response ->
                    response.account = account.toProto()
                }.build()
            }
            ?: throw NotFoundException("No account exists for address [${request.address}]")
    }

    private fun <M : Message> StreamObserver<M>.doAdminOnlyRequest(runIfAdmin: () -> M) {
        if (address() != masterKey.publicKey.getAddress(mainNet = provenanceProperties.mainNet)) {
            this.onError(AccessDeniedException("Only the master key may make this request"))
        } else {
            runCatching(runIfAdmin).fold(
                onSuccess = { response ->
                    this.onNext(response)
                    this.onCompleted()
                },
                onFailure = { e ->
                    if (e is StatusRuntimeException) {
                        this.onError(e)
                    } else {
                        logger.error("Unexpected exception thrown by rpc route", e)
                        this.onError(StatusRuntimeException(Status.UNKNOWN.withDescription("An unexpected error occurred")))
                    }
                }
            )
        }
    }
}
