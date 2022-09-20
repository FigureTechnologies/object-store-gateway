CREATE TABLE IF NOT EXISTS object_permissions (
    id BIGSERIAL PRIMARY KEY,
    object_hash TEXT NOT NULL,
    grantee_address VARCHAR(44) NOT NULL,
    object_size BIGINT NOT NULL,
    created TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS object_permissions_object_hash_grantee_address ON object_permissions (object_hash, grantee_address);
