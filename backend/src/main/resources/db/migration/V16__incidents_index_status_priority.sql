-- The staff lists filtered by status and priority count the matching incidents for their pages.
-- With only ix_incident_status_reported_at, that count read every incident of the statuses and
-- dropped the other priorities: 254 ms with 1,000,000 incidents. With both columns in one index it
-- is an index-only scan: 4 ms (backend/load-tests/practice/RESULTS.md, experiment 4).
CREATE INDEX ix_incident_status_priority_reported_at
    ON incident (status, priority, reported_at DESC, id);
