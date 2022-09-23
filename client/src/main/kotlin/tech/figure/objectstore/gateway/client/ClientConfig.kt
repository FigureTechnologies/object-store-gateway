package tech.figure.objectstore.gateway.client

import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Configuration values for a GatewayClient.
 *
 * @param gatewayUri A grpc URI for communication with a deployed object-store-gateway server.  Ex: grpcs://my.org
 * @param mainNet If true, the client will generate JWT values using the Provenance Blockchain mainnet human-readable-
 * prefix: pb.  Otherwise, the testnet hrp will be used: tp.
 * @param channelConfigLambda Allows for dynamic configuration of a NettyChannelBuilder used to create the grpc stubs
 * used in the GatewayClient after all other parameters are supplied as input.
 *
 * The remaining parameters are directly passed into a NettyChannelBuilder.  The documentation for the builder can be
 * found at: https://www.javadoc.io/doc/io.grpc/grpc-all/1.45.1/io/grpc/netty/NettyChannelBuilder.html
 */
data class ClientConfig(
    val gatewayUri: URI,
    val mainNet: Boolean,

    // grpc properties
    val inboundMessageSize: Int = 40 * 1024 * 1024, // ~ 20 MB
    val idleTimeout: Pair<Long, TimeUnit> = 5L to TimeUnit.MINUTES,
    val keepAliveTime: Pair<Long, TimeUnit> = 60L to TimeUnit.SECONDS, // ~ 12 pbc block cuts
    val keepAliveTimeout: Pair<Long, TimeUnit> = 20L to TimeUnit.SECONDS,
    val executor: ExecutorService = Executors.newFixedThreadPool(8),
    val channelConfigLambda: (NettyChannelBuilder) -> Unit = { }
)
