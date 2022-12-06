package tech.figure.objectstore.gateway.client

import tech.figure.objectstore.gateway.GatewayOuterClass.ScopeGrantee

/**
 * A simple helper utility that facilitates the building of protos used in the GatewayClient.  This abstracts some of
 * the verbosity away from callers.
 */
object GatewayProtoUtil {
    /**
     * Builds a ScopeGrantee proto with the given parameters.
     *
     * @param granteeAddress The bech32 address of the account to receive access to a given scope.
     * @param grantId An optional string qualifier to use as an identifier for the grant record created in the system.
     * This can later be referenced in calls to GatewayClient.revokeScopePermission to directly remove the grant created
     * for this grantee.
     */
    fun buildScopeGrantee(
        granteeAddress: String,
        grantId: String? = null,
    ): ScopeGrantee = ScopeGrantee.newBuilder().also { grantee ->
        grantee.granteeAddress = granteeAddress
        grantId?.also { grantee.grantId = it }
    }.build()
}
