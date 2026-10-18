-- Reverse of V19__Add_multi_tenancy_integrity.sql
--
-- Apply AFTER the reverse of V21 and BEFORE the reverse of V18: the composite
-- foreign keys reference the (company_id, id) unique constraints and the
-- company_id columns, which V18's reverse removes.
--
-- No data is changed by this script — it only removes integrity constraints.
--
-- Flyway note: run this manually (or via Flyway repair after deleting the
-- V19 row from flyway_schema_history). It is not itself a Flyway migration.

-- User-reference composite twins.
ALTER TABLE tasks DROP CONSTRAINT fk_tasks_company_assignee;
ALTER TABLE chat_channel_members DROP CONSTRAINT fk_chat_channel_members_company_user;
ALTER TABLE chat_messages DROP CONSTRAINT fk_chat_messages_company_author;
ALTER TABLE messages DROP CONSTRAINT fk_messages_company_sender;
ALTER TABLE messages DROP CONSTRAINT fk_messages_company_recipient;
ALTER TABLE notifications DROP CONSTRAINT fk_notifications_company_user;
ALTER TABLE push_subscriptions DROP CONSTRAINT fk_push_subscriptions_company_user;
ALTER TABLE password_reset_requests DROP CONSTRAINT fk_password_reset_requests_company_user;
ALTER TABLE ai_conversations DROP CONSTRAINT fk_ai_conversations_company_user;
ALTER TABLE user_avatars DROP CONSTRAINT fk_user_avatars_company_user;

-- Tenant-pair composite twins.
ALTER TABLE tasks DROP CONSTRAINT fk_tasks_company_project;
ALTER TABLE tasks DROP CONSTRAINT fk_tasks_company_sprint;
ALTER TABLE sprints DROP CONSTRAINT fk_sprints_company_project;
ALTER TABLE projects DROP CONSTRAINT fk_projects_company_workspace;
ALTER TABLE wiki_pages DROP CONSTRAINT fk_wiki_pages_company_project;
ALTER TABLE chat_messages DROP CONSTRAINT fk_chat_messages_company_channel;
ALTER TABLE chat_channel_members DROP CONSTRAINT fk_chat_channel_members_company_channel;
ALTER TABLE ai_messages DROP CONSTRAINT fk_ai_messages_company_conversation;

-- The composite child-column indexes go with the constraints above.
DROP INDEX idx_tasks_company_assignee;
DROP INDEX idx_chat_channel_members_company_user;
DROP INDEX idx_chat_messages_company_author;
DROP INDEX idx_messages_company_sender;
DROP INDEX idx_messages_company_recipient;
DROP INDEX idx_notifications_company_user;
DROP INDEX idx_push_subscriptions_company_user;
DROP INDEX idx_password_reset_requests_company_user;
DROP INDEX idx_ai_conversations_company_user;
DROP INDEX idx_tasks_company_project;
DROP INDEX idx_tasks_company_sprint;
DROP INDEX idx_sprints_company_project;
DROP INDEX idx_projects_company_workspace;
DROP INDEX idx_wiki_pages_company_project;
DROP INDEX idx_chat_messages_company_channel;
DROP INDEX idx_chat_channel_members_company_channel;
DROP INDEX idx_ai_messages_company_conversation;

-- (company_id, id) unique targets (users last: the tenant-table uniques are
-- independent, but ordering keeps the script readable).
ALTER TABLE workspaces       DROP CONSTRAINT uq_workspaces_company_id;
ALTER TABLE projects         DROP CONSTRAINT uq_projects_company_id;
ALTER TABLE sprints          DROP CONSTRAINT uq_sprints_company_id;
ALTER TABLE chat_channels    DROP CONSTRAINT uq_chat_channels_company_id;
ALTER TABLE ai_conversations DROP CONSTRAINT uq_ai_conversations_company_id;
ALTER TABLE users            DROP CONSTRAINT uq_users_company_id;

-- employee_code: restore the global uniqueness of V11 and the column default.
-- The default is restored by re-adding V11's expression; the sequence object
-- (user_employee_code_seq) was never dropped.
ALTER TABLE users DROP CONSTRAINT chk_users_employee_code_by_role;
ALTER TABLE users DROP CONSTRAINT uq_users_company_employee_code;
CREATE UNIQUE INDEX ux_users_employee_code ON users (employee_code);
ALTER TABLE users ALTER COLUMN employee_code
    SET DEFAULT 'NX-' || LPAD(nextval('user_employee_code_seq')::text, 4, '0');

-- Email goes back to exact-match global uniqueness only.
DROP INDEX ux_users_email_lower;
