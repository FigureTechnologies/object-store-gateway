package tech.figure.objectstore.gateway.helpers

import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import tech.figure.objectstore.gateway.model.ScopePermissionsTable
import java.sql.ResultSet

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
    grantId: String? = null,
): Long = transaction {
    ScopePermissionsTable.select {
        ScopePermissionsTable.scopeAddress.eq(scopeAddr)
            .and { ScopePermissionsTable.granteeAddress eq grantee }
            .let { query ->
                if (grantId != null) {
                    query.and { ScopePermissionsTable.grantId.eq(grantId) }
                } else {
                    query
                }
            }
    }.count()
}

/**
 * Easy way to execute a SQL update in-place anywhere in an int test.
 * This could potentially allow sql injection, so don't go replicatin' it in some production code, now, y'hear!?
 */
fun runRawSqlUpdate(sql: String): Int = transaction {
    val connection = TransactionManager.current().connection
    val statement = connection.prepareStatement(sql, false)
    statement.executeUpdate()
}

/**
 * Easy way to execute a SQL query in-place anywhere in an int test.
 * This could potentially allow sql injection, so don't go replicatin' it in some production code!!!!!!!!!!AHHHHH!!!!
 *
 * @param useResultSet The ResultSet is closed after the transaction exits, so this function allows the values to be
 * consumed before the transaction is closed.
 */
fun runRawSqlQuery(sql: String, useResultSet: (ResultSet) -> Unit = {}) = transaction {
    val connection = TransactionManager.current().connection
    val statement = connection.prepareStatement(sql, false)
    statement.executeQuery().also(useResultSet)
}
