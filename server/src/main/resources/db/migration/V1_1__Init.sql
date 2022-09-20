CREATE TABLE IF NOT EXISTS scope_permissions (
    id INTEGER PRIMARY KEY,
    scope_address VARCHAR(44) NOT NULL,
    grantee_address VARCHAR(44) NOT NULL,
    granter_address VARCHAR(44) NOT NULL,
    created TIMESTAMPTZ NOT NULL
 );

CREATE TABLE IF NOT EXISTS block_height (
    "uuid" uuid NOT NULL PRIMARY KEY,
    height BIGINT NOT NULL,
    "timestamp" TIMESTAMPTZ NOT NULL
);
