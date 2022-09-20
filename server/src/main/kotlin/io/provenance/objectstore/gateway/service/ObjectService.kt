package io.provenance.objectstore.gateway.service

import io.provenance.objectstore.gateway.configuration.ProvenanceProperties
import io.provenance.objectstore.gateway.exception.AccessDeniedException
import io.provenance.objectstore.gateway.repository.ObjectPermissionsRepository
import io.provenance.scope.encryption.model.KeyRef
import io.provenance.scope.encryption.util.getAddress
import io.provenance.scope.objectstore.client.CachedOsClient
import io.provenance.scope.objectstore.util.base64Decode
import io.provenance.scope.objectstore.util.toHex
import io.provenance.scope.util.NotFoundException
import io.provenance.scope.util.base64String
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.security.PublicKey

@Component
class ObjectService(
    private val objectStoreClient: CachedOsClient,
    private val masterKey: KeyRef,
    private val objectPermissionsRepository: ObjectPermissionsRepository,
    private val provenanceProperties: ProvenanceProperties,
) {
    companion object {
        const val OBJECT_META_TYPE_KEY = "object_type"
    }

    fun putObject(objectBytes: ByteArray, type: String?, requesterPublicKey: PublicKey): String {
        val metadata = if (type != null) {
            mapOf(OBJECT_META_TYPE_KEY to type)
        } else mapOf()

        return objectStoreClient.osClient.put(
            ByteArrayInputStream(objectBytes),
            masterKey.publicKey,
            masterKey.signer(),
            objectBytes.size.toLong(),
            setOf(requesterPublicKey),
            metadata
        ).get().hash.toByteArray().base64String().also { hash ->
            objectPermissionsRepository.addAccessPermission(hash, requesterPublicKey.getAddress(provenanceProperties.mainNet))
        }
    }

    fun getObject(hash: String, requesterAddress: String): Pair<ByteArray, String?> {
        if (!objectPermissionsRepository.hasAccessPermission(hash, requesterAddress)) {
            throw AccessDeniedException("Object access not granted to $requesterAddress [hash: $hash]")
        }

        return objectStoreClient.osClient.get(hash.base64Decode(), masterKey.publicKey).get().use { dimeInputStream ->
            dimeInputStream.getDecryptedPayload(masterKey).use { signatureInputStream ->
                signatureInputStream.readAllBytes().also {
                    if (!signatureInputStream.verify()) {
                        throw NotFoundException(
                            """
                                Object was fetched but we're unable to verify item signature
                                [encryption public key: ${masterKey.publicKey.toHex()}]
                                [hash: $hash]
                            """.trimIndent()
                        )
                    }
                }
            } to dimeInputStream.metadata[OBJECT_META_TYPE_KEY]
        }
    }
}
