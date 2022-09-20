package io.provenance.objectstore.gateway.util

import io.grpc.Metadata

fun String.toJwtMeta(): Metadata = Metadata().apply {
    put(Constants.JWT_GRPC_HEADER_KEY, this@toJwtMeta)
}
