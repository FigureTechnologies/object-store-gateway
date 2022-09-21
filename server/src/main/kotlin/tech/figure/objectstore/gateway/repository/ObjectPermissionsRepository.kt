package tech.figure.objectstore.gateway.repository

import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Component
import tech.figure.objectstore.gateway.model.ObjectPermission

@Component
class ObjectPermissionsRepository {
    fun addAccessPermission(objectHash: String, granteeAddress: String, objectSize: Long) {
        transaction { ObjectPermission.new(objectHash, granteeAddress, objectSize) }
    }

    fun hasAccessPermission(objectHash: String, granteeAddress: String): Boolean = transaction {
        ObjectPermission.findByObjectHashAndAddress(objectHash, granteeAddress) != null
    }
}
