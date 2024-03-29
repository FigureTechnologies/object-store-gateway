package tech.figure.objectstore.gateway.shared

import java.security.SignatureException
import kotlin.experimental.and

class DERtoJOSEConverter(
    val ecNumberSize: Int
) {
    @Throws(SignatureException::class)
    fun DERToJOSE(derSignature: ByteArray): ByteArray {
        // DER Structure: http://crypto.stackexchange.com/a/1797
        val derEncoded = derSignature[0] == 0x30.toByte() && derSignature.size != ecNumberSize * 2
        if (!derEncoded) {
            throw SignatureException("Invalid DER signature format.")
        }
        val joseSignature = ByteArray(ecNumberSize * 2)

        // Skip 0x30
        var offset = 1
        if (derSignature[1] == 0x81.toByte()) {
            // Skip sign
            offset++
        }

        // Convert to unsigned. Should match DER length - offset
        val encodedLength: Int = (derSignature[offset++] and 0xff.toByte()).toInt()
        if (encodedLength != derSignature.size - offset) {
            throw SignatureException("Invalid DER signature format.")
        }

        // Skip 0x02
        offset++

        // Obtain R number length (Includes padding) and skip it
        val rLength = derSignature[offset++].toInt()
        if (rLength > ecNumberSize + 1) {
            throw SignatureException("Invalid DER signature format.")
        }
        val rPadding: Int = ecNumberSize - rLength
        // Retrieve R number
        System.arraycopy(
            derSignature,
            offset + Math.max(-rPadding, 0),
            joseSignature,
            Math.max(rPadding, 0),
            rLength + Math.min(rPadding, 0)
        )

        // Skip R number and 0x02
        offset += rLength + 1

        // Obtain S number length. (Includes padding)
        val sLength = derSignature[offset++].toInt()
        if (sLength > ecNumberSize + 1) {
            throw SignatureException("Invalid DER signature format.")
        }
        val sPadding: Int = ecNumberSize - sLength
        // Retrieve R number
        System.arraycopy(
            derSignature,
            offset + Math.max(-sPadding, 0),
            joseSignature,
            ecNumberSize + Math.max(sPadding, 0),
            sLength + Math.min(sPadding, 0)
        )
        return joseSignature
    }
}
