-- Multi-tenancy, phase 2 of 4 (integrity).
--
-- V18 gave every table a company_id. This migration makes the pairing
-- enforceable: a composite twin is added to every foreign key so that a child
-- row may only ever point at a parent row of the same company.
--
-- The original single-column foreign keys are all KEPT. They own the ON
-- DELETE behaviour (CASCADE, SET NULL) and must not change; the composite
-- twins use the default NO ACTION / MATCH SIMPLE and only police the company
-- pairing. With MATCH SIMPLE, a NULL reference column (unassigned task, task
-- with no sprint, deleted author) skips the check exactly as before.

-- ---------------------------------------------------------- tenant <-> tenant
-- Parent side: (company_id, id) is what the composite foreign keys target.
ALTER TABLE workspaces     ADD CONSTRAINT uq_workspaces_company_id     UNIQUE (company_id, id);
ALTER TABLE projects       ADD CONSTRAINT uq_projects_company_id       UNIQUE (company_id, id);
ALTER TABLE sprints        ADD CONSTRAINT uq_sprints_company_id        UNIQUE (company_id, id);
ALTER TABLE chat_channels  ADD CONSTRAINT uq_chat_channels_company_id  UNIQUE (company_id, id);
ALTER TABLE ai_conversations ADD CONSTRAINT uq_ai_conversations_company_id UNIQUE (company_id, id);

-- Child side: composite twins for every tenant-pair foreign key found in the
-- schema (all foreign keys between two tenant tables).
ALTER TABLE tasks ADD CONSTRAINT fk_tasks_company_project
    FOREIGN KEY (company_id, project_id) REFERENCES projects (company_id, id);
ALTER TABLE tasks ADD CONSTRAINT fk_tasks_company_sprint
    FOREIGN KEY (company_id, sprint_id) REFERENCES sprints (company_id, id);
ALTER TABLE sprints ADD CONSTRAINT fk_sprints_company_project
    FOREIGN KEY (company_id, project_id) REFERENCES projects (company_id, id);
ALTER TABLE projects ADD CONSTRAINT fk_projects_company_workspace
    FOREIGN KEY (company_id, workspace_id) REFERENCES workspaces (company_id, id);
ALTER TABLE wiki_pages ADD CONSTRAINT fk_wiki_pages_company_project
    FOREIGN KEY (company_id, project_id) REFERENCES projects (company_id, id);
ALTER TABLE chat_messages ADD CONSTRAINT fk_chat_messages_company_channel
    FOREIGN KEY (company_id, channel_id) REFERENCES chat_channels (company_id, id);
ALTER TABLE chat_channel_members ADD CONSTRAINT fk_chat_channel_members_company_channel
    FOREIGN KEY (company_id, channel_id) REFERENCES chat_channels (company_id, id);
ALTER TABLE ai_messages ADD CONSTRAINT fk_ai_messages_company_conversation
    FOREIGN KEY (company_id, conversation_id) REFERENCES ai_conversations (company_id, id);

-- task_labels -> tasks is the only tenant-pair foreign key without a twin:
-- task_labels is an @ElementCollection child table with no company_id of its
-- own (see V18). It stays reachable only through its tenant-scoped parent and
-- is documented as the explicit exception in docs/MULTI_TENANCY.md.

-- Indexes on the composite child columns, so the referential checks on parent
-- deletes and same-company lookups do not scan.
CREATE INDEX idx_tasks_company_project ON tasks (company_id, project_id);
CREATE INDEX idx_tasks_company_sprint  ON tasks (company_id, sprint_id);
CREATE INDEX idx_sprints_company_project ON sprints (company_id, project_id);
CREATE INDEX idx_projects_company_workspace ON projects (company_id, workspace_id);
CREATE INDEX idx_wiki_pages_company_project ON wiki_pages (company_id, project_id);
CREATE INDEX idx_chat_messages_company_channel ON chat_messages (company_id, channel_id);
CREATE INDEX idx_chat_channel_members_company_channel ON chat_channel_members (company_id, channel_id);
CREATE INDEX idx_ai_messages_company_conversation ON ai_messages (company_id, conversation_id);

-- ------------------------------------------------------------- user references
-- (company_id, id) is the target every user-reference composite key aims at.
ALTER TABLE users ADD CONSTRAINT uq_users_company_id UNIQUE (company_id, id);

ALTER TABLE tasks ADD CONSTRAINT fk_tasks_company_assignee
    FOREIGN KEY (company_id, assignee_id) REFERENCES users (company_id, id);
ALTER TABLE chat_channel_members ADD CONSTRAINT fk_chat_channel_members_company_user
    FOREIGN KEY (company_id, user_id) REFERENCES users (company_id, id);
ALTER TABLE chat_messages ADD CONSTRAINT fk_chat_messages_company_author
    FOREIGN KEY (company_id, author_id) REFERENCES users (company_id, id);
ALTER TABLE messages ADD CONSTRAINT fk_messages_company_sender
    FOREIGN KEY (company_id, sender_id) REFERENCES users (company_id, id);
ALTER TABLE messages ADD CONSTRAINT fk_messages_company_recipient
    FOREIGN KEY (company_id, recipient_id) REFERENCES users (company_id, id);
ALTER TABLE notifications ADD CONSTRAINT fk_notifications_company_user
    FOREIGN KEY (company_id, user_id) REFERENCES users (company_id, id);
ALTER TABLE push_subscriptions ADD CONSTRAINT fk_push_subscriptions_company_user
    FOREIGN KEY (company_id, user_id) REFERENCES users (company_id, id);
ALTER TABLE password_reset_requests ADD CONSTRAINT fk_password_reset_requests_company_user
    FOREIGN KEY (company_id, user_id) REFERENCES users (company_id, id);
ALTER TABLE ai_conversations ADD CONSTRAINT fk_ai_conversations_company_user
    FOREIGN KEY (company_id, user_id) REFERENCES users (company_id, id);
ALTER TABLE user_avatars ADD CONSTRAINT fk_user_avatars_company_user
    FOREIGN KEY (company_id, user_id) REFERENCES users (company_id, id);

-- Two deliberate exceptions, both documented here and in docs/MULTI_TENANCY.md:
--
-- * password_reset_requests.resolved_by keeps its single-column foreign key
--   only. The resolver may be a SUPER_ADMIN, whose company_id is NULL, so a
--   composite twin would reject the very accounts allowed to resolve resets.
--
-- * knowledge_chunks.owner_id has never had a foreign key (the chunk table is
--   a derived index kept in sync by the application). Adding one now could
--   fail on orphan rows and would block user deletion, so it is indexed for
--   same-company filtering only; the application enforces the pairing (and
--   test B10 covers it).

CREATE INDEX idx_tasks_company_assignee ON tasks (company_id, assignee_id);
CREATE INDEX idx_chat_channel_members_company_user ON chat_channel_members (company_id, user_id);
CREATE INDEX idx_chat_messages_company_author ON chat_messages (company_id, author_id);
CREATE INDEX idx_messages_company_sender ON messages (company_id, sender_id);
CREATE INDEX idx_messages_company_recipient ON messages (company_id, recipient_id);
CREATE INDEX idx_notifications_company_user ON notifications (company_id, user_id);
CREATE INDEX idx_push_subscriptions_company_user ON push_subscriptions (company_id, user_id);
CREATE INDEX idx_password_reset_requests_company_user ON password_reset_requests (company_id, user_id);
CREATE INDEX idx_ai_conversations_company_user ON ai_conversations (company_id, user_id);
-- user_avatars.user_id is already the primary key; a second index on the
-- composite would duplicate it for every lookup the foreign key performs.

CREATE INDEX idx_knowledge_chunks_company_owner ON knowledge_chunks (company_id, owner_id);

-- ------------------------------------------------------------- employee_code
-- Codes are unique per company, not globally: NX-0007 in one company may
-- repeat in another. V11's global unique index goes, and the database default
-- goes with it — the sequence it drew from is global, and code generation now
-- happens per company in the application. The sequence object itself is left
-- in place until it is proven unreferenced.
DROP INDEX ux_users_employee_code;
ALTER TABLE users ALTER COLUMN employee_code DROP DEFAULT;
ALTER TABLE users ADD CONSTRAINT uq_users_company_employee_code UNIQUE (company_id, employee_code);

-- SUPER_ADMIN is the only role without a code; everyone else must have one.
ALTER TABLE users ADD CONSTRAINT chk_users_employee_code_by_role
    CHECK (role = 'SUPER_ADMIN' OR employee_code IS NOT NULL);

-- ------------------------------------------------------------------ email
-- Login still resolves accounts by exact email (case sensitive), but two
-- accounts that differ only by case would be indistinguishable to a person
-- typing their address, so uniqueness is enforced case-insensitively.
CREATE UNIQUE INDEX ux_users_email_lower ON users (LOWER(email));
