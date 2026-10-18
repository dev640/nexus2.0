-- Multi-tenancy, phase 4 of 4 (remove the dead organizations demo seed).
--
-- organizations was the demo seed of V1/V2: one row ("Acme Corporation"),
-- referenced by exactly two columns, both of which nobody reads. Real
-- tenancy now lives in companies (V18), so the table and its two dangling
-- columns go. No CASCADE: every constraint is dropped explicitly so nothing
-- is removed that was not accounted for first.
DO $$
DECLARE
    r RECORD;
BEGIN
    -- Drop the foreign keys that point at organizations, by their real names
    -- in pg_constraint (they were created unnamed in V1, so the names are the
    -- Postgres defaults, but looking them up keeps this explicit and safe).
    FOR r IN
        SELECT c.conname, t.relname
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        WHERE c.contype = 'f'
          AND c.confrelid = 'organizations'::regclass
          AND t.relname IN ('workspaces', 'projects')
    LOOP
        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', r.relname, r.conname);
    END LOOP;
END $$;

ALTER TABLE workspaces DROP COLUMN organization_id;
ALTER TABLE projects DROP COLUMN organization_id;

DROP TABLE organizations;
