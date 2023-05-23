package tech.figure.objectstore.gateway.configuration

object BeanQualifiers {
    const val BATCH_PROCESS_COROUTINE_SCOPE_QUALIFIER = "batchProcessCoroutineScopeBean"
    const val EVENT_STREAM_COROUTINE_SCOPE_QUALIFIER = "eventStreamCoroutineScopeBean"
    const val OBJECTSTORE_ENCRYPTION_KEYS: String = "objectStoreEncryptionKeys"
    const val OBJECTSTORE_PRIVATE_KEYS: String = "objectStorePrivateKeys"
    const val OBJECTSTORE_MASTER_KEY: String = "objectStoreMasterKey"
}
