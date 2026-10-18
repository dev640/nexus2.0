package com.nexus.backend.db;

import static com.nexus.backend.db.DbTestSupport.assertRejected;
import static com.nexus.backend.db.DbTestSupport.exec;
import static com.nexus.backend.db.DbTestSupport.open;
import static com.nexus.backend.db.DbTestSupport.queryLong;
import static com.nexus.backend.db.DbTestSupport.queryString;
import static com.nexus.backend.db.DbTestSupport.rowCounts;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Proves the multi-tenancy migrations (V18-V21) on a real Postgres.
 *
 * <p>The flow mirrors what the real database will go through: migrate the
 * pre-tenancy schema to V17, put a row into EVERY tenant table (several of
 * them — chat, AI, whiteboard, messages, push — are empty in a fresh
 * database, and the backfill must be exercised against them too), record the
 * row counts, then apply V18-V21 and verify that nothing was lost, everything
 * is stamped with the default company, and cross-company writes are rejected
 * by the database itself.
 *
 * <p>One ordered scenario rather than independent tests: each phase builds on
 * the schema the previous one verified, and the deletions at the end mutate
 * data no other phase depends on.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MultiTenancyMigrationTest {

    /** Every table that exists before and after the tenancy migrations. */
    private static final String[] TENANT_TABLES = {
        "users", "workspaces", "projects", "sprints", "tasks", "task_labels",
        "wiki_pages", "notifications", "whiteboard_notes", "chat_channels",
        "chat_channel_members", "chat_messages", "user_avatars",
        "password_reset_requests", "knowledge_chunks", "ai_conversations",
        "ai_messages", "messages", "push_subscriptions"
    };

    /** Tables that gained a NOT NULL company_id in V18. */
    private static final String[] STAMPED_TABLES = {
        "users", "workspaces", "projects", "sprints", "tasks", "wiki_pages",
        "notifications", "whiteboard_notes", "chat_channels",
        "chat_channel_members", "chat_messages", "user_avatars",
        "password_reset_requests", "knowledge_chunks", "ai_conversations",
        "ai_messages", "messages", "push_subscriptions"
    };

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DbTestSupport.IMAGE);

    private static Connection conn;
    private static Map<String, Long> countsBeforeTenancy;
    private static long defaultCompany;
    private static long otherCompany;
    private static long otherWorkspace;
    private static long otherProject;
    private static long otherSprint;
    private static long otherUser;
    private static long otherChannel;

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        POSTGRES.start();
        conn = open(POSTGRES);

        String version = queryString(conn, "SELECT version()");
        System.out.println("[migration-test] " + version);
        assertThat(version).startsWith("PostgreSQL 16");

        // 1. Pre-tenancy schema with the real V2 seed data.
        flywayTo("17").migrate();
        seedEveryEmptyTenantTable();

        // 2. Record counts, then apply the tenancy migrations.
        countsBeforeTenancy = rowCounts(conn, TENANT_TABLES);
        System.out.println("[migration-test] rows before V18: " + countsBeforeTenancy);
        flywayTo(null).migrate();
        System.out.println("[migration-test] rows after V21:  "
            + rowCounts(conn, TENANT_TABLES));

        defaultCompany = queryLong(conn, "SELECT id FROM companies WHERE name = 'Nexus HQ'");
    }

    @AfterAll
    static void stop() {
        try {
            if (conn != null) {
                conn.close();
            }
        } catch (Exception ignored) {
            // container shutdown below is what matters
        }
        POSTGRES.stop();
    }

    /** {@code target == null} means "migrate everything that is pending". */
    private static Flyway flywayTo(String target) {
        var config = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration");
        if (target != null) {
            config = config.target(MigrationVersion.fromVersion(target));
        }
        return config.load();
    }

    // ------------------------------------------------------------- phase 1: shape

    @Test
    @Order(1)
    void noRowIsLostAndEveryTenantRowBelongsToTheDefaultCompany() throws Exception {
        Map<String, Long> countsAfter = rowCounts(conn, TENANT_TABLES);
        assertThat(countsAfter).isEqualTo(countsBeforeTenancy);

        for (String table : STAMPED_TABLES) {
            long nulls = queryLong(conn,
                "SELECT COUNT(*) FROM " + table + " WHERE company_id IS NULL");
            assertThat(nulls).as("NULL company_id in " + table).isZero();

            long foreign = queryLong(conn,
                "SELECT COUNT(*) FROM " + table + " WHERE company_id <> " + defaultCompany);
            assertThat(foreign).as("rows of another company in " + table).isZero();
        }
    }

    @Test
    @Order(2)
    void employeeSeqContinuesAboveTheHighestExistingCode() throws Exception {
        // The seed codes are NX-0001..NX-0004 (V11 backfills from the id), so
        // the highest numeric part is 4 — parsed from the codes, not from ids.
        long maxCode = queryLong(conn,
            "SELECT MAX(NULLIF(regexp_replace(employee_code, '[^0-9]', '', 'g'), '')::BIGINT) "
                + "FROM users");
        long seq = queryLong(conn, "SELECT employee_seq FROM companies WHERE id = " + defaultCompany);
        assertThat(seq).isEqualTo(maxCode);
        assertThat(seq).isEqualTo(4L);
    }

    @Test
    @Order(3)
    void organizationsSeedAndItsColumnsAreGone() throws Exception {
        assertThat(queryLong(conn,
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'organizations'"))
            .isZero();
        assertThat(queryLong(conn,
            "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE column_name = 'organization_id' AND table_schema = 'public'"))
            .isZero();

        // The demo data it pointed at survives, rehomed onto the company.
        assertThat(queryLong(conn, "SELECT COUNT(*) FROM projects")).isPositive();
        assertThat(queryLong(conn, "SELECT COUNT(*) FROM workspaces")).isPositive();
    }

    @Test
    @Order(4)
    void everyConstraintFromTheMigrationsExists() throws Exception {
        String[] constraints = {
            // V18
            "chk_companies_status", "chk_users_role_valid", "chk_users_company_by_role",
            "fk_users_company",
            // V19 parents
            "uq_users_company_id", "uq_workspaces_company_id", "uq_projects_company_id",
            "uq_sprints_company_id", "uq_chat_channels_company_id",
            "uq_ai_conversations_company_id",
            // V19 tenant-pair twins
            "fk_tasks_company_project", "fk_tasks_company_sprint",
            "fk_sprints_company_project", "fk_projects_company_workspace",
            "fk_wiki_pages_company_project", "fk_chat_messages_company_channel",
            "fk_chat_channel_members_company_channel", "fk_ai_messages_company_conversation",
            // V19 user-reference twins
            "fk_tasks_company_assignee", "fk_chat_channel_members_company_user",
            "fk_chat_messages_company_author", "fk_messages_company_sender",
            "fk_messages_company_recipient", "fk_notifications_company_user",
            "fk_push_subscriptions_company_user", "fk_password_reset_requests_company_user",
            "fk_ai_conversations_company_user", "fk_user_avatars_company_user",
            // V19 employee code
            "uq_users_company_employee_code", "chk_users_employee_code_by_role"
        };
        for (String constraint : constraints) {
            long found = queryLong(conn,
                "SELECT COUNT(*) FROM pg_constraint WHERE conname = ? AND connamespace "
                    + "= 'public'::regnamespace", constraint);
            assertThat(found).as("constraint " + constraint).isEqualTo(1L);
        }

        // Index-backed guarantees.
        assertThat(queryLong(conn,
            "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' "
                + "AND indexname = 'ux_users_email_lower'")).isEqualTo(1L);
        assertThat(queryLong(conn,
            "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' "
                + "AND indexname = 'ux_users_employee_code'"))
            .as("the global employee_code unique index must be gone")
            .isZero();

        // The original single-column foreign keys survived (V19 must not drop them).
        for (String fk : new String[] {
            "tasks_project_id_fkey", "tasks_sprint_id_fkey", "tasks_assignee_id_fkey",
            "sprints_project_id_fkey", "projects_workspace_id_fkey",
            "chat_messages_channel_id_fkey", "chat_messages_author_id_fkey",
            "chat_channel_members_channel_id_fkey", "chat_channel_members_user_id_fkey",
            "ai_messages_conversation_id_fkey", "ai_conversations_user_id_fkey",
            "messages_sender_id_fkey", "messages_recipient_id_fkey",
            "notifications_user_id_fkey", "push_subscriptions_user_id_fkey",
            "password_reset_requests_user_id_fkey", "password_reset_requests_resolved_by_fkey"
        }) {
            long found = queryLong(conn,
                "SELECT COUNT(*) FROM pg_constraint WHERE conname = ? AND connamespace "
                    + "= 'public'::regnamespace", fk);
            assertThat(found).as("original FK " + fk).isEqualTo(1L);
        }
    }

    // ------------------------------------------------------- phase 2: second company

    @Test
    @Order(5)
    void aSecondCompanyCanDoEverythingTheFirstCan() throws Exception {
        otherCompany = queryLong(conn,
            "INSERT INTO companies (name) VALUES ('Other Co') RETURNING id");
        otherWorkspace = queryLong(conn,
            "INSERT INTO workspaces (name, company_id) VALUES ('Other WS', ?) RETURNING id",
            otherCompany);
        otherProject = queryLong(conn,
            "INSERT INTO projects (name, company_id, workspace_id) "
                + "VALUES ('Other Proj', ?, ?) RETURNING id", otherCompany, otherWorkspace);
        otherSprint = queryLong(conn,
            "INSERT INTO sprints (project_id, number, goal, start_date, end_date, company_id) "
                + "VALUES (?, 1, 'go', '2026-01-05', '2026-01-16', ?) RETURNING id",
            otherProject, otherCompany);
        // NX-0001 already exists in the default company: same code, other
        // company — allowed, because codes are unique per company only.
        otherUser = queryLong(conn,
            "INSERT INTO users (name, email, password, role, employee_code, company_id) "
                + "VALUES ('Other Person', 'other@other.test', 'x', 'MEMBER', 'NX-0001', ?) "
                + "RETURNING id", otherCompany);
        otherChannel = queryLong(conn,
            "INSERT INTO chat_channels (name, type, company_id) "
                + "VALUES ('other-general', 'PUBLIC', ?) RETURNING id", otherCompany);

        // Same-company work in the other company succeeds end to end.
        long task = queryLong(conn,
            "INSERT INTO tasks (title, project_id, sprint_id, assignee_id, company_id) "
                + "VALUES ('their task', ?, ?, ?, ?) RETURNING id",
            otherProject, otherSprint, otherUser, otherCompany);
        assertThat(task).isPositive();
        exec(conn, "UPDATE tasks SET sprint_id = NULL, assignee_id = NULL WHERE id = ?", task);
        exec(conn, "DELETE FROM tasks WHERE id = ?", task);
    }

    @Test
    @Order(6)
    void crossCompanyReferencesAreRejectedByTheDatabase() throws Exception {
        long ownTask = queryLong(conn,
            "INSERT INTO tasks (title, project_id, company_id) "
                + "VALUES ('probe', 1, " + defaultCompany + ") RETURNING id");

        // Tenant table -> tenant table, both directions of "wrong company".
        assertRejected(conn, "task in other company pointing at default company's project",
            "INSERT INTO tasks (title, project_id, company_id) VALUES ('x', 1, ?)",
            otherCompany);
        assertRejected(conn, "task in other company pointing at default company's sprint",
            "INSERT INTO tasks (title, project_id, sprint_id, company_id) "
                + "VALUES ('x', ?, 1, ?)", otherProject, otherCompany);
        assertRejected(conn, "sprint in other company pointing at default company's project",
            "INSERT INTO sprints (project_id, number, goal, start_date, end_date, company_id) "
                + "VALUES (1, 9, 'g', '2026-02-02', '2026-02-13', ?)", otherCompany);
        assertRejected(conn, "project in other company pointing at default company's workspace",
            "INSERT INTO projects (name, workspace_id, company_id) VALUES ('x', 1, ?)",
            otherCompany);

        // User references: assignee, message parties, chat, notifications.
        assertRejected(conn, "task assigned to a user of another company",
            "UPDATE tasks SET assignee_id = ? WHERE id = ?", otherUser, ownTask);
        assertRejected(conn, "message whose recipient belongs to another company",
            "INSERT INTO messages (sender_id, recipient_id, subject, body, company_id) "
                + "VALUES (1, ?, 's', 'b', ?)", otherUser, defaultCompany);
        assertRejected(conn, "message whose sender belongs to another company",
            "INSERT INTO messages (sender_id, recipient_id, subject, body, company_id) "
                + "VALUES (?, 1, 's', 'b', ?)", otherUser, defaultCompany);
        assertRejected(conn, "chat membership for a user of another company",
            "INSERT INTO chat_channel_members (channel_id, user_id, company_id) "
                + "VALUES (1, ?, ?)", otherUser, defaultCompany);
        assertRejected(conn, "chat message posted into another company's channel",
            "INSERT INTO chat_messages (channel_id, author_id, body, company_id) "
                + "VALUES (?, 1, 'hi', ?)", otherChannel, defaultCompany);
        assertRejected(conn, "notification for a user of another company",
            "INSERT INTO notifications (user_id, text, company_id) VALUES (?, 'n', ?)",
            otherUser, defaultCompany);
        assertRejected(conn, "avatar row for a user of another company",
            "INSERT INTO user_avatars (user_id, bytes, content_type, company_id) "
                + "VALUES (?, '\\x00'::bytea, 'image/png', ?)", otherUser, defaultCompany);
        assertRejected(conn, "AI conversation owned by a user of another company",
            "INSERT INTO ai_conversations (id, user_id, company_id) "
                + "VALUES ('33333333-3333-3333-3333-333333333333', ?, ?)",
            otherUser, defaultCompany);
        assertRejected(conn, "push subscription for a user of another company",
            "INSERT INTO push_subscriptions (user_id, endpoint, p256dh, auth, company_id) "
                + "VALUES (?, 'https://push/other', 'k', 'a', ?)", otherUser, defaultCompany);

        exec(conn, "DELETE FROM tasks WHERE id = ?", ownTask);
    }

    @Test
    @Order(7)
    void identityRulesHoldPerCompany() throws Exception {
        // Same code twice in the SAME company is rejected.
        assertRejected(conn, "duplicate employee_code within one company",
            "INSERT INTO users (name, email, password, role, employee_code, company_id) "
                + "VALUES ('Dup', 'dup1@nexus.test', 'x', 'MEMBER', 'NX-0001', ?)",
            defaultCompany);
        // Exact duplicate email is rejected globally.
        assertRejected(conn, "duplicate email",
            "INSERT INTO users (name, email, password, role, employee_code, company_id) "
                + "VALUES ('Dup', 'devendra@nexus.com', 'x', 'MEMBER', 'NX-0100', ?)",
            defaultCompany);
        // And so is a case variant of an existing email.
        assertRejected(conn, "email differing only by case",
            "INSERT INTO users (name, email, password, role, employee_code, company_id) "
                + "VALUES ('Dup', 'Devendra@Nexus.com', 'x', 'MEMBER', 'NX-0101', ?)",
            defaultCompany);

        // A SUPER_ADMIN belongs to no company and needs no code.
        long superAdmin = queryLong(conn,
            "INSERT INTO users (name, email, password, role, company_id, employee_code) "
                + "VALUES ('Root', 'root@nexus.test', 'x', 'SUPER_ADMIN', NULL, NULL) "
                + "RETURNING id");
        assertThat(superAdmin).isPositive();

        // Every other role must have a company; unknown roles are refused.
        assertRejected(conn, "non-SUPER_ADMIN without a company",
            "INSERT INTO users (name, email, password, role, employee_code, company_id) "
                + "VALUES ('NoCo', 'noco@nexus.test', 'x', 'MEMBER', 'NX-0102', NULL)");
        assertRejected(conn, "unknown role",
            "INSERT INTO users (name, email, password, role, employee_code, company_id) "
                + "VALUES ('Boss', 'boss@nexus.test', 'x', 'BOSS', 'NX-0103', ?)",
            defaultCompany);
        assertRejected(conn, "non-SUPER_ADMIN with an employee code but no company",
            "INSERT INTO users (name, email, password, role, employee_code, company_id) "
                + "VALUES ('Code', 'code@nexus.test', 'x', 'ADMIN', 'NX-0104', NULL)");

        // A reset request may be resolved by the global SUPER_ADMIN, which the
        // single-column resolved_by FK allows and no composite twin blocks.
        long request = queryLong(conn,
            "INSERT INTO password_reset_requests (user_id, company_id) VALUES (4, ?) "
                + "RETURNING id", defaultCompany);
        exec(conn,
            "UPDATE password_reset_requests SET resolved_by = ?, status = 'RESOLVED' "
                + "WHERE id = ?", superAdmin, request);
        exec(conn, "DELETE FROM password_reset_requests WHERE id = ?", request);
        exec(conn, "DELETE FROM users WHERE id = ?", superAdmin);
    }

    // ------------------------------------------------------- phase 3: delete a user

    /**
     * A user deletion must behave exactly as it did before tenancy: the rows
     * that cascade disappear, the references that used to be nulled become
     * NULL again, and the composite twins must not turn a legal delete into
     * an FK violation.
     */
    @Test
    @Order(8)
    void deletingACompanyUserKeepsItsPreTenancyBehaviour() throws Exception {
        long doomed = queryLong(conn,
            "INSERT INTO users (name, email, password, role, employee_code, company_id) "
                + "VALUES ('Doomed', 'doomed@nexus.test', 'x', 'MEMBER', 'NX-0099', ?) "
                + "RETURNING id", defaultCompany);

        long task = queryLong(conn,
            "INSERT INTO tasks (title, project_id, company_id, assignee_id) "
                + "VALUES ('t', 1, ?, ?) RETURNING id", defaultCompany, doomed);
        long message = queryLong(conn,
            "INSERT INTO chat_messages (channel_id, author_id, body, company_id) "
                + "VALUES (1, ?, 'bye', ?) RETURNING id", doomed, defaultCompany);
        long membership = queryLong(conn,
            "INSERT INTO chat_channel_members (channel_id, user_id, company_id) "
                + "VALUES (1, ?, ?) RETURNING id", doomed, defaultCompany);
        long notification = queryLong(conn,
            "INSERT INTO notifications (user_id, text, company_id) VALUES (?, 'n', ?) "
                + "RETURNING id", doomed, defaultCompany);
        long push = queryLong(conn,
            "INSERT INTO push_subscriptions (user_id, endpoint, p256dh, auth, company_id) "
                + "VALUES (?, 'https://push/doomed', 'k', 'a', ?) RETURNING id",
            doomed, defaultCompany);
        // user_avatars is keyed by user_id — there is no id column.
        long avatar = queryLong(conn,
            "INSERT INTO user_avatars (user_id, bytes, content_type, company_id) "
                + "VALUES (?, '\\x00'::bytea, 'image/png', ?) RETURNING user_id",
            doomed, defaultCompany);
        // ai_conversations / ai_messages are keyed by literal UUIDs.
        String conversation = "44444444-4444-4444-4444-444444444444";
        String aiMessage = "55555555-5555-5555-5555-555555555555";
        exec(conn,
            "INSERT INTO ai_conversations (id, user_id, company_id) "
                + "VALUES ('" + conversation + "', ?, ?)",
            doomed, defaultCompany);
        exec(conn,
            "INSERT INTO ai_messages (id, conversation_id, company_id, role, content) "
                + "VALUES ('" + aiMessage + "', '" + conversation + "', ?, 'user', 'q')",
            defaultCompany);
        long mail = queryLong(conn,
            "INSERT INTO messages (sender_id, recipient_id, subject, body, company_id) "
                + "VALUES (?, 1, 's', 'b', ?) RETURNING id", doomed, defaultCompany);
        long ownRequest = queryLong(conn,
            "INSERT INTO password_reset_requests (user_id, company_id) VALUES (?, ?) "
                + "RETURNING id", doomed, defaultCompany);
        // Another person's request, resolved by the doomed user: the resolver
        // reference is the SET NULL case.
        long foreignRequest = queryLong(conn,
            "INSERT INTO password_reset_requests (user_id, company_id) VALUES (4, ?) "
                + "RETURNING id", defaultCompany);
        exec(conn, "UPDATE password_reset_requests SET resolved_by = ? WHERE id = ?",
            doomed, foreignRequest);

        exec(conn, "DELETE FROM users WHERE id = ?", doomed);

        // SET NULL references keep their rows and lose the person.
        assertThat(queryLong(conn,
            "SELECT COUNT(*) FROM tasks WHERE id = ? AND assignee_id IS NULL", task))
            .isEqualTo(1L);
        assertThat(queryLong(conn,
            "SELECT COUNT(*) FROM chat_messages WHERE id = ? AND author_id IS NULL", message))
            .isEqualTo(1L);
        assertThat(queryLong(conn,
            "SELECT COUNT(*) FROM password_reset_requests "
                + "WHERE id = ? AND resolved_by IS NULL", foreignRequest))
            .isEqualTo(1L);

        // CASCADE rows are gone — with no orphans anywhere.
        for (String probe : new String[] {
            "SELECT COUNT(*) FROM chat_channel_members WHERE id = " + membership,
            "SELECT COUNT(*) FROM notifications WHERE id = " + notification,
            "SELECT COUNT(*) FROM push_subscriptions WHERE id = " + push,
            // user_avatars is keyed by user_id, not id.
            "SELECT COUNT(*) FROM user_avatars WHERE user_id = " + avatar,
            "SELECT COUNT(*) FROM ai_conversations WHERE id = '" + conversation + "'",
            "SELECT COUNT(*) FROM ai_messages WHERE id = '" + aiMessage + "'",
            "SELECT COUNT(*) FROM messages WHERE id = " + mail,
            "SELECT COUNT(*) FROM password_reset_requests WHERE id = " + ownRequest,
            "SELECT COUNT(*) FROM tasks WHERE assignee_id = " + doomed,
            "SELECT COUNT(*) FROM chat_messages WHERE author_id = " + doomed,
            "SELECT COUNT(*) FROM messages WHERE sender_id = " + doomed
                + " OR recipient_id = " + doomed,
            "SELECT COUNT(*) FROM chat_channel_members WHERE user_id = " + doomed,
            "SELECT COUNT(*) FROM notifications WHERE user_id = " + doomed,
            "SELECT COUNT(*) FROM push_subscriptions WHERE user_id = " + doomed,
            "SELECT COUNT(*) FROM user_avatars WHERE user_id = " + doomed,
            "SELECT COUNT(*) FROM ai_conversations WHERE user_id = " + doomed,
            "SELECT COUNT(*) FROM password_reset_requests "
                + "WHERE user_id = " + doomed + " OR resolved_by = " + doomed
        }) {
            assertThat(queryLong(conn, probe)).as("orphan: " + probe).isZero();
        }
    }

    /**
     * knowledge_chunks.owner_id has never had a foreign key, and V19 keeps it
     * that way on purpose: an orphan owner (a chunk whose owner was deleted)
     * must not block the delete or fail a re-index. What V19 does guarantee is
     * that the column is indexable per company so the application can filter
     * before ranking. This test pins both halves of that decision: an orphan
     * is accepted by the database, and the supporting index exists.
     */
    @Test
    @Order(9)
    void knowledgeChunkOwnerIsADocumentedException() throws Exception {
        long orphan = queryLong(conn,
            "INSERT INTO knowledge_chunks "
                + "(source_type, source_id, title, content, owner_id, company_id) "
                + "VALUES ('WIKI', 999, 'Orphan', 'body', 999999, ?) RETURNING id",
            defaultCompany);
        assertThat(orphan).isPositive();

        // The (company_id, owner_id) index from V19 exists for company-scoped
        // filtering; the pairing itself is the application's job (test B10).
        assertThat(queryLong(conn,
            "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' "
                + "AND indexname = 'idx_knowledge_chunks_company_owner'"))
            .isEqualTo(1L);
    }

    // ------------------------------------------------------------- fixtures

    /**
     * Puts one row into every tenant table the V2 seed leaves empty, so the
     * V18 backfill really touches each of them instead of only proving itself
     * against tables that happened to have data.
     */
    private static void seedEveryEmptyTenantTable() throws Exception {
        exec(conn, "INSERT INTO wiki_pages (title, content, project_id) "
            + "VALUES ('Seeding', 'body', 1)");
        exec(conn, "INSERT INTO notifications (user_id, category, text) "
            + "VALUES (1, 'TASKS', 'seed')");
        exec(conn, "INSERT INTO whiteboard_notes (board, text, author) "
            + "VALUES ('default', 'seed note', 'Devendra')");
        exec(conn, "INSERT INTO chat_channels (name, type, created_by) "
            + "VALUES ('general', 'PUBLIC', 'Devendra')");
        exec(conn, "INSERT INTO chat_channel_members (channel_id, user_id, is_admin) "
            + "VALUES (1, 1, TRUE)");
        exec(conn, "INSERT INTO chat_messages (channel_id, author_id, body) "
            + "VALUES (1, 1, 'hello')");
        exec(conn, "INSERT INTO user_avatars (user_id, bytes, content_type) "
            + "VALUES (1, '\\x00'::bytea, 'image/png')");
        exec(conn, "INSERT INTO password_reset_requests (user_id) VALUES (2)");
        exec(conn, "INSERT INTO knowledge_chunks "
            + "(source_type, source_id, title, content, owner_id) "
            + "VALUES ('WIKI', 1, 'Seeding', 'body', 1)");
        exec(conn, "INSERT INTO ai_conversations "
            + "(id, user_id, title) "
            + "VALUES ('11111111-1111-1111-1111-111111111111', 1, 'Seed chat')");
        exec(conn, "INSERT INTO ai_messages "
            + "(id, conversation_id, role, content) "
            + "VALUES ('22222222-2222-2222-2222-222222222222', "
            + "'11111111-1111-1111-1111-111111111111', 'user', 'hi')");
        exec(conn, "INSERT INTO messages (sender_id, recipient_id, subject, body) "
            + "VALUES (1, 2, 'Hello', 'body')");
        exec(conn, "INSERT INTO push_subscriptions (user_id, endpoint, p256dh, auth) "
            + "VALUES (1, 'https://push/seed', 'k', 'a')");
    }
}
