package io.provenance.objectstore.gateway.client

import io.grpc.netty.NettyChannelBuilder
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class ClientConfig(
    val gatewayUri: URI,

    // grpc properties
    val inboundMessageSize: Int = 40 * 1024 * 1024, // ~ 20 MB
    val idleTimeout: Pair<Long, TimeUnit> = 5L to TimeUnit.MINUTES,
    val keepAliveTime: Pair<Long, TimeUnit> = 60L to TimeUnit.SECONDS, // ~ 12 pbc block cuts
    val keepAliveTimeout: Pair<Long, TimeUnit> = 20L to TimeUnit.SECONDS,
    val executor: ExecutorService = Executors.newFixedThreadPool(8),
    val channelConfigLambda: (NettyChannelBuilder) -> Unit = { }
)
