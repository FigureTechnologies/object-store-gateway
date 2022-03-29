package io.provenance.objectstore.gateway.service

import io.provenance.client.grpc.PbClient
import io.provenance.metadata.v1.ScopeRequest
import io.provenance.objectstore.gateway.GatewayOuterClass
import io.provenance.objectstore.gateway.util.toByteString
import io.provenance.scope.encryption.model.KeyRef
import io.provenance.scope.objectstore.client.CachedOsClient
import io.provenance.scope.objectstore.util.base64Decode
import io.provenance.scope.sdk.extensions.resultType
import org.springframework.stereotype.Component

@Component
class ScopeFetchService(
    private val objectStoreClient: CachedOsClient,
    private val encryptionKeyRef: KeyRef,
    private val pbClient: PbClient,
) {
    fun fetchScope(scopeAddress: String): List<GatewayOuterClass.Record> =
        pbClient.metadataClient.scope(ScopeRequest.newBuilder().setScopeId(scopeAddress).setIncludeRecords(true).build()).also {
            println("fetching ${it.recordsCount} records")
        }.recordsList
            .map { record ->
                GatewayOuterClass.Record.newBuilder()
                    .setName(record.record.name)
                    .addAllInputs(record.record.inputsList.map {
                        GatewayOuterClass.RawObject.newBuilder()
                            .setHash(it.hash)
                            .setType(it.typeName)
                            .setObjectBytes(objectStoreClient.getJar(it.hash.base64Decode(), encryptionKeyRef).get().readAllBytes().toByteString())
                            .build()
                    })
                    .addAllOutputs(record.record.outputsList.map {
                        GatewayOuterClass.RawObject.newBuilder()
                            .setHash(it.hash)
                            .setType(record.record.resultType())
                            .setObjectBytes(objectStoreClient.getJar(it.hash.base64Decode(), encryptionKeyRef).get().readAllBytes().toByteString())
                            .build()
                    })
                    .build()
            }
}
