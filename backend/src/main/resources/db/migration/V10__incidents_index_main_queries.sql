-- Indexes for the main queries of the incidents module (#132). Each one was measured with
-- EXPLAIN (ANALYZE, BUFFERS) on the perf dataset (100,000 incidents) before and after; the plans and
-- times are in docs/database.md, "Indexes". Every index has a write cost, so none is added without
-- a measured query behind it.

-- My reports: a citizen's incidents, newest first, and their count. The id breaks ties of the
-- same instant, like the ORDER BY of the listing.
CREATE INDEX ix_incident_reporter_reported_at ON incident (reporter_id, reported_at DESC, id);

-- Operator inbox without a status filter (the default view), newest first. The technician's list
-- walks it too and checks the assignment of each incident.
CREATE INDEX ix_incident_reported_at ON incident (reported_at DESC, id);

-- Operator inbox filtered by one status, newest first, and its count.
CREATE INDEX ix_incident_status_reported_at ON incident (status, reported_at DESC, id);

-- What a technician sees: the EXISTS over their active assignments, for the list, its count and
-- the detail. Only active assignments are ever looked up this way.
CREATE INDEX ix_assignment_active_technician ON assignment (technician_id, incident_id)
    WHERE is_active;

-- The history of one incident. It has a handful of entries, so sorting them is cheaper than a
-- wider index on (incident_id, instant, id), which measured four times larger.
CREATE INDEX ix_status_change_incident_id ON status_change (incident_id);
