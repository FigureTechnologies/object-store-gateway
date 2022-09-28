-- The original index from V1_1 may have been established as a constraint.  This will ensure the constraint is removed
-- before attempting to drop the index.
ALTER TABLE scope_permissions DROP CONSTRAINT IF EXISTS scope_permissions_scope_address_grantee_address_granter_address;
-- Remove old index that enforces unique on scope_address/grantee_address/granter_address in preparation for new index
-- in the v3_1 migration.
DROP INDEX IF EXISTS scope_permissions_scope_address_grantee_address_granter_address;
