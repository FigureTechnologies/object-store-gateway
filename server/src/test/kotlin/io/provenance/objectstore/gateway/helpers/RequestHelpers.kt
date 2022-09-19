package io.provenance.objectstore.gateway.helpers

import io.provenance.objectstore.gateway.GatewayOuterClass

fun getValidRequest(granterAddress: String? = null) = GatewayOuterClass.FetchObjectRequest.newBuilder()
    .setScopeAddress("myCoolScope")
    .apply {
        granterAddress?.also { setGranterAddress(it) }
    }
    .build()
