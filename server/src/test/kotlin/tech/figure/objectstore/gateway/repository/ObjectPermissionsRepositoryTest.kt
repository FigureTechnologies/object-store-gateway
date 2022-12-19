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
import tech.figure.objectstore.gateway.model.ObjectPermissionsTable.isObjectWithMeta
import tech.figure.objectstore.gateway.model.ObjectPermissionsTable.storageKeyAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
class ObjectPermissionsRepositoryTest {
    lateinit var repository: ObjectPermissionsRepository

    val obj = randomObject()
    val objectHash = obj.objectBytes.toByteArray().sha256String()
    val objectSizeBytes = obj.toByteArray().size.toLong()
    val granterAddress = ProvenanceKeyGenerator.generateKeyPair().public.getAddress(false)
    val granteeAddress = ProvenanceKeyGenerator.generateKeyPair().public.getAddress(false)
    val masterKey = ProvenanceKeyGenerator.generateKeyPair()
    val masterKeyAddress = masterKey.public.getAddress(false)

    @BeforeEach
    fun setUp() {
        transaction { ObjectPermissionsTable.deleteAll() }
        repository = ObjectPermissionsRepository()
    }

    @Test
    fun `addAccessPermission should create access permission record`() {
        repository.addAccessPermission(objectHash = objectHash, granterAddress = granterAddress, granteeAddress = granteeAddress, storageKeyAddress = masterKeyAddress, objectSizeBytes = objectSizeBytes, isObjectWithMeta = true)

        transaction {
            ObjectPermission.findByObjectHashAndGranteeAddress(objectHash, granteeAddress).also { record ->
                assertNotNull(record)
                assertEquals(granterAddress, record.granterAddress, "The granter address of the record should match")
                assertEquals(objectHash, record.objectHash, "The object hash of the record should match")
                assertEquals(granteeAddress, record.granteeAddress, "The grantee address of the record should match")
                assertEquals(masterKeyAddress, record.storageKeyAddress, "The storage key address should match")
                assertEquals(true, record.isObjectWithMeta, "The object's meta status should match")
            }
        }
    }

    @Test
    fun `hasAccessPermission should return true if permission set up`() {
        transaction {
            ObjectPermission.new(
                objectHash = objectHash,
                granterAddress = granterAddress,
                granteeAddress = granteeAddress,
                storageKeyAddress = masterKeyAddress,
                objectSizeBytes = objectSizeBytes,
                isObjectWithMeta = true
            )
        }

        val hasAccess = repository.hasAccessPermission(objectHash, granteeAddress)

        assertTrue(hasAccess, "The address should have access")
    }

    @Test
    fun `hasAccessPermission should return false if permission not set up`() {
        val hasAccess = repository.hasAccessPermission(objectHash, granteeAddress)

        assertFalse(hasAccess, "The address should not have access")
    }

    @Test
    fun `getAccessPermission should return the access permission if it exists`() {
        transaction {
            ObjectPermission.new(
                objectHash = objectHash,
                granterAddress = granterAddress,
                granteeAddress = granteeAddress,
                storageKeyAddress = masterKeyAddress,
                objectSizeBytes = objectSizeBytes,
                isObjectWithMeta = true
            )
        }

        val accessPermission = repository.getAccessPermission(objectHash, granteeAddress)

        assertEquals(objectHash, accessPermission?.objectHash, "The access permission should be returned with the proper hash")
        assertEquals(granterAddress, accessPermission?.granterAddress, "The access permission should be returned with the proper granter")
        assertEquals(granteeAddress, accessPermission?.granteeAddress, "The access permission should be returned with the proper grantee")
        assertEquals(masterKeyAddress, accessPermission?.storageKeyAddress, "The access permission should be returned with the proper key address")
        assertEquals(objectSizeBytes, accessPermission?.objectSizeBytes, "The access permission should be returned with the proper object size")
        assertEquals(true, accessPermission?.isObjectWithMeta, "The access permission should be returned with the proper object meta status")
    }

    @Test
    fun `getAccessPermission should return null if the access permission does not exist`() {
        val accessPermission = repository.getAccessPermission(objectHash, granteeAddress)

        assertNull(accessPermission, "The address should not return an access permission")
    }
}
