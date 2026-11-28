-- Consecutive wrong second-factor codes of each account (#435): kept here, not in memory, so a restart
-- does not give the attempts back. A valid code or an administrator's reset sets it back to zero; at
-- the limit of urbanpulse.security.mfa.max-consecutive-wrong-codes the code step is refused until a
-- reset. Existing accounts start at zero.
ALTER TABLE users
    ADD COLUMN mfa_failed_codes integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_users_mfa_failed_codes_not_negative CHECK (mfa_failed_codes >= 0);
