package io.provenance.objectstore.gateway.helpers

import io.provenance.objectstore.gateway.GatewayOuterClass
import io.provenance.scope.encryption.crypto.SignerImpl
import io.provenance.scope.encryption.model.DirectKeyRef
import io.provenance.scope.util.toProtoTimestamp
import java.security.KeyPair
import java.time.OffsetDateTime

fun getValidRequest(granterAddress: String? = null) = GatewayOuterClass.FetchObjectRequest.newBuilder()
    .setScopeAddress("myCoolScope")
    .apply {
        granterAddress?.also { setGranterAddress(it) }
    }
    .build()
