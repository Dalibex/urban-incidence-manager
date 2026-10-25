-- PostGIS provides the geography type and spatial indexes used by every module (ADR-0003).
-- The postgis/postgis image already ships the extension; IF NOT EXISTS keeps this migration
-- valid there and on a plain PostgreSQL server where it still has to be created.
CREATE EXTENSION IF NOT EXISTS postgis;
