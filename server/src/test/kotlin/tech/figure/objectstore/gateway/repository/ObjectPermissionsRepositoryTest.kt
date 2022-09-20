package tech.figure.objectstore.gateway.repository

import io.provenance.scope.encryption.ecies.ProvenanceKeyGenerator
import io.provenance.scope.encryption.util.getAddress
import io.provenance.scope.util.sha256String
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import tech.figure.objectstore.gateway.helpers.randomObject
import tech.figure.objectstore.gateway.model.ObjectPermission
import tech.figure.objectstore.gateway.model.ObjectPermissionsTable
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
class ObjectPermissionsRepositoryTest {
    lateinit var repository: ObjectPermissionsRepository

    val obj = randomObject()
    val objectHash = obj.objectBytes.toByteArray().sha256String()
    val objectSize = obj.toByteArray().size.toLong()
    val granteeAddress = ProvenanceKeyGenerator.generateKeyPair().public.getAddress(false)

    @BeforeEach
    fun setUp() {
        transaction { ObjectPermissionsTable.deleteAll() }
        repository = ObjectPermissionsRepository()
    }

    @Test
    fun `addAccessPermission should create access permission record`() {
        repository.addAccessPermission(objectHash, granteeAddress, objectSize)

        transaction {
            ObjectPermission.findByObjectHashAndAddress(objectHash, granteeAddress).also { record ->
                assertNotNull(record)
                assertEquals(objectHash, record.objectHash, "The object hash of the record should match")
                assertEquals(granteeAddress, record.granteeAddress, "The grantee address of the record should match")
            }
        }
    }

    @Test
    fun `hasAccessPermission should return true if permission set up`() {
        transaction { ObjectPermission.new(objectHash, granteeAddress, objectSize) }

        val hasAccess = repository.hasAccessPermission(objectHash, granteeAddress)

        assertTrue(hasAccess, "The address should have access")
    }

    @Test
    fun `hasAccessPermission should return false if permission not set up`() {
        val hasAccess = repository.hasAccessPermission(objectHash, granteeAddress)

        assertFalse(hasAccess, "The address should have access")
    }
}
