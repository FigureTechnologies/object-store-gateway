package io.provenance.objectstore.gatway.shared

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import io.provenance.scope.encryption.crypto.SignerImpl
import io.provenance.scope.encryption.model.KeyRef
import java.security.interfaces.ECPublicKey

class KeyRefSecP256K1Algorithm(
    keyRef: KeyRef,
): Algorithm("ES256K", "A SecP256K1 algorithm implementation for use with a Provenance p8e scope sdk KeyRef") {
    private val signatureConverter = DERtoJOSEConverter(32)
    private val signer: SignerImpl = keyRef.signer().apply {
        hashType = SignerImpl.Companion.HashType.SHA256
        deterministic = false
    }

    override fun verify(jwt: DecodedJWT) {
        // Delegate verification to regular ECDSA256K algo, since it only needs the public key
        JWT.require(ECDSA256K(signer.getPublicKey() as ECPublicKey, null))
            .build()
            .verify(jwt)
    }

    override fun sign(contentBytes: ByteArray): ByteArray {
        return signer.run {
            initSign()
            update(contentBytes)
            signatureConverter.DERToJOSE(sign())
        }
    }
}
