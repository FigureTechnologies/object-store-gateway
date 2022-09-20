package tech.figure.objectstore.gateway

import java.security.PublicKey

fun publicKey(): PublicKey = Constants.REQUESTOR_PUBLIC_KEY_CTX.get()
fun address(): String = Constants.REQUESTOR_ADDRESS_CTX.get()
