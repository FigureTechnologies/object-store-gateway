package tech.figure.objectstore.gateway.service

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import io.provenance.scope.encryption.ecies.ECUtils
import io.provenance.scope.encryption.util.getAddress
import io.provenance.scope.objectstore.util.base64Decode
import org.springframework.stereotype.Service
import tech.figure.objectstore.gateway.exception.JwtValidationException
import java.security.PublicKey
import java.security.interfaces.ECPublicKey

data class VerificationResult(val address: String, val publicKey: PublicKey)

@Service
class JwtVerificationService {
    fun verifyJwtString(jwt: String) = verifyJwt(JWT.decode(jwt))

    @Suppress("DEPRECATION")
    fun verifyJwt(jwt: DecodedJWT): VerificationResult {
        val publicKey = jwt.claims.get(Constants.JWT_PUBLIC_KEY)
            ?.asString()
            ?.base64Decode()
            ?.let { ECUtils.convertBytesToPublicKey(it) }
            ?: throw JwtValidationException("Failed to find public key in jwt")

        val algorithm = Algorithm.ECDSA256K(publicKey as ECPublicKey, null)

        val verified = try {
            JWT.require(algorithm)
                .acceptExpiresAt(0L)
                .build()
                .verify(jwt)
        } catch (e: JWTVerificationException) {
            throw JwtValidationException(e.message, e)
        }

        if (!verified.claims.containsKey(Constants.JWT_EXPIRATION_KEY)) {
            throw JwtValidationException("JWT expiration is required")
        }

        val address = verified.claims[Constants.JWT_ADDRESS_KEY]?.asString() ?: throw JwtValidationException("Failed to find address in jwt")
        val mainNet = address.startsWith(Constants.MAINNET_HRP)

        if (publicKey.getAddress(mainNet) != address) {
            throw JwtValidationException("Jwt public key and address do not match")
        }

        return VerificationResult(
            address,
            publicKey
        )
    }
}
