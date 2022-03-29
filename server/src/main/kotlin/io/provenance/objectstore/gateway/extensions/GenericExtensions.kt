package io.provenance.objectstore.gateway.extensions

inline fun <reified T> T.wrapList(): List<T> = listOf(this)
inline fun <reified T> T.wrapSet(): Set<T> = setOf(this)
inline fun <reified T> T.wrapArray(): Array<T> = arrayOf(this)

fun <T: Any> T?.checkNotNull(lazyMessage: () -> String): T {
    checkNotNull(this, lazyMessage)
    return this
}
