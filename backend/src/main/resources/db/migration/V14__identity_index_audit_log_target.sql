-- The audit entries of one record (#47). The classification changes of an incident read its
-- CLASSIFY_INCIDENT entries by target_id, for the timeline of the detail. Measured with EXPLAIN
-- (ANALYZE, BUFFERS) on the perf dataset with 200,000 entries: 9.7 ms with a sequential scan of
-- audit_log, 0.1 ms with this index. Details in docs/database.md, "Indexes".
CREATE INDEX ix_audit_log_target_id ON audit_log (target_id);
