package tech.figure.objectstore.gateway.helpers

import io.provenance.metadata.v1.Party
import io.provenance.metadata.v1.PartyType
import io.provenance.metadata.v1.ScopeResponse
import io.provenance.metadata.v1.SessionWrapper
import io.provenance.scope.util.MetadataAddress
import java.util.UUID

fun mockScopeResponse(
    address: String = MetadataAddress.forScope(UUID.randomUUID()).toString(),
    owners: Set<Pair<PartyType, String>> = emptySet(),
    dataAccessAddresses: Set<String> = emptySet(),
    valueOwnerAddress: String? = null,
    sessionParties: Set<Pair<PartyType, String>> = emptySet(),
): ScopeResponse = ScopeResponse.newBuilder().also { responseBuilder ->
    responseBuilder.scopeBuilder.also { scopeBuilder ->
        scopeBuilder.scopeIdInfoBuilder.scopeAddr = address
        scopeBuilder.scopeBuilder.also { nestedScopeBuilder ->
            owners.forEach { (role, address) ->
                nestedScopeBuilder.addOwners(
                    Party.newBuilder().also { partyBuilder ->
                        partyBuilder.role = role
                        partyBuilder.address = address
                    }
                )
            }
            nestedScopeBuilder.addAllDataAccess(dataAccessAddresses)
            valueOwnerAddress?.also { nestedScopeBuilder.valueOwnerAddress = it }
        }
    }
    if (sessionParties.isNotEmpty()) {
        responseBuilder.addSessions(
            SessionWrapper.newBuilder().also { session ->
                sessionParties.forEach { (role, address) ->
                    session.sessionBuilder.addParties(
                        Party.newBuilder().also { partyBuilder ->
                            partyBuilder.role = role
                            partyBuilder.address = address
                        }
                    )
                }
            }
        )
    }
}.build()
