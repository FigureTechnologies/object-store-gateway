ALTER TABLE scope_permissions ADD COLUMN grant_id text;

CREATE INDEX IF NOT EXISTS scope_permissions_grant_id_idx ON scope_permissions(grant_id);
