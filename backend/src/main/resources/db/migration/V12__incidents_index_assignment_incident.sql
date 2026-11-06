-- The assignments of one incident (#184). Every staff detail returns the active assignment and
-- every assignment or reassignment deactivates the previous one, so both load the assignments of
-- the incident. Measured with EXPLAIN (ANALYZE, BUFFERS) on the perf dataset (80,000 assignments):
-- 4.4 ms with a sequential scan of assignment, 0.06 ms with this index. Details in
-- docs/database.md, "Indexes".
CREATE INDEX ix_assignment_incident_id ON assignment (incident_id);
