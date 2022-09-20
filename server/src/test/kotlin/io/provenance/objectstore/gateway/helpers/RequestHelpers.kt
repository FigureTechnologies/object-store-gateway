package io.provenance.objectstore.gateway.helpers

import io.provenance.objectstore.gateway.GatewayOuterClass
import io.provenance.scope.util.toByteString
import kotlin.random.Random

fun getValidRequest(granterAddress: String? = null) = GatewayOuterClass.FetchObjectRequest.newBuilder()
    .setScopeAddress("myCoolScope")
    .apply {
        granterAddress?.also { setGranterAddress(it) }
    }
    .build()

fun getValidPutObjectRequest(type: String? = null, size: Int = 100) = GatewayOuterClass.PutObjectRequest.newBuilder()
    .setObjectBytes(Random.nextBytes(size).toByteString())
    .apply {
        if (type != null) {
            setType(type)
        }
    }
    .build()

fun getValidFetchObjectByHashRequest(hash: String) = GatewayOuterClass.FetchObjectByHashRequest.newBuilder()
    .setHash(hash)
    .build()
