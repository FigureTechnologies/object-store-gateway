package io.provenance.objectstore.gateway.repository

import io.provenance.objectstore.gateway.model.ObjectPermission
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Component

@Component
class ObjectPermissionsRepository {
    fun addAccessPermission(objectHash: String, granteeAddress: String, objectSize: Long) {
        transaction { ObjectPermission.new(objectHash, granteeAddress, objectSize) }
    }

    fun hasAccessPermission(objectHash: String, granteeAddress: String): Boolean = transaction {
        ObjectPermission.findByObjectHashAndAddress(objectHash, granteeAddress) != null
    }
}
