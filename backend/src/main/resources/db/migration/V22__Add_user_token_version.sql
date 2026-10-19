-- Local Nexus JWTs carry this account version. A password replacement bumps it,
-- invalidating every access and refresh token issued against the old version.
ALTER TABLE users
    ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;