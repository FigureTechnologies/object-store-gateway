package io.provenance.objectstore.gateway.util

import com.google.protobuf.ByteString
import io.provenance.objectstore.gateway.GatewayOuterClass
import io.provenance.scope.encryption.aes.ProvenanceAESCrypt
import io.provenance.scope.objectstore.util.base64Decode
import io.provenance.scope.objectstore.util.toPublicKey
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.PublicKey
import java.security.Signature

fun ByteArray.toByteString() = ByteString.copyFrom(this)

fun GatewayOuterClass.FetchObjectRequest.validateSignature(): Boolean = Signature.getInstance("SHA512withECDDSA", BouncyCastleProvider.PROVIDER_NAME).run {
    val requesterPublicKey = signature.signer.signingPublicKey.toPublicKey()
    initVerify(requesterPublicKey)
    update(params.toByteArray())
    verify(signature.signature.base64Decode())
}
