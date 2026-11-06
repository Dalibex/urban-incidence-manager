-- Indexes for the staff filters of the incident list (#182). Each one was measured with
-- EXPLAIN (ANALYZE, BUFFERS) on the perf dataset (100,000 incidents) before and after; the plans and
-- times are in docs/database.md, "Indexes". The category, priority and date filters need none: the
-- indexes of V10 already serve them.

-- Filter by department: the EXISTS over the active assignment of each incident, like
-- ix_assignment_active_technician for the technician. Only active assignments are looked up.
CREATE INDEX ix_assignment_active_department ON assignment (department_id, incident_id)
    WHERE is_active;

-- Sort by updatedAt, newest first. The id breaks ties of the same instant, like the ORDER BY of
-- the listing.
CREATE INDEX ix_incident_updated_at ON incident (updated_at DESC, id);
