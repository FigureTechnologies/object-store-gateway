ALTER TABLE object_permissions ADD COLUMN granter_address VARCHAR(44) NOT NULL;
ALTER TABLE object_permissions ADD COLUMN storage_key_address VARCHAR(44);
ALTER TABLE object_permissions ADD COLUMN is_object_with_meta BOOLEAN NOT NULL;
