ALTER TABLE object_permissions
    ADD COLUMN granter_address VARCHAR(44),
    ADD COLUMN storage_key_address VARCHAR(44),
    ADD COLUMN is_object_with_meta BOOLEAN
;

WITH granter_lookup AS (
    SELECT op.object_hash, dsa.account_address
    FROM object_permissions op JOIN data_storage_accounts dsa
    ON op.grantee_address = dsa.account_address
)
UPDATE object_permissions op
SET is_object_with_meta = true,
    granter_address = granter_lookup.account_address
FROM granter_lookup
WHERE granter_lookup.object_hash = op.object_hash;

ALTER TABLE object_permissions
    ALTER COLUMN granter_address SET NOT NULL,
    ALTER COLUMN is_object_with_meta SET NOT NULL
;
--     ALTER COLUMN storage_key_address SET NOT NULL, -- todo: subsequent migration to make this true after this gets set on startup
