-- Uniqueness is now enforced in-process by the H2 in-memory registry,
-- so the secondary unique index on generated_id is no longer needed.
-- Removing it eliminates per-row B-tree maintenance on every insert.
ALTER TABLE transactions DROP INDEX ux_generated_id;
