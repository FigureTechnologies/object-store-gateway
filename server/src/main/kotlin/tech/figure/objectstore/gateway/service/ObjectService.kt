package tech.figure.objectstore.gateway.service

import io.provenance.scope.encryption.model.KeyRef
import io.provenance.scope.encryption.util.getAddress
import io.provenance.scope.objectstore.client.CachedOsClient
import io.provenance.scope.objectstore.util.base64Decode
import io.provenance.scope.objectstore.util.toHex
import io.provenance.scope.util.NotFoundException
import io.provenance.scope.util.base64String
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import tech.figure.objectstore.gateway.GatewayOuterClass
import tech.figure.objectstore.gateway.configuration.BeanQualifiers
import tech.figure.objectstore.gateway.configuration.ProvenanceProperties
import tech.figure.objectstore.gateway.exception.AccessDeniedException
import tech.figure.objectstore.gateway.repository.DataStorageAccountsRepository
import tech.figure.objectstore.gateway.repository.ObjectPermissionsRepository
import java.io.ByteArrayInputStream
import java.security.PublicKey

@Component
class ObjectService(
    private val accountsRepository: DataStorageAccountsRepository,
    private val objectStoreClient: CachedOsClient,
    @Qualifier(BeanQualifiers.OBJECTSTORE_MASTER_KEY) private val masterKey: KeyRef,
    private val objectPermissionsRepository: ObjectPermissionsRepository,
    private val provenanceProperties: ProvenanceProperties,
) {
    private val masterAddress = masterKey.publicKey.getAddress(provenanceProperties.mainNet)

    fun putObject(obj: GatewayOuterClass.ObjectWithMeta, requesterPublicKey: PublicKey, additionalAudienceKeys: List<PublicKey> = listOf()): String {
        val requesterAddress = requesterPublicKey.getAddress(provenanceProperties.mainNet)
        // Always allow the master key data storage rights
        if (requesterAddress != masterAddress && !accountsRepository.isAddressEnabled(requesterAddress)) {
            throw AccessDeniedException("Object storage not granted to $requesterAddress")
        }

        val objectBytes = obj.toByteArray()
        val objectSize = objectBytes.size.toLong()

        val requesterAndAdditionalAudienceKeys = setOf(requesterPublicKey) + additionalAudienceKeys

        return objectStoreClient.osClient.put(
            ByteArrayInputStream(objectBytes),
            masterKey.publicKey,
            masterKey.signer(),
            objectSize,
            requesterAndAdditionalAudienceKeys,
        ).get().hash.toByteArray().base64String().also { hash ->
            requesterAndAdditionalAudienceKeys.forEach {
                objectPermissionsRepository.addAccessPermission(hash, it.getAddress(provenanceProperties.mainNet), objectSize)
            }
        }
    }

    fun getObject(hash: String, requesterAddress: String): GatewayOuterClass.ObjectWithMeta {
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
            }
        }.let(GatewayOuterClass.ObjectWithMeta::parseFrom)
    }
}
