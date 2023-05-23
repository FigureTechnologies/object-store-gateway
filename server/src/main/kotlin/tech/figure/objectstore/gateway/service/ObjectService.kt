package tech.figure.objectstore.gateway.service

import io.provenance.scope.encryption.model.KeyRef
import io.provenance.scope.encryption.util.getAddress
import io.provenance.scope.objectstore.client.CachedOsClient
import io.provenance.scope.objectstore.util.base64Decode
import io.provenance.scope.objectstore.util.toHex
import io.provenance.scope.util.NotFoundException
import io.provenance.scope.util.base64String
import io.provenance.scope.util.toByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KLogging
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import tech.figure.objectstore.gateway.GatewayOuterClass
import tech.figure.objectstore.gateway.GatewayOuterClass.ObjectWithMeta
import tech.figure.objectstore.gateway.configuration.BatchProperties
import tech.figure.objectstore.gateway.configuration.BeanQualifiers
import tech.figure.objectstore.gateway.configuration.ProvenanceProperties
import tech.figure.objectstore.gateway.exception.AccessDeniedException
import tech.figure.objectstore.gateway.exception.ExistingGrantException
import tech.figure.objectstore.gateway.exception.InvalidInputException
import tech.figure.objectstore.gateway.repository.DataStorageAccountsRepository
import tech.figure.objectstore.gateway.repository.ObjectPermissionsRepository
import java.io.ByteArrayInputStream
import java.security.PublicKey

@Component
class ObjectService(
    private val accountsRepository: DataStorageAccountsRepository,
    private val batchProperties: BatchProperties,
    @Qualifier(BeanQualifiers.BATCH_PROCESS_COROUTINE_SCOPE_QUALIFIER) private val batchProcessScope: CoroutineScope,
    private val objectStoreClient: CachedOsClient,
    @Qualifier(BeanQualifiers.OBJECTSTORE_ENCRYPTION_KEYS) private val encryptionKeys: Map<String, KeyRef>,
    @Qualifier(BeanQualifiers.OBJECTSTORE_MASTER_KEY) private val masterKey: KeyRef,
    private val objectPermissionsRepository: ObjectPermissionsRepository,
    private val provenanceProperties: ProvenanceProperties,
) {
    private companion object : KLogging()

    private val masterAddress = masterKey.publicKey.getAddress(provenanceProperties.mainNet)

    fun putObject(obj: GatewayOuterClass.ObjectWithMeta, requesterPublicKey: PublicKey, additionalAudienceKeys: List<PublicKey> = listOf(), useRequesterKey: Boolean = false): String {
        val requesterAddress = requesterPublicKey.getAddress(provenanceProperties.mainNet)
        // Always allow the master key data storage rights
        if (requesterAddress != masterAddress && !encryptionKeys.keys.contains(requesterAddress) && !accountsRepository.isAddressEnabled(requesterAddress)) {
            throw AccessDeniedException("Object storage not granted to $requesterAddress")
        }

        val encryptionKey = if (!useRequesterKey) {
            masterKey
        } else {
            encryptionKeys[requesterAddress]
                ?: throw AccessDeniedException("Only registered encryption keys can request storage with their own key")
        }

        val objectBytes = obj.toByteArray()
        val objectSizeBytes = objectBytes.size.toLong()

        val requesterAndAdditionalAudienceKeys = setOf(requesterPublicKey) + additionalAudienceKeys
        val granterAddress = requesterPublicKey.getAddress(provenanceProperties.mainNet)

        return objectStoreClient.osClient.put(
            ByteArrayInputStream(objectBytes),
            encryptionKey.publicKey,
            encryptionKey.signer(),
            objectSizeBytes,
            requesterAndAdditionalAudienceKeys,
        ).get().hash.toByteArray().base64String().also { hash ->
            val storageKeyAddress = encryptionKey.publicKey.getAddress(provenanceProperties.mainNet)
            requesterAndAdditionalAudienceKeys.forEach {
                objectPermissionsRepository.addAccessPermission(
                    objectHash = hash,
                    granterAddress = granterAddress,
                    granteeAddress = it.getAddress(provenanceProperties.mainNet),
                    storageKeyAddress = storageKeyAddress,
                    objectSizeBytes = objectSizeBytes,
                    isObjectWithMeta = true,
                )
            }
        }
    }

    fun registerExistingObject(objectHash: String, requesterPublicKey: PublicKey, additionalAddresses: List<String>) {
        val requesterAddress = requesterPublicKey.getAddress(provenanceProperties.mainNet)
        if (requesterAddress != masterAddress && !encryptionKeys.containsKey(requesterAddress)) {
            throw AccessDeniedException("Existing object registration only available for master or registered encryption keys")
        }

        val keyToUse = if (requesterAddress == masterAddress) masterKey else encryptionKeys[requesterAddress]!!

        // verify that requester can fetch object from object store (get size and has access)
        val objectSizeBytes = getObjectInternal(objectHash, keyToUse).size.toLong()

        // add access permissions for non-meta object stored with granter as requester's address, and grantees of requester? and additionalAddresses
        val requesterAndAdditionalAddresses = additionalAddresses.toSet() + requesterAddress
        requesterAndAdditionalAddresses.forEach {
            objectPermissionsRepository.addAccessPermission(
                objectHash = objectHash,
                granterAddress = requesterAddress,
                granteeAddress = it,
                storageKeyAddress = requesterAddress,
                objectSizeBytes = objectSizeBytes,
                isObjectWithMeta = false,
            )
        }
    }

    fun getObject(hash: String, requesterAddress: String): GatewayOuterClass.ObjectWithMeta {
        val objectPermission = objectPermissionsRepository.getAccessPermission(hash, requesterAddress)
            ?: throw AccessDeniedException("Object access not granted to $requesterAddress [hash: $hash]")

        val storageKey = objectPermission.storageKeyAddress.let {
            if (it == masterAddress) {
                masterKey
            } else {
                encryptionKeys[it]
            }
        } ?: throw AccessDeniedException("No key found for retrieval for $requesterAddress [hash: $hash]")

        return getObjectInternal(hash, storageKey).let {
            if (objectPermission.isObjectWithMeta) {
                GatewayOuterClass.ObjectWithMeta.parseFrom(it)
            } else {
                ObjectWithMeta.newBuilder().setObjectBytes(it.toByteString()).build()
            }
        }
    }

    private fun getObjectInternal(hash: String, key: KeyRef) = objectStoreClient.osClient.get(hash.base64Decode(), key.publicKey).get().use { dimeInputStream ->
        dimeInputStream.getDecryptedPayload(key).use { signatureInputStream ->
            signatureInputStream.readAllBytes().also {
                if (!signatureInputStream.verify()) {
                    throw NotFoundException(
                        """
                                Object was fetched but we're unable to verify item signature
                                [encryption public key: ${key.publicKey.toHex()}]
                                [hash: $hash]
                        """.trimIndent()
                    )
                }
            }
        }
    }

    fun grantAccess(hash: String, granteeAddress: String, granterAddress: String) = transaction {
        val existingObjects = objectPermissionsRepository.getAccessPermissionsForGranter(
            objectHash = hash,
            granterAddress = granterAddress,
        )
        if (existingObjects.isEmpty()) {
            throw AccessDeniedException("Granter [$granterAddress] has no authority to grant on hash [$hash]")
        }
        if (existingObjects.any { it.granteeAddress == granteeAddress }) {
            throw ExistingGrantException("Grantee [$granteeAddress] has already been granted permissions to hash [$hash]")
        }
        val existingObject = existingObjects.first()
        objectPermissionsRepository.addAccessPermission(
            objectHash = hash,
            granterAddress = granterAddress,
            granteeAddress = granteeAddress,
            storageKeyAddress = existingObject.storageKeyAddress,
            objectSizeBytes = existingObject.objectSizeBytes,
            isObjectWithMeta = existingObject.isObjectWithMeta,
        )
    }

    fun batchGrantAccess(
        granteeAddress: String,
        granterAddress: String,
        targetHashes: Collection<String>? = null,
        emitResponse: (hash: String, grantee: String) -> Unit,
        completeProcess: () -> Unit,
    ) = transaction {
        val cachedObjects = if (targetHashes != null) {
            if (targetHashes.size == 0) {
                throw InvalidInputException("Target hash count must be greater than zero")
            }
            if (targetHashes.size > batchProperties.maxProvidedRecords) {
                throw InvalidInputException("Target hash count must be less than maximum value of [${batchProperties.maxProvidedRecords}]")
            }
            objectPermissionsRepository.getAccessPermissionsForGranterByHashes(
                objectHashes = targetHashes,
                granterAddress = granterAddress,
            )
        } else {
            null
        }
        val hashesToGrant = cachedObjects?.keys ?: objectPermissionsRepository.getAllGranterHashes(granterAddress = granterAddress)
        batchProcessScope.launch {
            hashesToGrant.map { hash ->
                batchProcessScope.launch {
                    val objectToGrant = cachedObjects
                        ?.get(hash)
                        ?.firstOrNull()
                        ?: objectPermissionsRepository
                            .getAccessPermissionsForGranter(objectHash = hash, granterAddress = granterAddress)
                            .firstOrNull()
                    if (objectToGrant == null) {
                        logger.info { "Skipping object grant for hash [$hash]. It cannot be found for granter [$granterAddress]" }
                    } else {
                        logger.info { "ADDING object grant for hash [$hash] to [$granteeAddress] from [$granterAddress]" }
                        objectPermissionsRepository.addAccessPermission(
                            objectHash = hash,
                            granterAddress = granterAddress,
                            granteeAddress = granteeAddress,
                            storageKeyAddress = objectToGrant.storageKeyAddress,
                            objectSizeBytes = objectToGrant.objectSizeBytes,
                            isObjectWithMeta = objectToGrant.isObjectWithMeta,
                        )
                        emitResponse(hash, granteeAddress)
                    }
                }
            }.forEach { it.join() }
            completeProcess()
        }
    }

    fun revokeAccess(hash: String, requesterAddress: String, revokeAddresses: List<String>) {
        objectPermissionsRepository.revokeAccessPermissions(hash, requesterAddress, revokeAddresses)
    }
}
