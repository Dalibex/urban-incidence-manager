-- Second factor of the staff (#57, ADR-0021). The TOTP secret is stored encrypted (AES-GCM, Base64 of
-- the IV and the ciphertext); null means the account has not enrolled. The last accepted time step
-- refuses a code that was already used, so it only exists with a secret.
ALTER TABLE users
    ADD COLUMN mfa_secret         text,
    ADD COLUMN mfa_last_time_step bigint,
    ADD CONSTRAINT ck_users_mfa_last_time_step_needs_secret
        CHECK (mfa_last_time_step IS NULL OR mfa_secret IS NOT NULL);

-- The first step of a sign-in that needs the second factor. It lives here, not in Redis, because it
-- is spent in the same transaction that issues the tokens and records ENROLL_MFA. Only the SHA-256
-- of the token is kept, like refresh_token. An enrolment keeps its pending secret (encrypted) in its
-- own challenge until a first valid code confirms it.
CREATE TABLE mfa_challenges (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid        NOT NULL,
    token_hash      text        NOT NULL,
    enrollment      boolean     NOT NULL,
    pending_secret  text,
    failed_attempts integer     NOT NULL DEFAULT 0,
    expires_at      timestamptz NOT NULL,
    spent_at        timestamptz,
    created_at      timestamptz NOT NULL,
    CONSTRAINT uq_mfa_challenges_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_mfa_challenges_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- A confirmed enrolment and a reset spend every open challenge of one user.
CREATE INDEX ix_mfa_challenges_user_id ON mfa_challenges (user_id);

-- A session opened with a valid code of the second factor, and its rotations. A refresh refuses a
-- session without it when the role of the user needs the second factor: one opened before #57, or
-- before a change of role or of MFA_REQUIRED_ROLES.
ALTER TABLE refresh_token
    ADD COLUMN second_factor boolean NOT NULL DEFAULT false;
