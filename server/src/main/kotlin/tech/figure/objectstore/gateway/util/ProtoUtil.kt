package tech.figure.objectstore.gateway.util

import com.google.protobuf.ByteString
import io.provenance.metadata.v1.Party
import io.provenance.metadata.v1.PartyType

fun ByteArray.toByteString() = ByteString.copyFrom(this)

fun String.toOwnerParty() = toPartyType(PartyType.PARTY_TYPE_OWNER)

fun String.toPartyType(partyType: PartyType) = Party.newBuilder().setAddress(this).setRole(partyType).build()
