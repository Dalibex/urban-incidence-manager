-- The Idempotency-Key of createIncident (#289): a retry of the same report returns the incident
-- already created instead of a second one. The citizen's app chooses the key, so it is unique per
-- reporter, not across the table: another citizen who sends the same key gets their own incident.
-- The hash of the request tells a retry from a different report sent with the same key. Both are
-- kept for as long as the incident exists; a report sent without the header leaves both empty, and
-- PostgreSQL does not compare empty keys, so those reports never collide.
--
-- The service locks the reporter's row before it looks the key up, so two requests with the same
-- key at once are checked one after the other; the constraint is the database rule behind it. Its
-- index starts by reporter_id and also serves that lookup.
ALTER TABLE incident
    ADD COLUMN idempotency_key          uuid,
    ADD COLUMN idempotency_request_hash varchar(64),
    ADD CONSTRAINT uq_incident_reporter_idempotency_key UNIQUE (reporter_id, idempotency_key),
    ADD CONSTRAINT ck_incident_idempotency_key_with_hash
        CHECK ((idempotency_key IS NULL) = (idempotency_request_hash IS NULL));
