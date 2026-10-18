-- Reverse of V18__Add_multi_tenancy_core.sql
--
-- Apply AFTER the reverses of V19, V20 and V21 (in that order): the composite
-- foreign keys and audit_log must be gone before company_id columns can be
-- dropped, and V21's reverse has already recreated organizations.
--
-- DATA LOSS NOTICE: dropping company_id discards every tenant row's company
-- assignment. This script restores the V17 *shape*, not the company data —
-- take a pg_dump before rolling anything back.
--
-- Flyway note: run this manually (or via Flyway repair after deleting the
-- V18 row from flyway_schema_history). It is not itself a Flyway migration.

-- The role/company relationship rule and the role vocabulary go first: the
-- backfilled rows all have company_id, but dropping the CHECK that requires
-- it must precede dropping the column it inspects.
ALTER TABLE users DROP CONSTRAINT chk_users_company_by_role;
ALTER TABLE users DROP CONSTRAINT chk_users_role_valid;

-- Self-service password changes: column removed with its meaning.
ALTER TABLE users DROP COLUMN must_change_password;

-- Restore employee_code to its V11 state (NOT NULL); V19's reverse has
-- already restored the column DEFAULT and the global unique index.
-- Guarded so the script reports a clear error instead of failing midway if
-- any SUPER_ADMIN rows (NULL employee_code) still exist — delete or code them
-- first.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM users WHERE employee_code IS NULL) THEN
        RAISE EXCEPTION
            'users has rows with NULL employee_code; give them codes before reversing V18';
    END IF;
END $$;
ALTER TABLE users ALTER COLUMN employee_code SET NOT NULL;

-- Company foreign keys, then company_id columns, then the companies table
-- itself. Dropping the column also drops its index (idx_*_company_id), so no
-- separate DROP INDEX is needed.
ALTER TABLE users                   DROP CONSTRAINT fk_users_company;
ALTER TABLE workspaces              DROP CONSTRAINT fk_workspaces_company;
ALTER TABLE projects                DROP CONSTRAINT fk_projects_company;
ALTER TABLE sprints                 DROP CONSTRAINT fk_sprints_company;
ALTER TABLE tasks                   DROP CONSTRAINT fk_tasks_company;
ALTER TABLE wiki_pages              DROP CONSTRAINT fk_wiki_pages_company;
ALTER TABLE notifications           DROP CONSTRAINT fk_notifications_company;
ALTER TABLE whiteboard_notes        DROP CONSTRAINT fk_whiteboard_notes_company;
ALTER TABLE chat_channels           DROP CONSTRAINT fk_chat_channels_company;
ALTER TABLE chat_channel_members    DROP CONSTRAINT fk_chat_channel_members_company;
ALTER TABLE chat_messages           DROP CONSTRAINT fk_chat_messages_company;
ALTER TABLE user_avatars            DROP CONSTRAINT fk_user_avatars_company;
ALTER TABLE password_reset_requests DROP CONSTRAINT fk_password_reset_requests_company;
ALTER TABLE knowledge_chunks        DROP CONSTRAINT fk_knowledge_chunks_company;
ALTER TABLE ai_conversations        DROP CONSTRAINT fk_ai_conversations_company;
ALTER TABLE ai_messages             DROP CONSTRAINT fk_ai_messages_company;
ALTER TABLE messages                DROP CONSTRAINT fk_messages_company;
ALTER TABLE push_subscriptions      DROP CONSTRAINT fk_push_subscriptions_company;

ALTER TABLE users                  DROP COLUMN company_id;
ALTER TABLE workspaces             DROP COLUMN company_id;
ALTER TABLE projects               DROP COLUMN company_id;
ALTER TABLE sprints                DROP COLUMN company_id;
ALTER TABLE tasks                  DROP COLUMN company_id;
ALTER TABLE wiki_pages             DROP COLUMN company_id;
ALTER TABLE notifications          DROP COLUMN company_id;
ALTER TABLE whiteboard_notes       DROP COLUMN company_id;
ALTER TABLE chat_channels          DROP COLUMN company_id;
ALTER TABLE chat_channel_members   DROP COLUMN company_id;
ALTER TABLE chat_messages          DROP COLUMN company_id;
ALTER TABLE user_avatars           DROP COLUMN company_id;
ALTER TABLE password_reset_requests DROP COLUMN company_id;
ALTER TABLE knowledge_chunks       DROP COLUMN company_id;
ALTER TABLE ai_conversations       DROP COLUMN company_id;
ALTER TABLE ai_messages            DROP COLUMN company_id;
ALTER TABLE messages               DROP COLUMN company_id;
ALTER TABLE push_subscriptions     DROP COLUMN company_id;

-- Last reference to companies goes with the table (and its trigger).
DROP TABLE companies;
