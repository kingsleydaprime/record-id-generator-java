ALTER TABLE transactions
    ADD COLUMN source_hash VARCHAR(64),
    ADD UNIQUE INDEX ux_source_hash (source_hash);
