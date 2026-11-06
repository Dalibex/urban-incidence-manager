-- One photo per content and incident (ADR-0013, #192). A retry of an upload is recognised by the
-- SHA-256 of its content; the service looks it up before storing, and this index keeps two retries
-- that arrive at the same time from both being stored. It also serves the list of the photos of an
-- incident and that lookup, which both start by incident_id.
CREATE UNIQUE INDEX uq_attachment_incident_content_hash ON attachment (incident_id, content_hash);
