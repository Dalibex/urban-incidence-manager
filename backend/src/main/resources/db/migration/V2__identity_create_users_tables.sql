-- "user" is a reserved word in PostgreSQL, so the table is named users.
CREATE TABLE users (
    id            uuid    PRIMARY KEY DEFAULT gen_random_uuid(),
    name          varchar NOT NULL,
    email         varchar NOT NULL,
    password_hash varchar NOT NULL,
    role          varchar NOT NULL,
    enabled       boolean NOT NULL DEFAULT true,
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_role CHECK (role IN ('CITIZEN', 'MUNICIPAL_OPERATOR', 'TECHNICIAN', 'ADMINISTRATOR', 'ANALYST'))
);

-- Only the hash of a refresh token is stored (ADR-0004).
CREATE TABLE refresh_token (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL,
    token_hash varchar     NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE audit_log (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id    uuid        NOT NULL,
    instant     timestamptz NOT NULL,
    action      varchar     NOT NULL,
    target_id   uuid,
    target_type varchar,
    details     jsonb,
    CONSTRAINT fk_audit_log_actor FOREIGN KEY (actor_id) REFERENCES users (id)
);
