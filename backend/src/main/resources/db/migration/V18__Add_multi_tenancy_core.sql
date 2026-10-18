-- Multi-tenancy, phase 1 of 4 (structure + backfill).
--
-- Every piece of business data in Nexus now belongs to exactly one company.
-- This migration creates the companies table, adds company_id to every tenant
-- table, stamps every existing row with the single default company, and
-- constrains users so that a SUPER_ADMIN is global (company_id NULL) while
-- every other role belongs to exactly one company.
--
-- Integrity is layered on in V19 (composite foreign keys), audit in V20 and
-- the dead organizations demo seed is removed in V21.

-- ------------------------------------------------------------------ companies
CREATE TABLE companies (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    legal_name VARCHAR(200),
    registration_no VARCHAR(100),
    industry VARCHAR(100),
    address TEXT,
    city VARCHAR(100),
    country VARCHAR(100),
    contact_person VARCHAR(100),
    contact_email VARCHAR(255),
    contact_phone VARCHAR(50),
    website VARCHAR(255),
    company_size VARCHAR(50),
    logo BYTEA,
    logo_content_type VARCHAR(50),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    employee_seq BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_companies_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

CREATE UNIQUE INDEX ux_companies_name_lower ON companies (LOWER(name));
CREATE UNIQUE INDEX ux_companies_registration_no
    ON companies (registration_no) WHERE registration_no IS NOT NULL;

CREATE TRIGGER update_companies_updated_at BEFORE UPDATE ON companies
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ------------------------------------------------------- company_id columns
-- Nullable for the length of this migration so the backfill can run first;
-- tenant tables become NOT NULL below, users stays nullable forever because a
-- SUPER_ADMIN is not a member of any company.
ALTER TABLE users                  ADD COLUMN company_id BIGINT;
ALTER TABLE workspaces             ADD COLUMN company_id BIGINT;
ALTER TABLE projects               ADD COLUMN company_id BIGINT;
ALTER TABLE sprints                ADD COLUMN company_id BIGINT;
ALTER TABLE tasks                  ADD COLUMN company_id BIGINT;
ALTER TABLE wiki_pages             ADD COLUMN company_id BIGINT;
ALTER TABLE notifications          ADD COLUMN company_id BIGINT;
ALTER TABLE whiteboard_notes       ADD COLUMN company_id BIGINT;
ALTER TABLE chat_channels          ADD COLUMN company_id BIGINT;
ALTER TABLE chat_channel_members   ADD COLUMN company_id BIGINT;
ALTER TABLE chat_messages          ADD COLUMN company_id BIGINT;
ALTER TABLE user_avatars           ADD COLUMN company_id BIGINT;
ALTER TABLE password_reset_requests ADD COLUMN company_id BIGINT;
ALTER TABLE knowledge_chunks       ADD COLUMN company_id BIGINT;
ALTER TABLE ai_conversations       ADD COLUMN company_id BIGINT;
ALTER TABLE ai_messages            ADD COLUMN company_id BIGINT;
ALTER TABLE messages               ADD COLUMN company_id BIGINT;
ALTER TABLE push_subscriptions     ADD COLUMN company_id BIGINT;

-- task_labels is deliberately absent: it is an @ElementCollection child table
-- with no company_id of its own. It is reachable only through its
-- tenant-scoped parent (tasks) and is documented as an explicit exception in
-- docs/MULTI_TENANCY.md for the Phase 4 ArchUnit test.

-- ------------------------------------------------- default company + backfill
-- The default company is created WITHOUT an explicit id; its id is captured
-- here and used for every backfill statement below.
DO $$
DECLARE
    v_default_company_id BIGINT;
BEGIN
    INSERT INTO companies (name) VALUES ('Nexus HQ')
    RETURNING id INTO v_default_company_id;

    UPDATE users                   SET company_id = v_default_company_id;
    UPDATE workspaces              SET company_id = v_default_company_id;
    UPDATE projects                SET company_id = v_default_company_id;
    UPDATE sprints                 SET company_id = v_default_company_id;
    UPDATE tasks                   SET company_id = v_default_company_id;
    UPDATE wiki_pages              SET company_id = v_default_company_id;
    UPDATE notifications           SET company_id = v_default_company_id;
    UPDATE whiteboard_notes        SET company_id = v_default_company_id;
    UPDATE chat_channels           SET company_id = v_default_company_id;
    UPDATE chat_channel_members    SET company_id = v_default_company_id;
    UPDATE chat_messages           SET company_id = v_default_company_id;
    UPDATE user_avatars            SET company_id = v_default_company_id;
    UPDATE password_reset_requests SET company_id = v_default_company_id;
    UPDATE knowledge_chunks        SET company_id = v_default_company_id;
    UPDATE ai_conversations        SET company_id = v_default_company_id;
    UPDATE ai_messages             SET company_id = v_default_company_id;
    UPDATE messages                SET company_id = v_default_company_id;
    UPDATE push_subscriptions      SET company_id = v_default_company_id;

    -- Per-company employee code numbering continues above the highest number
    -- any existing code contains. The digits are parsed out of the codes
    -- (NX-0007 -> 7) instead of assuming the code mirrors the user id.
    UPDATE companies
    SET employee_seq = COALESCE(
        (SELECT MAX(NULLIF(regexp_replace(employee_code, '[^0-9]', '', 'g'), '')::BIGINT)
         FROM users),
        0)
    WHERE id = v_default_company_id;
END $$;

-- --------------------------------------------------------- NOT NULL (tenant)
-- users.company_id stays nullable on purpose: SUPER_ADMIN has no company.
ALTER TABLE workspaces              ALTER COLUMN company_id SET NOT NULL;
ALTER TABLE projects                ALTER COLUMN company_id SET NOT NULL;
ALTER TABLE sprints                 ALTER COLUMN company_id SET NOT NULL;
ALTER TABLE tasks                   ALTER COLUMN company_id SET NOT NULL;
ALTER TABLE wiki_pages              ALTER COLUMN company_id SET NOT NULL;
ALTER TABLE notifications           ALTER COLUMN company_id SET NOT NULL;
ALTER TABLE whiteboard_notes        ALTER COLUMN company_id SET NOT NULL;
ALTER TABLE chat_channels           ALTER COLUMN company_id SET NOT NULL;
ALTER TABLE chat_channel_members    ALTER COLUMN company_id SET NOT NULL;
ALTER TABLE chat_messages           ALTER COLUMN company_id SET NOT NULL;
ALTER TABLE user_avatars            ALTER COLUMN company_id SET NOT NULL;
ALTER TABLE password_reset_requests ALTER COLUMN company_id SET NOT NULL;
ALTER TABLE knowledge_chunks        ALTER COLUMN company_id SET NOT NULL;
ALTER TABLE ai_conversations        ALTER COLUMN company_id SET NOT NULL;
ALTER TABLE ai_messages             ALTER COLUMN company_id SET NOT NULL;
ALTER TABLE messages                ALTER COLUMN company_id SET NOT NULL;
ALTER TABLE push_subscriptions      ALTER COLUMN company_id SET NOT NULL;

-- --------------------------------------------------- FK to companies + index
ALTER TABLE users                   ADD CONSTRAINT fk_users_company
    FOREIGN KEY (company_id) REFERENCES companies (id);
ALTER TABLE workspaces              ADD CONSTRAINT fk_workspaces_company
    FOREIGN KEY (company_id) REFERENCES companies (id);
ALTER TABLE projects                ADD CONSTRAINT fk_projects_company
    FOREIGN KEY (company_id) REFERENCES companies (id);
ALTER TABLE sprints                 ADD CONSTRAINT fk_sprints_company
    FOREIGN KEY (company_id) REFERENCES companies (id);
ALTER TABLE tasks                   ADD CONSTRAINT fk_tasks_company
    FOREIGN KEY (company_id) REFERENCES companies (id);
ALTER TABLE wiki_pages              ADD CONSTRAINT fk_wiki_pages_company
    FOREIGN KEY (company_id) REFERENCES companies (id);
ALTER TABLE notifications           ADD CONSTRAINT fk_notifications_company
    FOREIGN KEY (company_id) REFERENCES companies (id);
ALTER TABLE whiteboard_notes        ADD CONSTRAINT fk_whiteboard_notes_company
    FOREIGN KEY (company_id) REFERENCES companies (id);
ALTER TABLE chat_channels           ADD CONSTRAINT fk_chat_channels_company
    FOREIGN KEY (company_id) REFERENCES companies (id);
ALTER TABLE chat_channel_members    ADD CONSTRAINT fk_chat_channel_members_company
    FOREIGN KEY (company_id) REFERENCES companies (id);
ALTER TABLE chat_messages           ADD CONSTRAINT fk_chat_messages_company
    FOREIGN KEY (company_id) REFERENCES companies (id);
ALTER TABLE user_avatars            ADD CONSTRAINT fk_user_avatars_company
    FOREIGN KEY (company_id) REFERENCES companies (id);
ALTER TABLE password_reset_requests ADD CONSTRAINT fk_password_reset_requests_company
    FOREIGN KEY (company_id) REFERENCES companies (id);
ALTER TABLE knowledge_chunks        ADD CONSTRAINT fk_knowledge_chunks_company
    FOREIGN KEY (company_id) REFERENCES companies (id);
ALTER TABLE ai_conversations        ADD CONSTRAINT fk_ai_conversations_company
    FOREIGN KEY (company_id) REFERENCES companies (id);
ALTER TABLE ai_messages             ADD CONSTRAINT fk_ai_messages_company
    FOREIGN KEY (company_id) REFERENCES companies (id);
ALTER TABLE messages                ADD CONSTRAINT fk_messages_company
    FOREIGN KEY (company_id) REFERENCES companies (id);
ALTER TABLE push_subscriptions      ADD CONSTRAINT fk_push_subscriptions_company
    FOREIGN KEY (company_id) REFERENCES companies (id);

CREATE INDEX idx_users_company_id                  ON users (company_id);
CREATE INDEX idx_workspaces_company_id             ON workspaces (company_id);
CREATE INDEX idx_projects_company_id               ON projects (company_id);
CREATE INDEX idx_sprints_company_id                ON sprints (company_id);
CREATE INDEX idx_tasks_company_id                  ON tasks (company_id);
CREATE INDEX idx_wiki_pages_company_id             ON wiki_pages (company_id);
CREATE INDEX idx_notifications_company_id          ON notifications (company_id);
CREATE INDEX idx_whiteboard_notes_company_id       ON whiteboard_notes (company_id);
CREATE INDEX idx_chat_channels_company_id          ON chat_channels (company_id);
CREATE INDEX idx_chat_channel_members_company_id   ON chat_channel_members (company_id);
CREATE INDEX idx_chat_messages_company_id          ON chat_messages (company_id);
CREATE INDEX idx_user_avatars_company_id           ON user_avatars (company_id);
CREATE INDEX idx_password_reset_requests_company_id ON password_reset_requests (company_id);
CREATE INDEX idx_knowledge_chunks_company_id       ON knowledge_chunks (company_id);
CREATE INDEX idx_ai_conversations_company_id       ON ai_conversations (company_id);
CREATE INDEX idx_ai_messages_company_id            ON ai_messages (company_id);
CREATE INDEX idx_messages_company_id               ON messages (company_id);
CREATE INDEX idx_push_subscriptions_company_id     ON push_subscriptions (company_id);

-- ------------------------------------------------------------ users columns
-- Self-service password changes become possible in phase 2; the flag forces a
-- user who was issued a generated password to pick their own.
ALTER TABLE users ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT false;

-- SUPER_ADMIN accounts have no employee code, so the column must be nullable.
-- V19 drops the global default and re-scopes uniqueness per company.
ALTER TABLE users ALTER COLUMN employee_code DROP NOT NULL;

-- Role vocabulary and the rule that ties a role to its company.
ALTER TABLE users ADD CONSTRAINT chk_users_role_valid
    CHECK (role IN ('ADMIN', 'MEMBER', 'VIEWER', 'DEVELOPER', 'MANAGER', 'SUPER_ADMIN'));

ALTER TABLE users ADD CONSTRAINT chk_users_company_by_role
    CHECK (
        (role = 'SUPER_ADMIN' AND company_id IS NULL)
        OR (role <> 'SUPER_ADMIN' AND company_id IS NOT NULL)
    );
