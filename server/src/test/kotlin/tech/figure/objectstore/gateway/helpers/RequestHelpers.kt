package tech.figure.objectstore.gateway.helpers

import io.provenance.scope.util.toByteString
import tech.figure.objectstore.gateway.GatewayOuterClass
import kotlin.random.Random

fun getValidRequest(granterAddress: String? = null) = GatewayOuterClass.FetchObjectRequest.newBuilder()
    .setScopeAddress("myCoolScope")
    .apply {
        granterAddress?.also { setGranterAddress(it) }
    }
    .build()

fun getValidPutObjectRequest(type: String? = null, size: Int = 100) = GatewayOuterClass.PutObjectRequest.newBuilder()
    .setObject(randomObject(type))
    .build()

fun getValidFetchObjectByHashRequest(hash: String) = GatewayOuterClass.FetchObjectByHashRequest.newBuilder()
    .setHash(hash)
    .build()

fun randomObject(objectType: String? = null, size: Int = 100) = objectFromParts(Random.nextBytes(size), objectType)

fun objectFromParts(objectBytes: ByteArray, objectType: String? = null) = GatewayOuterClass.ObjectWithMeta.newBuilder()
    .setObjectBytes(objectBytes.toByteString())
    .apply {
        if (objectType != null) {
            type = objectType
        }
    }
    .build()
