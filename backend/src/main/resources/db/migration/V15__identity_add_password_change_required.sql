-- An account an administrator creates must change its password at its first sign-in (#165), so
-- the password the administrator typed stops working. Existing accounts, citizens and the first
-- administrator are not obliged: false by default.
ALTER TABLE users
    ADD COLUMN password_change_required boolean NOT NULL DEFAULT false;
