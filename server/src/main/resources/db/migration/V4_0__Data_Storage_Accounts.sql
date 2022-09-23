CREATE TABLE IF NOT EXISTS data_storage_accounts(
    account_address VARCHAR(44) PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created TIMESTAMPTZ NOT NULL
);
