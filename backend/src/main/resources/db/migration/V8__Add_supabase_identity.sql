-- Supabase auth integration: each Supabase Auth user has its own UUID. We store
-- it as a secondary identity on the local user row, so a person can sign in
-- through Supabase and still own their numeric app ID, tasks and history.
-- The column is nullable: accounts created by the built-in email/password
-- flow have no Supabase identity until they first sign in through Supabase
-- with the same email (at which point the rows are linked, never duplicated).

ALTER TABLE users ADD COLUMN supabase_id UUID;

-- One local account per Supabase identity; multiple NULLs are allowed.
CREATE UNIQUE INDEX users_supabase_id_key ON users (supabase_id) WHERE supabase_id IS NOT NULL;

-- Supabase-managed accounts authenticate with Supabase-issued JWTs, so no
-- local password hash exists for them.
ALTER TABLE users ALTER COLUMN password DROP NOT NULL;
