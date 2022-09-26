ALTER TABLE scope_permissions ADD COLUMN grant_id text;

CREATE INDEX IF NOT EXISTS scope_permissions_grant_id_idx ON scope_permissions(grant_id);

-- Ensures that there can be duplicate grants from granters to grantees on a scope address, as long as the duplicate
-- specifies a newly-unique grant id
CREATE UNIQUE INDEX IF NOT EXISTS scope_permissions_scope_adr_grantee_adr_granter_adr_grant_id_nn
    ON scope_permissions (scope_address, grantee_address, granter_address, grant_id)
    WHERE grant_id IS NOT NULL;

-- Companion index to the above index.  Nulls in indices are not handled in the way one might think, where they indicate
-- another unique value.  Instead, they are disregarded and any number of records that include null as a value are
-- allowed.  To circumvent this, two unique indices can be created that handle the null and non-null case of a single
-- column.  Fortunately, only grant_id is nullable, which makes this combo relatively simplistic to declare.
CREATE UNIQUE INDEX IF NOT EXISTS scope_permissions_scope_adr_grantee_adr_granter_adr_grant_id_nl
    ON scope_permissions (scope_address, grantee_address, granter_address)
    WHERE grant_id IS NULL;
