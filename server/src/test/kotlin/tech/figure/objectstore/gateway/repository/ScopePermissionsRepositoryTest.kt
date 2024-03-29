package tech.figure.objectstore.gateway.repository

import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import tech.figure.objectstore.gateway.model.ScopePermission
import tech.figure.objectstore.gateway.model.ScopePermissionsTable
import kotlin.test.assertEquals

@SpringBootTest
class ScopePermissionsRepositoryTest {
    lateinit var repository: ScopePermissionsRepository

    val scopeAddress = "myCoolScope"
    val granteeAddress = "granteeIsMe"
    val granterAddress = "granterOfYourDreams"
    val otherGranterAddress = "granterOfYourNightmares"

    @BeforeEach
    fun setUp() {
        transaction { ScopePermissionsTable.deleteAll() }
        repository = ScopePermissionsRepository()
    }

    @Test
    fun `addAccessPermission should create access permission record`() {
        repository.addAccessPermission(scopeAddress, granteeAddress, granterAddress)

        transaction {
            ScopePermission.findAllByScopeIdAndGranteeAddress(scopeAddress, granteeAddress).also { records ->
                assertEquals(1, records.count(), "There should be only one record created")
                assertEquals(records.first().scopeAddress, scopeAddress, "The scope permission record should have the proper scope address")
                assertEquals(records.first().granterAddress, granterAddress, "The scope permission record should have the proper granter")
                assertEquals(records.first().granteeAddress, granteeAddress, "The scope permission record should have the proper grantee")
                assertEquals(records.first().grantId, null, "The scope permission record should have a null grant id by default")
            }
        }
    }

    @Test
    fun `addAccessPermission should not insert duplicate records when a null grantId is supplied`() {
        repository.addAccessPermission(scopeAddress, granteeAddress, granterAddress)
        repository.addAccessPermission(scopeAddress, granteeAddress, granterAddress)
        transaction {
            ScopePermission.findAllByScopeIdAndGranteeAddress(scopeAddress, granteeAddress).also { records ->
                assertEquals(
                    expected = 1,
                    actual = records.count(),
                    message = "Duplicate records with null grant ids should be ignored and only one copy of the record should be inserted",
                )
            }
        }
    }

    @Test
    fun `getAccessGranterAddresses should return an empty list if there is no permission set up`() {
        val addresses = transaction { repository.getAccessGranterAddresses(scopeAddress, granteeAddress) }

        assertEquals(0, addresses.size, "There should be no granter address when access has not been granted")
    }

    @Test
    fun `getAccessGranterAddresses should return the specified granter address if access has been granted`() {
        transaction { ScopePermission.new(scopeAddress, granteeAddress, granterAddress) }

        val addresses = transaction { repository.getAccessGranterAddresses(scopeAddress, granteeAddress) }

        assertEquals(1, addresses.size, "There should only be one address returned when there is only one set up")
        assertEquals(granterAddress, addresses.first(), "The provided address should be return if access is granted")
    }

    @Test
    fun `getAccessGranterAddresses should return all granter addresses when access is set up but no granter is specified`() {
        transaction {
            ScopePermission.new(scopeAddress, granteeAddress, granterAddress)
            ScopePermission.new(scopeAddress, granteeAddress, otherGranterAddress)
        }

        val addresses = transaction { repository.getAccessGranterAddresses(scopeAddress, granteeAddress) }

        assertEquals(listOf(granterAddress, otherGranterAddress).sorted(), addresses.sorted(), "All expected addresses should be returned when access is granted by different granters")
    }
}
