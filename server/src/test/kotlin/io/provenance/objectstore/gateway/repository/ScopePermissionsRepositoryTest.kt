package io.provenance.objectstore.gateway.repository

import io.provenance.objectstore.gateway.configuration.DataMigration
import io.provenance.objectstore.gateway.model.ScopePermission
import io.provenance.objectstore.gateway.model.ScopePermissionsTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.asserter

@SpringBootTest
class ScopePermissionsRepositoryTest {
    lateinit var repository: ScopePermissionsRepository

    @Autowired
    lateinit var dataMigration: DataMigration

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

        val record = transaction { ScopePermission.findByScopeIdAndAddresses(scopeAddress, granteeAddress, granterAddress) }

        assertNotNull(record, "The scope permission record should be created")
    }

    @Test
    fun `getAccessGranterAddress should return null if there is no permission set up`() {
        val address = transaction { repository.getAccessGranterAddress(scopeAddress, granteeAddress, granterAddress) }

        assertNull(address, "There should be no granter address when access has not been granted")
    }

    @Test
    fun `getAccessGranterAddress should return null if there is not a permission record for the specified granter`() {
        transaction { ScopePermission.new(scopeAddress, granteeAddress, granterAddress) }

        val address = transaction { repository.getAccessGranterAddress(scopeAddress, granteeAddress, otherGranterAddress) }

        assertNull(address, "There should be no granter address when access has not been granted for the specified granter")
    }

    @Test
    fun `getAccessGranterAddress should return the specified granter address if access has been granted`() {
        transaction { ScopePermission.new(scopeAddress, granteeAddress, granterAddress) }

        val address = transaction { repository.getAccessGranterAddress(scopeAddress, granteeAddress, granterAddress) }

        assertEquals(granterAddress, address, "The provided address should be return if access is granted")
    }

    @Test
    fun `getAccessGranterAddress should return an address when access is set up but no granter is specified`() {
        transaction {
            ScopePermission.new(scopeAddress, granteeAddress, granterAddress)
            ScopePermission.new(scopeAddress, granteeAddress, otherGranterAddress)
        }

        val address = transaction { repository.getAccessGranterAddress(scopeAddress, granteeAddress, null) }

        assertContains(listOf(granterAddress, otherGranterAddress), address, "One of the expected addresses should be returned when access is granted but no granter is specified")
    }
}
