-- Reverse of V21__Cleanup_organizations.sql
--
-- Apply FIRST when rolling back the multi-tenancy migrations: it recreates
-- organizations, which the reverses of V19 and V18 must see in place.
--
-- STRUCTURE ONLY. The reverse recreates the organizations table and the two
-- organization_id columns exactly as V1 defined them, but the seeded demo row
-- ('Acme Corporation', id 1, from V2) and any organization_id values that
-- V21 discarded can only come back from a pg_dump backup taken before the
-- forward migration. The columns are recreated empty (NULL).
--
-- Flyway note: run this manually (or via Flyway repair after deleting the
-- V21 row from flyway_schema_history). It is not itself a Flyway migration.

-- Same shape V1 created (including the updated_at trigger function that still
-- exists, shared with the surviving tables).
CREATE TABLE organizations (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER update_organizations_updated_at BEFORE UPDATE ON organizations
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- The two columns and their foreign keys, as V1 declared them:
-- workspaces ON DELETE CASCADE, projects ON DELETE SET NULL.
ALTER TABLE workspaces ADD COLUMN organization_id BIGINT;
ALTER TABLE workspaces ADD CONSTRAINT workspaces_organization_id_fkey
    FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE;

ALTER TABLE projects ADD COLUMN organization_id BIGINT;
ALTER TABLE projects ADD CONSTRAINT projects_organization_id_fkey
    FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE SET NULL;
