package tech.figure.objectstore.gateway.server

import com.google.protobuf.Message
import io.grpc.stub.StreamObserver
import io.provenance.scope.encryption.model.KeyRef
import io.provenance.scope.encryption.util.getAddress
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
    override fun putDataStorageAccount(
        request: PutDataStorageAccountRequest,
        responseObserver: StreamObserver<PutDataStorageAccountResponse>,
    ) {
        if (responseObserver.isRequestNotMasterKey()) {
            responseObserver.sendAccessDeniedException()
            return
        }
        // Update if exists, create if not
        val account = if (accountsRepository.findDataStorageAccountOrNull(request.address, enabledOnly = false) != null) {
            accountsRepository.setStorageAccountEnabled(accountAddress = request.address, enabled = request.enabled)
        } else {
            accountsRepository.addDataStorageAccount(accountAddress = request.address, enabled = request.enabled)
        }
        responseObserver.onNext(
            PutDataStorageAccountResponse.newBuilder().also { response ->
                response.account = account.toProto()
            }.build()
        )
        responseObserver.onCompleted()
    }

    override fun fetchDataStorageAccount(
        request: FetchDataStorageAccountRequest,
        responseObserver: StreamObserver<FetchDataStorageAccountResponse>,
    ) {
        if (responseObserver.isRequestNotMasterKey()) {
            responseObserver.sendAccessDeniedException()
            return
        }
        accountsRepository.findDataStorageAccountOrNull(accountAddress = request.address, enabledOnly = false)
            ?.also { account ->
                responseObserver.onNext(
                    FetchDataStorageAccountResponse.newBuilder().also { response ->
                        response.account = account.toProto()
                    }.build()
                )
                responseObserver.onCompleted()
            }
            ?: run {
                responseObserver.onError(NotFoundException("No account exists for address [${request.address}]"))
            }
    }

    private fun <M : Message> StreamObserver<M>.isRequestNotMasterKey(): Boolean =
        address() != masterKey.publicKey.getAddress(mainNet = provenanceProperties.mainNet)

    private fun <M : Message> StreamObserver<M>.sendAccessDeniedException() {
        this.onError(AccessDeniedException("Only the master key may make this request"))
    }
}
