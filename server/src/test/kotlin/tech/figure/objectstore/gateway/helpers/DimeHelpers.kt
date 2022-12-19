package tech.figure.objectstore.gateway.helpers

import io.mockk.every
import io.mockk.mockk
import io.provenance.scope.encryption.crypto.SignatureInputStream
import io.provenance.scope.encryption.domain.inputstream.DIMEInputStream
import io.provenance.scope.encryption.model.KeyRef

fun mockDime(objectBytes: ByteArray, encryptionKey: KeyRef): DIMEInputStream {
    val dimeInputStream: DIMEInputStream = mockk()
    val signatureInputStream: SignatureInputStream = mockk()
    every { dimeInputStream.getDecryptedPayload(encryptionKey) } returns signatureInputStream
    every { signatureInputStream.readAllBytes() } returns objectBytes
    every { signatureInputStream.verify() } returns true
    every { dimeInputStream.close() } returns Unit
    every { signatureInputStream.close() } returns Unit
    return dimeInputStream
}
