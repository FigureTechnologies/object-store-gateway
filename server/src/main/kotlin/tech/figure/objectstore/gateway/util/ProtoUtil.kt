package tech.figure.objectstore.gateway.util

import com.google.protobuf.ByteString
import io.provenance.metadata.v1.Party
import io.provenance.metadata.v1.PartyType

fun ByteArray.toByteString() = ByteString.copyFrom(this)

fun String.toOwnerParty() = Party.newBuilder().setAddress(this).setRole(PartyType.PARTY_TYPE_OWNER).build()
