package tech.figure.objectstore.gateway.service

import io.provenance.scope.util.MetadataAddress
import tech.figure.objectstore.gateway.configuration.ProvenanceProperties
import tech.figure.objectstore.gateway.helpers.bech32Address
import tech.figure.objectstore.gateway.helpers.genRandomAccount
import tech.figure.objectstore.gateway.service.Bech32Verification.Failure
import tech.figure.objectstore.gateway.service.Bech32Verification.Success
import java.net.URI
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AddressVerificationServiceTest {
    @Test
    fun `verifyAccountAddress properly processes mainnet addresses`() {
        val service = getService(mainNet = true)
        // Proves bech32 validation verifies checksums
        assertFailedValidation(
            verification = service.verifyAccountAddress("pb1fakeaddress"),
            expectedMessage = "checksum failed",
        )
        // Proves bech32 validation requires mainnet when configured to do so
        val testnetAddress = genRandomAccount(testnet = true).bech32Address
        assertFailedValidation(
            verification = service.verifyAccountAddress(testnetAddress),
            expectedMessage = "Expected hrp [pb] for address [$testnetAddress]",
        )
        // Verifies that lowercase input does not cause any issues
        assertSuccessfulValidation(
            verification = service.verifyAccountAddress(genRandomAccount(testnet = false).bech32Address.lowercase()),
            expectedHrp = "pb",
        )
        // Verifies that uppercase input does not cause any issues
        assertSuccessfulValidation(
            verification = service.verifyAccountAddress(genRandomAccount(testnet = false).bech32Address.uppercase()),
            expectedHrp = "pb",
        )
    }

    @Test
    fun `verifyAccountAddress properly processes testnet address`() {
        val service = getService(mainNet = false)
        // Proves bech32 validation verifies checksums
        assertFailedValidation(
            verification = service.verifyAccountAddress("tp1fakeaddress"),
            expectedMessage = "checksum failed",
        )
        // Proves bech32 validation requires testnet when configured to do so
        val mainnetAddress = genRandomAccount(testnet = false).bech32Address
        assertFailedValidation(
            verification = service.verifyAccountAddress(mainnetAddress),
            expectedMessage = "Expected hrp [tp] for address [$mainnetAddress]",
        )
        // Verifies that lowercase input does not cause any issues
        assertSuccessfulValidation(
            verification = service.verifyAccountAddress(genRandomAccount(testnet = true).bech32Address.lowercase()),
            expectedHrp = "tp",
        )
        // Verifies that uppercase input does not cause any issues
        assertSuccessfulValidation(
            verification = service.verifyAccountAddress(genRandomAccount(testnet = true).bech32Address.uppercase()),
            expectedHrp = "tp",
        )
    }

    @Test
    fun `verifyScopeAddress properly processes address`() {
        val service = getService()
        // Proves bech32 validation verifies checksums
        assertFailedValidation(
            verification = service.verifyScopeAddress("scope1fakeaddress"),
            expectedMessage = "checksum failed",
        )
        // Proves scope addresses require the scope prefix
        val address = genRandomAccount(testnet = false).bech32Address
        assertFailedValidation(
            verification = service.verifyScopeAddress(address),
            expectedMessage = "Expected hrp [scope] for address [$address]",
        )
        assertSuccessfulValidation(
            verification = service.verifyScopeAddress(MetadataAddress.forScope(UUID.randomUUID()).toString()),
            expectedHrp = "scope",
        )
    }

    private fun assertFailedValidation(verification: Bech32Verification, expectedMessage: String) {
        assertTrue(
            actual = verification is Failure,
            message = "Expected validation to be failure, but was: $verification",
        )
        assertTrue(
            actual = expectedMessage in verification.message,
            message = "Expected validation message to be correctly formatted, but got: ${verification.message}",
        )
    }

    private fun assertSuccessfulValidation(verification: Bech32Verification, expectedHrp: String) {
        assertTrue(
            actual = verification is Success,
            message = "Expected validation to be success, but was: $verification",
        )
        assertEquals(
            expected = expectedHrp,
            actual = verification.hrp,
            message = "Incorrect hrp derived from input",
        )
    }

    private fun getService(mainNet: Boolean = false): AddressVerificationService = AddressVerificationService(
        provenanceProperties = ProvenanceProperties(
            mainNet = mainNet,
            chainId = "chain-local",
            channelUri = URI.create("http://localhost:9090"),
        )
    )
}
