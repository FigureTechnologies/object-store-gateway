# Object Store Gateway

This application provides a mechanism for fetching permissioned objects stored as Provenance Scope Records from the
[Provenance Encrypted Object Store](https://github.com/provenance-io/object-store) and returning them in a raw, decrypted
format.

## Authentication
Requests to this service must include a grpc metadata header named "Authorization" containing a valid, SECP256K1 signed Provenance
jwt (containing the matching public key ([`sub`] claim)/address ([`addr`] claim) that was used to sign the jwt, must have a valid, future expiration [`exp`] claim).
Once the identity of the caller is determined/verified via the jwt, authorization for the requested data is performed according to the rules below

## Client
There is a client library provided to help facilitate making requests to this service. This can be used to construct a
jwt for requests to facilitate authentication if you do not already have another means of generating a jwt.

Example of creating a client:

```kotlin
import java.net.URI
import tech.figure.objectstore.gateway.client.ClientConfig
import tech.figure.objectstore.gateway.client.GatewayClient

fun getClient(): GatewayClient = GatewayClient(
    config = ClientConfig(
        gatewayUri = URI.create("grpcs://my.org"),
        mainNet = true,
        // See ClientConfig for descriptions of each optional parameter
    ),
)
```

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

## Core Functionality

### Access Grants
One of this service's primary functions is to allow Provenance Blockchain Accounts that would not normally be able to 
read the records stored for a scope in the [Provenance Encrypted Object Store](https://github.com/provenance-io/object-store)
access to those records.  When grants are processed for scopes, scopes that do not include a party, value owner, or data 
access address matching one of the private keys registered with this service will be ignored.

There are two ways in which grants can be added:

#### Wasm Event Grants
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

#### Manual Event Grants

In addition to event stream parsing, a manual grpc request is offered to perform access grants.  See: 
[GrantScopePermission](#GrantScopePermission).

### Access Revokes
The ability to revoke grants that were previously processed is available alongside the [Access Grants](#Access Grants)
functionality.  This functionality will remove all access previously granted to a Provenance Blockchain Account to view
the records associated with a Provenance Blockchain Scope.  

Like the grants functionality, two methods of performing an access revoke are provided:

#### Wasm Event Revokes
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

#### Manual Event Revokes

In addition to event stream parsing, a manual request is offered to perform access revokes.  See:
[RevokeScopePermission](#RevokeScopePermission).

### Object Storage
The service's other primary functionality is to provide object storage for enabled third party Provenance Blockchain
Account addresses.  The GRPC routes provided, [PutObject](#PutObject) and [FetchObject](#FetchObject) allow for accounts
authorized by the admin [PutDataStorageAccount](#PutDataStorageAccount) route to store arbitrary objects of any sort
in the service's configured [Provenance Encrypted Object Store](https://github.com/provenance-io/object-store) instance.
These objects are wrapped in proto that encodes additional metadata, hashed using sha256, and stored using the service's
configured [OBJECTSTORE_MASTER_KEY](#OBJECTSTORE_MASTER_KEY).

## Contract Attributes Rust Library
To facilitate the grant/revoke event structure in a [CosmWasm](https://github.com/CosmWasm/cosmwasm)-based smart
contract, the following Rust library is available: [os-gateway-contract-attributes](https://github.com/FigureTechnologies/os-gateway-contract-attributes).

It provides a helper struct for creating the entire event structure, properly grouped.  It also exposes the individual
keys and expected event type values as constants to allow custom implementations if desired.

## Server Configuration

When deployed as a [Docker](https://www.docker.com/) Container, the application utilizes various environment variables
to customize its functionality.  The deployment properties file is located [here](server/src/main/resources/application-container.properties).

The following environment variables are accepted as input:

### EVENT_STREAM_WEBSOCKET_URI
REQUIRED | String

An HTTP address to use in order to establish a connection to a trusted Provenance Blockchain Query Node for watching
events emitted by the relevant Provenance Blockchain network.

### EVENT_STREAM_EPOCH_HEIGHT
REQUIRED | String

The service dynamically tracks each encountered block from the Provenance Blockchain in its `block_height` table.  This
value will cause the event processor to start processing blocks at this height if no value has ever been stored in the
table.

### EVENT_STREAM_ENABLED
REQUIRED | Boolean

If this value is true, the server will automatically start watching events from the target Provenance Blockchain event
URI.  If not, a warning message will be emitted and the application will only be available for RPC route utilization.

### EVENT_STREAM_BLOCK_HEIGHT_TRACKING_UUID
REQUIRED | UUID v4

A UUID that establishes the latest block height.  If the app needs to re-run various events, swap this value to a new
UUID to force the application to use a new value for [EVENT_STREAM_EPOCH_HEIGHT](#EVENT_STREAM_EPOCH_HEIGHT).

### OBJECTSTORE_URI
REQUIRED | String

A grpc URI to use to establish a connection to an existing instance of a [Provenance Encrypted Object Store](https://github.com/provenance-io/object-store).
This should be an Object Store instance that is used by other applications in an organizations environment that create
Provenance Blockchain Scopes and store their records using one or more of the addresses stored in the
[OBJECTSTORE_PRIVATE_KEYS](#OBJECTSTORE_PRIVATE_KEYS) variable as an additional audience.

### OBJECTSTORE_PRIVATE_KEYS
REQUIRED | CSV (Strings)

A CSV list of hex-encoded Private Keys that belong to the Provenance Blockchain Accounts that have been/will be used as
additional audiences when encoding Provenance Blockchain Scope Records to the [Provenance Encrypted Object Store](https://github.com/provenance-io/object-store)
instance referred to by [OBJECTSTORE_URI](#OBJECTSTORE_URI).

### OBJECTSTORE_MASTER_KEY
REQUIRED | String

A hex-encoded PrivateKey that belongs to a Provenance Blockchain Account that should be held by an administrator of the
Object Store Gateway instance.  This value is uniquely given access to create and manage data access accounts, as well
as being able to manually add and revoke scope access grants.  Due to this, it is vital that the holder of this key be
a trusted entity in an organization.

### PROVENANCE_MAIN_NET
REQUIRED | Boolean

Denotes to the application how to derive bech32 addresses during its execution.  Provenance Blockchain Account bech32
addresses use different prefixes and HD paths based on the environment (mainnet/testnet).

### PROVENANCE_CHAIN_ID
REQUIRED | String

An identifier designating which Provenance Blockchain instance that the application executes queries against.  This,
in tandem with [PROVENANCE_CHANNEL_URI](#PROVENANCE_CHANNEL_URI), ensures that values from a trust Provenance Blockchain
instance are used.

### PROVENANCE_CHANNEL_URI
REQUIRED | String

A grpc URI that denotes which Provenance Blockchain node the service's [PbClient](https://github.com/provenance-io/pb-grpc-client-kotlin)
communicates with.

### DB_TYPE
REQUIRED | One of: postgresql, memory, sqlite

Configures the type of database instance with which the service will communicate.  The following values mandate the
following requirements:

- `postgresql`: A PostgreSQL database named with the value used by [DB_NAME](#DB_NAME) needs to be created before starting
  the application.
- `sqlite`: A Sqlite file must either exist at the location established by [DB_HOST](#DB_HOST) and [DB_NAME](#DB_NAME),
  or one will be created automatically.
- `memory`: No requirements are needed for this configuration.  The caveat is that all data established by the
  application will be lost if it is shutdown or restarted.

### DB_NAME
REQUIRED | String

The name qualifier for the database to be used.  This value's content is irrelevant if the `memory` [DB_TYPE](#DB_TYPE)
value is used.

### DB_USERNAME
REQUIRED | String

The username that will be used to log into the target database instance.  This value's content is irrelevant if the
`sqlite` or `memory` [DB_TYPE](#DB_TYPE) values are used.

### DB_PASSWORD
REQUIRED | String

The password that will be used to log into the target database instance.  This value's content is irrelevant if the
`sqlite` or `memory` [DB_TYPE](#DB_TYPE) values are used.

### DB_HOST
REQUIRED | String

The server location of the database.  This value's content is irrelevant if the `memory` [DB_TYPE](#DB_TYPE) is used.

### DB_PORT
REQUIRED | Integer

The server connection port of the database.  This value's content is irrelevant if the `sqlite` or `memory` [DB_TYPE](#DB_TYPE)
values are used.

### DB_SCHEMA
REQUIRED | String

The schema name under which tables are established in the target database.

### DB_CONNECTION_POOL_SIZE
REQUIRED | Integer

The maximum amount of connections that will be simultaneously used with the target database.
