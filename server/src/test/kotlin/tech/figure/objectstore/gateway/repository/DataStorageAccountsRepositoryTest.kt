package tech.figure.objectstore.gateway.repository

import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import tech.figure.objectstore.gateway.model.DataStorageAccountsTable
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
class DataStorageAccountsRepositoryTest {
    lateinit var repository: DataStorageAccountsRepository

    @BeforeEach
    fun setUp() {
        transaction { DataStorageAccountsTable.deleteAll() }
        repository = DataStorageAccountsRepository()
    }

    @Test
    fun `addDataStorageAccount should create data storage account record`() {
        val beforeInsert = OffsetDateTime.now()
        val accountAddress = "account address yolo"
        repository.addDataStorageAccount(accountAddress)

        repository.findDataStorageAccountOrNull(accountAddress = accountAddress).also { account ->
            assertNotNull(
                actual = account,
                message = "The account should be accessible by its address",
            )
            assertEquals(
                expected = accountAddress,
                actual = account.accountAddress,
                message = "The accountAddress value should properly return the primary key account address",
            )
            assertTrue(
                actual = account.enabled,
                message = "The account should be enabled by default",
            )
            assertFalse(
                actual = beforeInsert.isAfter(account.created),
                message = "The timestamp created before the insert [$beforeInsert] should not be after the created timestamp [${account.created}]",
            )
        }
    }

    @Test
    fun `addDataStorageAccount returns existing value instead of creating a new one`() {
        val accountAddress = "dank address"
        val account = repository.addDataStorageAccount(accountAddress)
        // Sleep for a millisecond to ensure that it's impossible for a new record to not have a new created timestamp
        Thread.sleep(1)
        val secondAccountMaybe = repository.addDataStorageAccount(accountAddress)
        assertEquals(
            expected = account.accountAddress,
            actual = secondAccountMaybe.accountAddress,
            message = "Account addresses should match",
        )
        assertEquals(
            expected = account.enabled,
            actual = secondAccountMaybe.enabled,
            message = "Enabled values should match",
        )
        assertEquals(
            expected = account.created,
            actual = secondAccountMaybe.created,
            message = "Created timestamps should match",
        )
    }

    @Test
    fun `findDataStorageAccountOrNull regards the enabled flag`() {
        assertNull(
            actual = repository.findDataStorageAccountOrNull(accountAddress = "some nonexistent addr", enabledOnly = false),
            message = "Null should be returned when querying for a nonexistent account",
        )
        val accountAddress = "heck-yeah-address"
        val account = repository.addDataStorageAccount(accountAddress, enabled = false)
        assertFalse(
            actual = account.enabled,
            message = "Sanity check: The account should be created as a disabled account",
        )
        assertNull(
            actual = repository.findDataStorageAccountOrNull(accountAddress = accountAddress, enabledOnly = true),
            message = "When only returning enabled records, findDataStorageAccountOrNull should return null",
        )
        assertEquals(
            expected = account,
            actual = repository.findDataStorageAccountOrNull(accountAddress = accountAddress, enabledOnly = false),
            message = "When returning all records, the disabled account should be found",
        )
    }

    @Test
    fun `setStorageAccountEnabled throws exception when the target account does not exist`() {
        assertFailsWith<IllegalStateException>("An exception should be thrown when the target account does not exist") {
            repository.setStorageAccountEnabled("someaddr", false)
        }
    }

    @Test
    fun `setStorageAccountEnabled successfully changes the target account enabled value`() {
        val account = repository.addDataStorageAccount("myaccount")
        assertTrue(
            actual = account.enabled,
            message = "Sanity check: Newly-created accounts should always be enabled",
        )
        val updatedAccount = repository.setStorageAccountEnabled(accountAddress = account.accountAddress, enabled = false)
        assertFalse(
            actual = updatedAccount.enabled,
            message = "The updated account should be set to enabled = false",
        )
        val updatedAccountAgain = repository.setStorageAccountEnabled(accountAddress = account.accountAddress, enabled = true)
        assertTrue(
            actual = updatedAccountAgain.enabled,
            message = "The updated account should be set to enabled = true",
        )
    }

    @Test
    fun `isAddressEnabled returns the proper value`() {
        assertFalse(
            actual = repository.isAddressEnabled("some fake account"),
            message = "False should be returned when the target account does not exist",
        )
        val account = repository.addDataStorageAccount("accountsorwhatever")
        assertTrue(
            actual = repository.isAddressEnabled(account.accountAddress),
            message = "All new accounts should be set to enabled",
        )
        repository.setStorageAccountEnabled(accountAddress = account.accountAddress, enabled = false)
        assertFalse(
            actual = repository.isAddressEnabled(account.accountAddress),
            message = "The account should be enabled = false after changing the enabled status",
        )
    }
}
