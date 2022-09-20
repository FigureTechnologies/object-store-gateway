ALTER TABLE scope_permissions ADD COLUMN grant_id text;

CREATE INDEX IF NOT EXISTS scope_permissions_grant_id_idx ON scope_permissions(grant_id);

-- Remove old index that enforces unique on scope_address/grantee_address/granter_address
DROP INDEX IF EXISTS scope_permissions_scope_address_grantee_address_granter_address;

-- LMAO BEST NAME EVER.  Ensures that there can be duplicate grants from granters to grantees on a scope address, as
-- long as the duplicate specifies a null or newly-unique grant id
CREATE UNIQUE INDEX IF NOT EXISTS scope_permissions_scope_addr_grantee_addr_granter_addr_grant_id ON scope_permissions (scope_address, grantee_address, granter_address, grant_id);
