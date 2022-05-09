# Object Store Gateway

This application provides a mechanism for fetching permissioned objects stored as Provenance Scope Records from the
[Provenance Encrypted Object Store](https://github.com/provenance-io/object-store) and returning them in a raw, decrypted
format.

This service exposes one endpoint, used for fetching an object. In order for an object to be fetched, the following criteria must be met.
1. The request must have a valid, non-expired signature
2. The requester address/key must meet one of the following requirements:
   1. Be an owner of the scope being requested
   2. Have been requested by an owner of the scope as a verifier for asset classification purposes. Scopes that do not
      include a party, value owner, or data access address matching one of the private keys registered with this service
      will be ignored.

In order to facilitate this scope data permissioning, this service listens on the [Provenance Event Stream](https://github.com/provenance-io/event-stream)
for asset classification contract events, and stores a lookup of the address registered to this service to the address
being assigned as an asset verifier. Later, when a request arrives for scope data, the above permissioning rules are
used to approve/deny access.

Note that the data returned from this service is raw bytes, and will have to be interpreted in whatever data format is
expected.
