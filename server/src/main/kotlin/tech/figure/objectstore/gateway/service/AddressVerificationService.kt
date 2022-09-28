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

    /**
     * Verifies that the input address is in proper bech32 format.  Ensures that the hrp in the address is the proper
     * value for the configured Provenance Blockchain network type (pb for mainnet and tp for testnet).  This function
     * will return a Bech32Verification indicating the results of the verification, and is guaranteed to never throw
     * exceptions.
     *
     * @param address The bech32 account address to verify.
     */
    fun verifyAccountAddress(address: String): Bech32Verification = validateBech32Address(
        address = address,
        expectedHrp = if (provenanceProperties.mainNet) MAINNET_HRP else TESTNET_HRP,
    )

    /**
     * Verifies that the input address is in proper bech32 format.  Ensures that the hrp in the address is the proper
     * identifier for a Provenance Blockchain Metadata Scope, "scope."  This function will return a Bech32Verification
     * indicating the results of the verification, and it is guaranteed to never throw exceptions.
     *
     * @param address The bech32 scope address to verify.
     */
    fun verifyScopeAddress(address: String): Bech32Verification = validateBech32Address(
        address = address,
        expectedHrp = SCOPE_HRP,
    )

    /**
     * Internal function that processes all bech32 address verification.  This leverages the Provenance Blockchain
     * Scope Util library's Bech32 helper class to ensure that the address is properly-formed and passes checksum
     * validation.  This function will return a Bech32Verification indicating the hrp on success, or information about
     * failures when they occur.
     *
     * @param address Any bech32 address to verify.
     * @param expectedHrp The human-readable-prefix expected for the input address.  A failure is returned if the
     * input address's hrp does not match this value.
     */
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

/**
 * Encapsulates the different response values that can be emitted by the AddressVerificationService.
 *
 * @value address The bech32 address that was verified.
 */
sealed interface Bech32Verification {
    val address: String

    /**
     * Indicates that the input bech32 address was successfully verified as the anticipated address type.
     *
     * @param hrp The identified human-readable-prefix in the address.
     */
    data class Success(override val address: String, val hrp: String) : Bech32Verification

    /**
     * Indicates that the input bech32 address was detected as invalid for the anticipated address type.
     *
     * @param cause An exception that was emitted during the verification process.  A null value indicates that the
     * failure was not caused by an exception.
     * @param message A message describing the reason that the address cannot be verified.
     */
    data class Failure(
        override val address: String,
        val cause: Exception? = null,
        val message: String = cause?.message ?: "Bech32 validation failed for input address [$address]",
    ) : Bech32Verification
}
