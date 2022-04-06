package io.provenance.objectstore.gateway.util

import com.google.protobuf.ByteString
import io.provenance.metadata.v1.Party
import io.provenance.metadata.v1.PartyType
import io.provenance.objectstore.gateway.GatewayOuterClass
import io.provenance.scope.encryption.aes.ProvenanceAESCrypt
import io.provenance.scope.objectstore.util.base64Decode
import io.provenance.scope.objectstore.util.toPublicKey
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.PublicKey
import java.security.Signature

fun ByteArray.toByteString() = ByteString.copyFrom(this)

fun GatewayOuterClass.FetchObjectRequest.validateSignature(): Boolean = Signature.getInstance("SHA512withECDDSA", BouncyCastleProvider.PROVIDER_NAME).runCatching {
    val requesterPublicKey = signature.signer.signingPublicKey.toPublicKey()
    initVerify(requesterPublicKey)
    update(params.toByteArray())
    verify(signature.signature.base64Decode())
}.getOrDefault(false)

fun String.toOwnerParty() = Party.newBuilder().setAddress(this).setRole(PartyType.PARTY_TYPE_OWNER).build()
