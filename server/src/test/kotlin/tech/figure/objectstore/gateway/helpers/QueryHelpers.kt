package tech.figure.objectstore.gateway.helpers

import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import tech.figure.objectstore.gateway.model.ScopePermissionsTable

fun queryGrantCount(
    scopeAddr: String,
    grantee: String,
    granter: String,
    grantId: String? = null,
): Long = transaction {
    ScopePermissionsTable.select {
        ScopePermissionsTable.scopeAddress.eq(scopeAddr)
            .and { ScopePermissionsTable.granteeAddress eq grantee }
            .and { ScopePermissionsTable.granterAddress eq granter }
            .and { ScopePermissionsTable.grantId eq grantId }
    }.count()
}

fun queryGrantCount(
    scopeAddr: String,
    grantee: String,
): Long = transaction {
    ScopePermissionsTable.select {
        ScopePermissionsTable.scopeAddress.eq(scopeAddr)
            .and { ScopePermissionsTable.granteeAddress eq grantee }
    }.count()
}
