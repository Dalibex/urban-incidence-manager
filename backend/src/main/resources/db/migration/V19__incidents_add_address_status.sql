-- The normalized address of an incident, resolved from its position after the report (#375).
-- The report never waits for it: it saves the location with address_status PENDING, and the
-- address worker of the geospatial module fills it in afterwards.
--
-- address_status says why there is no address, so a missing one is never shown as current:
--   PENDING      the worker has not resolved it yet
--   AVAILABLE    address, address_source and address_resolved_at are set
--   OUTSIDE_AREA the position is outside the municipality of Málaga
--   UNAVAILABLE  the source has no street there, refused the request, or did not answer before
--                urbanpulse.address.max-delay
-- address_resolved_at is the instant the backend stored it, kept apart from reported_at.
ALTER TABLE location
    ADD COLUMN address_status      varchar,
    ADD COLUMN address_source      varchar,
    ADD COLUMN address_resolved_at timestamptz;

-- No location had a resolved address before this migration, so the existing incidents are shown
-- as unavailable instead of calling the source for each one of them.
UPDATE location
SET address_status = 'UNAVAILABLE',
    address        = NULL;

-- A new location waits for the worker, as the report leaves it.
ALTER TABLE location
    ALTER COLUMN address_status SET NOT NULL,
    ALTER COLUMN address_status SET DEFAULT 'PENDING',
    ADD CONSTRAINT ck_location_address_status
        CHECK (address_status IN ('PENDING', 'AVAILABLE', 'OUTSIDE_AREA', 'UNAVAILABLE')),
    -- An address is kept with its source and its instant, and only then.
    ADD CONSTRAINT ck_location_address_with_source
        CHECK ((address_status = 'AVAILABLE') = (address IS NOT NULL)
           AND (address IS NULL) = (address_source IS NULL)
           AND (address IS NULL) = (address_resolved_at IS NULL));

-- The address worker looks for the PENDING locations every 30 s, to resolve them and to expire the
-- old ones. They are a handful among every location ever reported, so the index holds only them:
-- without it each run reads the whole table (docs/datasets.md, Address worker).
CREATE INDEX ix_location_address_pending
    ON location (incident_id)
    WHERE address_status = 'PENDING';
