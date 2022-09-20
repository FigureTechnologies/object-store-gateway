CREATE TABLE IF NOT EXISTS scope_permissions (
    id INTEGER PRIMARY KEY,
    scope_address VARCHAR(44) NOT NULL,
    grantee_address VARCHAR(44) NOT NULL,
    granter_address VARCHAR(44) NOT NULL,
    created TIMESTAMPTZ NOT NULL
 );

CREATE INDEX IF NOT EXISTS scope_permissions_scope_address ON scope_permissions (scope_address);
CREATE UNIQUE INDEX IF NOT EXISTS scope_permissions_scope_address_grantee_address_granter_address ON scope_permissions (scope_address, grantee_address, granter_address);

CREATE TABLE IF NOT EXISTS block_height (
    "uuid" uuid NOT NULL PRIMARY KEY,
    height BIGINT NOT NULL,
    "timestamp" TIMESTAMPTZ NOT NULL
);
