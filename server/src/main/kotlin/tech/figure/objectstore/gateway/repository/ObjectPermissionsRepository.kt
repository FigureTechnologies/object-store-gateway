package tech.figure.objectstore.gateway.repository

import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Repository
import tech.figure.objectstore.gateway.model.ObjectPermission
import tech.figure.objectstore.gateway.model.ObjectPermissionsTable.isObjectWithMeta

@Repository
class ObjectPermissionsRepository {
    fun addAccessPermission(objectHash: String, granterAddress: String, granteeAddress: String, storageKeyAddress: String, objectSizeBytes: Long, isObjectWithMeta: Boolean) {
        transaction {
            ObjectPermission.new(
                objectHash = objectHash,
                granterAddress = granterAddress,
                granteeAddress = granteeAddress,
                storageKeyAddress = storageKeyAddress,
                objectSizeBytes = objectSizeBytes,
                isObjectWithMeta = isObjectWithMeta
            )
        }
    }

    fun hasAccessPermission(objectHash: String, granteeAddress: String): Boolean = getAccessPermission(objectHash, granteeAddress) != null

    fun getAccessPermission(objectHash: String, granteeAddress: String): ObjectPermission? = transaction {
        ObjectPermission.findByObjectHashAndGranteeAddress(objectHash, granteeAddress)
    }

    fun getAccessPermissionsForGranter(objectHash: String, granterAddress: String): List<ObjectPermission> = transaction {
        ObjectPermission.findByObjectHashAndGranterAddress(objectHash = objectHash, granterAddress = granterAddress)
    }

    fun revokeAccessPermissions(objectHash: String, granterAddress: String, granteeAddresses: List<String>) = transaction {
        ObjectPermission.deleteByObjectHashGranterAndGranteeAddresses(objectHash, granterAddress, granteeAddresses)
    }

    fun getAccessPermissionsForGranterByHashes(objectHashes: Collection<String>, granterAddress: String): Map<String, List<ObjectPermission>> = transaction {
        ObjectPermission.findByObjectHashesAndGranterAddress(objectHashes = objectHashes, granterAddress = granterAddress)
    }

    fun getAllGranterHashes(granterAddress: String, excludedGrantees: Collection<String> = emptyList()): Set<String> = transaction {
        ObjectPermission.findHashesByGranterAddress(granterAddress = granterAddress, excludedGrantees = excludedGrantees)
    }
}
