-- Urban context of an incident. Its status makes pending, partial, failed or unavailable
-- context explicit, so missing data is never shown as current.
CREATE TABLE urban_context (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id    uuid        NOT NULL,
    weather        varchar,
    traffic_status varchar,
    event          varchar,
    status         varchar     NOT NULL,
    captured_at    timestamptz,
    CONSTRAINT uq_urban_context_incident UNIQUE (incident_id),
    CONSTRAINT fk_urban_context_incident FOREIGN KEY (incident_id) REFERENCES incident (id),
    CONSTRAINT ck_urban_context_weather CHECK (weather IN ('SUNNY', 'WINDY', 'CLOUDY', 'PARTLY_CLOUDY', 'RAINY', 'STORMY', 'SNOWY', 'FOGGY')),
    CONSTRAINT ck_urban_context_traffic_status CHECK (traffic_status IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_urban_context_status CHECK (status IN ('PENDING', 'COMPLETE', 'PARTIAL', 'ERROR', 'UNAVAILABLE'))
);

-- Normalized data from an external source. Observation and ingestion instants are kept apart,
-- and value may be empty while the observation is pending or failed (unknown is not zero).
CREATE TABLE external_observation (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    urban_context_id    uuid        NOT NULL,
    source              varchar     NOT NULL,
    measurement_type    varchar     NOT NULL,
    value               varchar,
    unit                varchar,
    quality             varchar,
    observation_instant timestamptz,
    ingestion_date      timestamptz NOT NULL,
    expiration_date     timestamptz,
    status              varchar     NOT NULL,
    CONSTRAINT fk_external_observation_urban_context FOREIGN KEY (urban_context_id) REFERENCES urban_context (id),
    CONSTRAINT ck_external_observation_status CHECK (status IN ('VALID', 'EXPIRED', 'DISCARDED', 'PENDING', 'ERROR'))
);

CREATE TABLE urban_asset (
    id          uuid                   PRIMARY KEY DEFAULT gen_random_uuid(),
    external_id varchar                NOT NULL,
    type        varchar                NOT NULL,
    source      varchar                NOT NULL,
    geometry    geography(Point, 4326) NOT NULL,
    metadata    jsonb,
    CONSTRAINT ck_urban_asset_type CHECK (type IN ('TRAFFIC_LIGHT', 'WASTE_BIN', 'FOUNTAIN', 'BUS_STOP', 'PARKING', 'GREEN_ZONE'))
);

CREATE INDEX ix_urban_asset_geometry ON urban_asset USING GIST (geometry);

-- Candidate or confirmed link between an incident and a nearby asset. Several candidates can
-- exist for one incident; an uncertain match is never stored as confirmed.
CREATE TABLE incident_asset (
    id                   uuid             PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id          uuid             NOT NULL,
    urban_asset_id       uuid             NOT NULL,
    distance_to_incident double precision,
    confidence_level     double precision,
    status               varchar          NOT NULL,
    CONSTRAINT fk_incident_asset_incident FOREIGN KEY (incident_id) REFERENCES incident (id),
    CONSTRAINT fk_incident_asset_urban_asset FOREIGN KEY (urban_asset_id) REFERENCES urban_asset (id),
    CONSTRAINT ck_incident_asset_status CHECK (status IN ('CANDIDATE', 'CONFIRMED'))
);

-- Secrets such as API keys are not stored here: they come from the environment (ADR-0011).
CREATE TABLE data_source_config (
    id           uuid    PRIMARY KEY DEFAULT gen_random_uuid(),
    name         varchar NOT NULL,
    endpoint_url varchar NOT NULL,
    is_active    boolean NOT NULL DEFAULT true
);
