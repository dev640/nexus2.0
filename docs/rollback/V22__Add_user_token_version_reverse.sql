-- Reverse of V22__Add_user_token_version.sql
-- This restores the V21 schema shape; any token-version history is discarded.
ALTER TABLE users DROP COLUMN token_version;