package tech.figure.objectstore.gateway.service

import io.provenance.scope.util.Bech32
import mu.KLogging
import org.springframework.stereotype.Service
import tech.figure.objectstore.gateway.configuration.ProvenanceProperties
import tech.figure.objectstore.gateway.service.Bech32Verification.Failure
import tech.figure.objectstore.gateway.service.Bech32Verification.Success

@Service
class AddressVerificationService(private val provenanceProperties: ProvenanceProperties) {
    private companion object : KLogging() {
        private const val MAINNET_HRP: String = "pb"
        private const val TESTNET_HRP: String = "tp"
        private const val SCOPE_HRP: String = "scope"
    }

    fun verifyAccountAddress(address: String): Bech32Verification = validateBech32Address(
        address = address,
        expectedHrp = if (provenanceProperties.mainNet) MAINNET_HRP else TESTNET_HRP,
    )

    fun verifyScopeAddress(address: String): Bech32Verification = validateBech32Address(
        address = address,
        expectedHrp = SCOPE_HRP,
    )

    private fun validateBech32Address(address: String, expectedHrp: String): Bech32Verification = try {
        val bech32Data = Bech32.decode(address)
        if (bech32Data.hrp == expectedHrp) {
            Success(address = address, hrp = bech32Data.hrp)
        } else {
            Failure(address = address, message = "Expected hrp [$expectedHrp] for address [$address]")
        }
    } catch (e: Exception) {
        Failure(address = address, cause = e)
    }
}

sealed interface Bech32Verification {
    val address: String

    data class Success(override val address: String, val hrp: String) : Bech32Verification
    data class Failure(
        override val address: String,
        val cause: Exception? = null,
        val message: String = cause?.message ?: "Bech32 validation failed for input address [$address]",
    ) : Bech32Verification
}
