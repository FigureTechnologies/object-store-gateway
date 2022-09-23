package tech.figure.objectstore.gateway.server

import io.grpc.Context
import io.grpc.stub.StreamObserver
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyAll
import io.provenance.hdwallet.ec.extensions.toJavaECPublicKey
import io.provenance.hdwallet.wallet.Account
import io.provenance.scope.encryption.util.getAddress
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.springframework.boot.test.context.SpringBootTest
import tech.figure.objectstore.gateway.admin.Admin.FetchDataStorageAccountRequest
import tech.figure.objectstore.gateway.admin.Admin.FetchDataStorageAccountResponse
import tech.figure.objectstore.gateway.admin.Admin.PutDataStorageAccountRequest
import tech.figure.objectstore.gateway.admin.Admin.PutDataStorageAccountResponse
import tech.figure.objectstore.gateway.configuration.ProvenanceProperties
import tech.figure.objectstore.gateway.exception.AccessDeniedException
import tech.figure.objectstore.gateway.exception.NotFoundException
import tech.figure.objectstore.gateway.helpers.createErrorSlot
import tech.figure.objectstore.gateway.helpers.genRandomAccount
import tech.figure.objectstore.gateway.helpers.keyRef
import tech.figure.objectstore.gateway.helpers.mockkObserver
import tech.figure.objectstore.gateway.model.DataStorageAccountsTable
import tech.figure.objectstore.gateway.repository.DataStorageAccountsRepository
import java.security.PublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

@SpringBootTest
class ObjectStoreGatewayAdminServerTest {
    lateinit var accountsRepository: DataStorageAccountsRepository
    lateinit var provenanceProperties: ProvenanceProperties
    val masterAccount: Account = genRandomAccount()

    lateinit var server: ObjectStoreGatewayAdminServer

    private companion object {
        const val DEFAULT_ACCOUNT: String = "default-account"
    }

    @BeforeEach
    fun clearDb() {
        transaction { DataStorageAccountsTable.deleteAll() }
    }

    fun setUp(requestorPublicKey: PublicKey = masterAccount.keyPair.publicKey.toJavaECPublicKey()) {
        accountsRepository = DataStorageAccountsRepository()
        provenanceProperties = mockk()
        every { provenanceProperties.mainNet } returns false
        Context.current()
            .withValue(Constants.REQUESTOR_PUBLIC_KEY_CTX, requestorPublicKey)
            .withValue(Constants.REQUESTOR_ADDRESS_CTX, requestorPublicKey.getAddress(false))
            .attach()
        server = ObjectStoreGatewayAdminServer(
            accountsRepository = accountsRepository,
            masterKey = masterAccount.keyRef,
            provenanceProperties = provenanceProperties,
        )
    }

    @Test
    fun `putDataStorageAccount is rejected when the requestor is not the master key`() {
        setUp(genRandomAccount().keyPair.publicKey.toJavaECPublicKey())
        val observer = mockkObserver<PutDataStorageAccountResponse>()
        val exceptionSlot = observer.createErrorSlot<AccessDeniedException>()
        observer.putDataStorageAccount()
        verifyAll(inverse = true) {
            observer.onNext(any())
            observer.onCompleted()
        }
        assertTrue(
            actual = "Only the master key may make this request" in (
                exceptionSlot.captured.message
                    ?: fail("Message should be set on the captured exception")
                ),
            message = "The proper message should be included in the expected exception, but got: ${exceptionSlot.captured.message}",
        )
    }

    @Test
    fun `putDataStorageAccount creates an account when one does not yet exist`() {
        setUp()
        assertNull(
            actual = accountsRepository.findDataStorageAccountOrNull(DEFAULT_ACCOUNT, enabledOnly = false),
            message = "Sanity check: No account with the default address should exist before operations occur",
        )
        val observer = mockkObserver<PutDataStorageAccountResponse>()
        observer.putDataStorageAccount()
        val account = accountsRepository.findDataStorageAccountOrNull(DEFAULT_ACCOUNT)
        assertNotNull(
            actual = account,
            message = "A data storage account should be created by the function",
        )
        assertEquals(
            expected = account.accountAddress,
            actual = DEFAULT_ACCOUNT,
            message = "The default account address should be used",
        )
        assertTrue(
            actual = account.enabled,
            message = "The account should be initially set to enabled",
        )
        verify(inverse = true) { observer.onError(any()) }
        verifyAll {
            observer.onNext(PutDataStorageAccountResponse.newBuilder().setAccount(account.toProto()).build())
            observer.onCompleted()
        }
    }

    @Test
    fun `putDataStorageAccount updates an existing account when it is targeted`() {
        setUp()
        accountsRepository.addDataStorageAccount(accountAddress = DEFAULT_ACCOUNT, enabled = true)
        assertEquals(
            expected = true,
            actual = accountsRepository.findDataStorageAccountOrNull(DEFAULT_ACCOUNT, enabledOnly = false)?.enabled,
            message = "Sanity check: The account should be in the database with an enabled value of true",
        )
        val observer = mockkObserver<PutDataStorageAccountResponse>()
        observer.putDataStorageAccount(account = DEFAULT_ACCOUNT, enabled = false)
        val account = accountsRepository.findDataStorageAccountOrNull(DEFAULT_ACCOUNT, enabledOnly = false)
        assertNotNull(
            actual = account,
            message = "After an update, the target account should still exist in the database",
        )
        assertFalse(
            actual = account.enabled,
            message = "The update should have altered the enabled value from true to false",
        )
        verify(inverse = true) { observer.onError(any()) }
        verifyAll {
            observer.onNext(PutDataStorageAccountResponse.newBuilder().setAccount(account.toProto()).build())
            observer.onCompleted()
        }
    }

    @Test
    fun `fetchDataStorageAccount is rejected when the requestor is not the master key`() {
        setUp(genRandomAccount().keyPair.publicKey.toJavaECPublicKey())
        val observer = mockkObserver<FetchDataStorageAccountResponse>()
        val exceptionSlot = observer.createErrorSlot<AccessDeniedException>()
        observer.fetchDataStorageAccount()
        verifyAll(inverse = true) {
            observer.onNext(any())
            observer.onCompleted()
        }
        assertTrue(
            actual = "Only the master key may make this request" in (
                exceptionSlot.captured.message
                    ?: fail("Message should be set on the captured exception")
                ),
            message = "The proper message should be included in the expected exception, but got: ${exceptionSlot.captured.message}",
        )
    }

    @Test
    fun `fetchDataStorageAccount returns a NotFoundException when no account exists for the given address`() {
        setUp()
        val observer = mockkObserver<FetchDataStorageAccountResponse>()
        val exceptionSlot = observer.createErrorSlot<NotFoundException>()
        observer.fetchDataStorageAccount()
        verifyAll(inverse = true) {
            observer.onNext(any())
            observer.onCompleted()
        }
        assertTrue(
            actual = "No account exists for address [$DEFAULT_ACCOUNT]" in (
                exceptionSlot.captured.message
                    ?: fail("Message should be set on the captured exception")
                ),
            message = "The proper message should be included in the expected exception, but got: ${exceptionSlot.captured.message}",
        )
    }

    @Test
    fun `fetchDataStorageAccount returns the account if it exists and is enabled`() {
        doSuccessfulFetchTest(accountEnabled = true)
    }

    @Test
    fun `fetchDataStorageAccount returns the account if it exists and is disabled`() {
        doSuccessfulFetchTest(accountEnabled = false)
    }

    private fun doSuccessfulFetchTest(accountEnabled: Boolean) {
        setUp()
        val account = accountsRepository.addDataStorageAccount(DEFAULT_ACCOUNT, enabled = accountEnabled)
        assertEquals(
            expected = accountEnabled,
            actual = account.enabled,
            message = "Sanity check: The account should be ${if (accountEnabled) "enabled" else "disabled"} after creation",
        )
        val observer = mockkObserver<FetchDataStorageAccountResponse>()
        observer.fetchDataStorageAccount()
        verify(inverse = true) { observer.onError(any()) }
        verifyAll {
            observer.onNext(FetchDataStorageAccountResponse.newBuilder().setAccount(account.toProto()).build())
            observer.onCompleted()
        }
    }

    private fun StreamObserver<PutDataStorageAccountResponse>.putDataStorageAccount(
        account: String = DEFAULT_ACCOUNT,
        enabled: Boolean = true,
    ) {
        server.putDataStorageAccount(
            request = PutDataStorageAccountRequest.newBuilder().setAddress(account).setEnabled(enabled).build(),
            responseObserver = this,
        )
    }

    private fun StreamObserver<FetchDataStorageAccountResponse>.fetchDataStorageAccount(
        account: String = DEFAULT_ACCOUNT,
    ) {
        server.fetchDataStorageAccount(
            request = FetchDataStorageAccountRequest.newBuilder().setAddress(account).build(),
            responseObserver = this,
        )
    }
}
