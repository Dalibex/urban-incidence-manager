-- Flyway migration V8: Replace storage_url with storage_key and add content_hash (ADR-0013)
ALTER TABLE attachment DROP COLUMN IF EXISTS storage_url;

ALTER TABLE attachment ADD COLUMN storage_key VARCHAR(255) NOT NULL;
ALTER TABLE attachment ADD COLUMN content_hash VARCHAR(64) NOT NULL;
