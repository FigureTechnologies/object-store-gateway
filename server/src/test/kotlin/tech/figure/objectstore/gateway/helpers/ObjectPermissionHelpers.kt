package tech.figure.objectstore.gateway.helpers

import io.mockk.every
import io.mockk.mockk
import tech.figure.objectstore.gateway.model.ObjectPermission

fun mockObjectPermission(objectHash: String, granteeAddress: String, granterAddress: String, storageKeyAddress: String, isObjectWithMeta: Boolean) = mockk<ObjectPermission>().also {
    every { it.objectHash } returns objectHash
    every { it.granteeAddress } returns granteeAddress
    every { it.granterAddress } returns granterAddress
    every { it.storageKeyAddress } returns storageKeyAddress
    every { it.isObjectWithMeta } returns isObjectWithMeta
}
