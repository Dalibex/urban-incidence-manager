-- Categories and departments are rows, not enums, so an administrator can manage them.
CREATE TABLE category (
    id          uuid    PRIMARY KEY DEFAULT gen_random_uuid(),
    name        varchar NOT NULL,
    description varchar,
    is_active   boolean NOT NULL DEFAULT true
);

CREATE TABLE department (
    id        uuid    PRIMARY KEY DEFAULT gen_random_uuid(),
    name      varchar NOT NULL,
    is_active boolean NOT NULL DEFAULT true
);

-- Category is optional when reporting. Priority and its justification stay empty until an
-- operator or the assistant sets them; priority_setter_id records who decided it.
CREATE TABLE incident (
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_id        uuid        NOT NULL,
    priority_setter_id uuid,
    category_id        uuid,
    title              varchar     NOT NULL,
    description        text        NOT NULL,
    status             varchar     NOT NULL,
    priority           varchar,
    priority_reason    varchar,
    priority_source    varchar,
    version            integer     NOT NULL DEFAULT 0,
    reported_at        timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    closed_at          timestamptz,
    CONSTRAINT fk_incident_reporter FOREIGN KEY (reporter_id) REFERENCES users (id),
    CONSTRAINT fk_incident_priority_setter FOREIGN KEY (priority_setter_id) REFERENCES users (id),
    CONSTRAINT fk_incident_category FOREIGN KEY (category_id) REFERENCES category (id),
    CONSTRAINT ck_incident_status CHECK (status IN ('REPORTED', 'VALIDATED', 'REJECTED', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED', 'REOPENED', 'CLOSED')),
    CONSTRAINT ck_incident_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_incident_priority_source CHECK (priority_source IN ('MANUAL', 'ASSISTED'))
);

-- One location per incident. Address, neighborhood and district are optional so that a report
-- is accepted even when the geocoding source is unavailable.
CREATE TABLE location (
    id           uuid                   PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id  uuid                   NOT NULL,
    coordinates  geography(Point, 4326) NOT NULL,
    accuracy     double precision,
    address      varchar,
    neighborhood varchar,
    district     varchar,
    CONSTRAINT uq_location_incident UNIQUE (incident_id),
    CONSTRAINT fk_location_incident FOREIGN KEY (incident_id) REFERENCES incident (id)
);

CREATE INDEX ix_location_coordinates ON location USING GIST (coordinates);

-- previous_status is empty for the first change of an incident (its creation).
CREATE TABLE status_change (
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id        uuid        NOT NULL,
    actor_id           uuid        NOT NULL,
    instant            timestamptz NOT NULL,
    previous_status    varchar,
    new_status         varchar     NOT NULL,
    reason             varchar,
    associated_data    jsonb,
    visible_to_citizen boolean     NOT NULL,
    CONSTRAINT fk_status_change_incident FOREIGN KEY (incident_id) REFERENCES incident (id),
    CONSTRAINT fk_status_change_actor FOREIGN KEY (actor_id) REFERENCES users (id),
    CONSTRAINT ck_status_change_previous_status CHECK (previous_status IN ('REPORTED', 'VALIDATED', 'REJECTED', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED', 'REOPENED', 'CLOSED')),
    CONSTRAINT ck_status_change_new_status CHECK (new_status IN ('REPORTED', 'VALIDATED', 'REJECTED', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED', 'REOPENED', 'CLOSED'))
);

-- An incident is assigned to a department; the technician is optional.
CREATE TABLE assignment (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id   uuid        NOT NULL,
    department_id uuid        NOT NULL,
    technician_id uuid,
    team          varchar,
    assigned_at   timestamptz NOT NULL,
    is_active     boolean     NOT NULL DEFAULT true,
    CONSTRAINT fk_assignment_incident FOREIGN KEY (incident_id) REFERENCES incident (id),
    CONSTRAINT fk_assignment_department FOREIGN KEY (department_id) REFERENCES department (id),
    CONSTRAINT fk_assignment_technician FOREIGN KEY (technician_id) REFERENCES users (id)
);

CREATE TABLE comment (
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id        uuid        NOT NULL,
    author_id          uuid        NOT NULL,
    text               text        NOT NULL,
    created_at         timestamptz NOT NULL,
    visible_to_citizen boolean     NOT NULL,
    CONSTRAINT fk_comment_incident FOREIGN KEY (incident_id) REFERENCES incident (id),
    CONSTRAINT fk_comment_author FOREIGN KEY (author_id) REFERENCES users (id)
);

-- Files live outside the database; only their metadata and storage reference are kept here.
-- comment_id is set when the file was sent as part of a comment.
CREATE TABLE attachment (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id uuid        NOT NULL,
    comment_id  uuid,
    uploader_id uuid        NOT NULL,
    file_name   varchar     NOT NULL,
    file_type   varchar     NOT NULL,
    file_size   bigint      NOT NULL,
    storage_url varchar     NOT NULL,
    uploaded_at timestamptz NOT NULL,
    CONSTRAINT fk_attachment_incident FOREIGN KEY (incident_id) REFERENCES incident (id),
    CONSTRAINT fk_attachment_comment FOREIGN KEY (comment_id) REFERENCES comment (id),
    CONSTRAINT fk_attachment_uploader FOREIGN KEY (uploader_id) REFERENCES users (id)
);
