# Object Store Gateway

This application provides a mechanism for fetching permissioned objects stored as Provenance Scope Records from the
[Provenance Encrypted Object Store](https://github.com/provenance-io/object-store) and returning them in a raw, decrypted
format.

## Authentication
Requests to this service must include a grpc metadata header named "Authorization" containing a valid, SECP256K1 signed Provenance
jwt (containing the matching public key ([`sub`] claim)/address ([`addr`] claim) that was used to sign the jwt, must have a valid, future expiration [`exp`] claim).
Once the identity of the caller is determined/verified via the jwt, authorization for the requested data is performed according to the rules below

## Route Definitions + Authorization
This service exposes various grpc endpoints.  Each requires a valid, non-expired JWT header for an authorized Provenance 
account with varying criteria based on the route:

### FetchObject

Fetches all records within a given scope. 

Note that the data returned from this route is raw bytes, and will have to be interpreted in whatever data format is
expected.

The requester address/key must meet one of the following requirements:

1. Be an owner of the scope being requested (and have their private key registered with this service)
2. Have been requested by an owner of a scope to be granted access to its data via smart contract wasm event using
   the correct [attribute format](https://github.com/FigureTechnologies/os-gateway-contract-attributes).
3. Have been requested by an owner of a scope to be granted access to its data via the [GrantScopePermission](#GrantScopePermission) 
   route.

### PutObject

The service will store the given object bytes in a [Provenance Encrypted Object Store](https://github.com/provenance-io/object-store)
using the service's registered master key, allowing for retrieval at a later time using the [FetchObjectByHash](#FetchObjectByHash)
route.

The requester address/key must be an account that was registered by the admin-only [PutDataStorageAccount](#PutDataStorageAccount) 
route.

### FetchObjectByHash

The service will fetch object bytes that were stored by the [PutObject](#PutObject) route.  

Note that the data returned from this route is raw bytes, and will have to be interpreted in whatever data format is
expected.

The requester address/key must have been the account that originally stored the value with the [PutObject](#PutObject)
route.

### GrantScopePermission

Manually enables a Provenance Blockchain Account to retrieve all records stored in a [Provenance Encrypted Object Store](https://github.com/provenance-io/object-store), 
but only if one of the private keys registered with this service was used as an additional audience when the records 
were stored.  This route creates an access grant record in the database.  This will enable the requested account to use 
the [FetchObject](#FetchObject) route.

The requester address/key must meet one of the following requirements:

1. Be the master key registered with the service.
2. Be the value owner of the scope referred to by the request.

### RevokeScopePermission

Manually removes one or more access grant records in the database for a target scope and Provenance Blockchain Account 
combination.

The requester address/key must meet one of the following requirements:

1. Be the master key registered with the service.
2. Be the value owner of the scope referred to by the request.
3. Be the Provenance Blockchain Account referred to be the request (self-permission-revoke).

### PutDataStorageAccount
Enables or updates a Provenance Blockchain Account's ability to use the [PutObject](#PutObject) route.

The requester address/key for this route must be the master key registered with the service.

### FetchDataStorageAccount

Retrieves data about a Provenance Blockchain Account's ability to use the [PutObject](#PutObject) route.

The requester address/key for this route must be the master key registered with the service.

## Access Grants
One of this service's primary functions is to allow Provenance Blockchain Accounts that would not normally be able to 
read the records stored for a scope in the [Provenance Encrypted Object Store](https://github.com/provenance-io/object-store)
access to those records.  When grants are processed for scopes, scopes that do not include a party, value owner, or data 
access address matching one of the private keys registered with this service will be ignored.

There are two ways in which grants can be added:

### Wasm Event Grants
This service watches the event stream emitted by the Provenance Blockchain using the [Figure Tech Event Stream Library](https://github.com/FigureTechnologies/event-stream).
When the [proper event formats](server/src/main/kotlin/tech/figure/objectstore/gateway/eventstream/GatewayEvent.kt) are
detected, the service will automatically attempt to verify the authenticity of the event's sources.  If all values are
properly established, an access grant for a target Provenance Blockchain Scope and Provenance Blockchain Account will
be added to the database, enabling record fetches using the [FetchObject](#FetchObject) route.  Only events emitted by 
transactions signed by the owner of the target scope will be regarded as valid.

Properly-formed events include the following attributes:

- `object_store_gateway_event_type`: This value should be set to `access_grant` to be correctly processed as a grant.
- `object_store_gateway_target_scope_address`: This value should include the Provenance Blockchain Scope bech32 address
of the scope for which access to records will be granted.
- `object_store_gateway_target_account_address`: This value should include the Provenance Blockchain Account bech32
address of the account to which record access will be granted.
- `object_store_gateway_access_grant_id`: This value is optional and the attribute may be omitted entirely.  If included,
this value should be a unique string that will allow targeted access revocations.  Duplicate values to the service will
cause the event to be ignored when processed.

### Manual Event Grants

In addition to event stream parsing, a manual grpc request is offered to perform access grants.  See: 
[GrantScopePermission](#GrantScopePermission).

## Access Revokes
The ability to revoke grants that were previously processed is available alongside the [Access Grants](#Access Grants)
functionality.  This functionality will remove all access previously granted to a Provenance Blockchain Account to view
the records associated with a Provenance Blockchain Scope.  

Like the grants functionality, two methods of performing an access revoke are provided:

### Wasm Event Revokes
This service watches the event stream emitted by the Provenance Blockchain using the [Figure Tech Event Stream Library](https://github.com/FigureTechnologies/event-stream).
When the [proper event formats](server/src/main/kotlin/tech/figure/objectstore/gateway/eventstream/GatewayEvent.kt) are
detected, the service will automatically attempt to verify the authenticity of the event's sources.  If all values are
properly established, any access grants for a target Provenance Blockchain Scope and Provenance Blockchain Account will
be removed from the database, preventing future record fetches using the [FetchObject](#FetchObject) route.  Only events 
emitted by transactions signed by the owner of the target scope or by the account for which access will be revoked will 
be regarded as valid.

Properly-formed events include the following attributes:

- `object_store_gateway_event_type`: This value should be set to `access_revoke` to be correctly processed as a revoke.
- `object_store_gateway_target_scope_address`: This value should include a Provenance Blockchain Scope bech32 address
to which a Provenance Blockchain Account currently has record access via this service.
- `object_store_gateway_target_account_address`: This value should include a Provenance Blockchain Account bech32 
address for which to revoke access.
- `object_store_gateway_access_grant_id`: This value is optional and the attribute may be omitted entirely.  If included,
this value should be a unique string that was used when an access grant was created.  If the grant id is specified as
an id unrelated to any existing grants, no grants will be deleted upon the successful processing of a revoke request. If
this value is omitted, all grants with the scope and target account values will be removed upon processing.

### Manual Event Revokes

In addition to event stream parsing, a manual request is offered to perform access revokes.  See:
[RevokeScopePermission](#RevokeScopePermission).

## Object Store Gateway Contract Attributes Rust Library
To facilitate the grant/revoke event structure in a [CosmWasm](https://github.com/CosmWasm/cosmwasm)-based smart 
contract, the following Rust library is available: [os-gateway-contract-attributes](https://github.com/FigureTechnologies/os-gateway-contract-attributes).

It provides a helper struct for creating the entire event structure, properly grouped.  It also exposes the individual
keys and expected event type values as constants to allow custom implementations if desired.

## Client
There is a client library provided to help facilitate making requests to this service. This can be used to construct a
jwt for requests to facilitate authentication if you do not already have another means of generating a jwt.
