-- Every refresh looks the token up by its hash, and a hash must point to one token only.
CREATE UNIQUE INDEX uq_refresh_token_token_hash ON refresh_token (token_hash);

-- Sign-out of every session (disabled account) revokes the tokens of one user.
CREATE INDEX ix_refresh_token_user_id ON refresh_token (user_id);
