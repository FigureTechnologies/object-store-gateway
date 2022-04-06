package io.provenance.objectstore.gateway.helpers

import io.provenance.objectstore.gateway.GatewayOuterClass
import io.provenance.scope.encryption.crypto.SignerImpl
import io.provenance.scope.encryption.model.DirectKeyRef
import io.provenance.scope.util.toProtoTimestamp
import java.security.KeyPair
import java.time.OffsetDateTime

fun KeyPair.getValidRequest(expirationSeconds: Long = 1000, granterAddress: String? = null): GatewayOuterClass.FetchObjectRequest {
    val params = GatewayOuterClass.FetchObjectParams.newBuilder()
        .setScopeAddress("myCoolScope")
        .setExpiration(OffsetDateTime.now().plusSeconds(expirationSeconds).toProtoTimestamp())
        .apply {
            granterAddress?.also { setGranterAddress(it) }
        }
        .build()

    val signer = DirectKeyRef(this). signer().apply {
        deterministic = true
        hashType = SignerImpl.Companion.HashType.SHA512
    }
    val signature = signer.sign(params)

    return GatewayOuterClass.FetchObjectRequest.newBuilder()
        .setParams(params)
        .setSignature(signature)
        .build()
}
