-- Promote id to a sequential BIGINT AUTO_INCREMENT clustered key.
-- The random 12-digit business ID moves to generated_id (unique index, not clustered).
-- This eliminates B-tree page splits on every insert caused by the random VARCHAR PK,
-- which is the primary cause of throughput degradation on large loads.
--
-- NOTE: requires an empty transactions table. Drop the volume / database before running
-- a fresh load so all three migrations execute on clean tables.
ALTER TABLE transactions
    DROP INDEX ux_source_hash,
    DROP COLUMN source_hash,
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT,
    ADD COLUMN generated_id VARCHAR(12) NOT NULL DEFAULT '' AFTER id,
    ADD UNIQUE INDEX ux_generated_id (generated_id);
