package com.nexus.backend.db;

import static com.nexus.backend.db.DbTestSupport.assertRejected;
import static com.nexus.backend.db.DbTestSupport.exec;
import static com.nexus.backend.db.DbTestSupport.open;
import static com.nexus.backend.db.DbTestSupport.queryLong;
import static com.nexus.backend.db.DbTestSupport.rollbackDir;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Proves the documented reverses in docs/rollback actually return the schema
 * to its V17 shape. Runs the scripts in the documented order (V21 -> V18) on a
 * scratch database and then checks the three guarantees the rollback docs
 * make: company_id columns are gone, the global employee_code uniqueness is
 * back, and the role CHECK is gone.
 *
 * <p>The scripts are read from docs/rollback rather than copied into test
 * resources on purpose: if someone edits a rollback script, this test must
 * fail — a stale copy in src/test/resources would keep passing while the
 * documented procedure silently rotted.
 */
class MultiTenancyRollbackTest {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DbTestSupport.IMAGE);

    private static Connection conn;

    @BeforeAll
    static void migrateThenRollBack() throws Exception {
        POSTGRES.start();
        conn = open(POSTGRES);

        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();

        // The documented reverse order: last migration undone first.
        for (String version : new String[] {"V21", "V20", "V19", "V18"}) {
            Path script = rollbackDir().resolve(version + reverseSuffix(version));
            assertThat(script)
                .as("rollback script for " + version)
                .exists();
            // Execute the whole file as one batch, exactly as an operator
            // would with psql -f.
            String sql = Files.readString(script);
            try (var st = conn.createStatement()) {
                st.execute(sql);
            }
        }
    }

    @AfterAll
    static void stop() {
        try {
            if (conn != null) {
                conn.close();
            }
        } catch (Exception ignored) {
            // container shutdown is what matters
        }
        POSTGRES.stop();
    }

    private static String reverseSuffix(String version) {
        return switch (version) {
            case "V18" -> "__Add_multi_tenancy_core_reverse.sql";
            case "V19" -> "__Add_multi_tenancy_integrity_reverse.sql";
            case "V20" -> "__Add_audit_log_reverse.sql";
            case "V21" -> "__Cleanup_organizations_reverse.sql";
            default -> throw new IllegalArgumentException(version);
        };
    }

    @Test
    void companyColumnsAreGone() throws Exception {
        assertThat(queryLong(conn,
            "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE column_name = 'company_id' AND table_schema = 'public'"))
            .as("company_id columns must all be dropped")
            .isZero();
        assertThat(queryLong(conn,
            "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_name IN ('companies', 'audit_log') AND table_schema = 'public'"))
            .as("companies and audit_log must be dropped")
            .isZero();
        assertThat(queryLong(conn,
            "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE column_name = 'must_change_password' AND table_schema = 'public'"))
            .isZero();
    }

    @Test
    void organizationsIsBackInItsV17Shape() throws Exception {
        assertThat(queryLong(conn,
            "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_name = 'organizations' AND table_schema = 'public'"))
            .isEqualTo(1L);
        assertThat(queryLong(conn,
            "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_name IN ('workspaces', 'projects') "
                + "AND column_name = 'organization_id' AND table_schema = 'public'"))
            .as("both organization_id columns must be recreated")
            .isEqualTo(2L);
        // Structure only: the demo row is documented as unrestorable without a
        // backup, so the table comes back empty.
        assertThat(queryLong(conn, "SELECT COUNT(*) FROM organizations")).isZero();
    }

    @Test
    void globalEmployeeCodeUniquenessIsRestored() throws Exception {
        assertThat(queryLong(conn,
            "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' "
                + "AND indexname = 'ux_users_employee_code'"))
            .isEqualTo(1L);

        // And it actually rejects a duplicate again.
        assertRejected(conn, "duplicate employee_code after rollback",
            "INSERT INTO users (name, email, password, role, employee_code) "
                + "VALUES ('Dup', 'dup@rollback.test', 'x', 'MEMBER', 'NX-0001')");

        // The column default draws from the old sequence once more.
        exec(conn, "INSERT INTO users (name, email, password, role) "
            + "VALUES ('Fresh', 'fresh@rollback.test', 'x', 'MEMBER')");
        assertThat(queryLong(conn,
            "SELECT COUNT(*) FROM users WHERE email = 'fresh@rollback.test' "
                + "AND employee_code LIKE 'NX-%'"))
            .isEqualTo(1L);
    }

    @Test
    void roleCheckIsGoneAndV17ToleranceReturns() throws Exception {
        assertThat(queryLong(conn,
            "SELECT COUNT(*) FROM pg_constraint WHERE conname = 'chk_users_role_valid'"))
            .isZero();
        assertThat(queryLong(conn,
            "SELECT COUNT(*) FROM pg_constraint WHERE conname = 'chk_users_company_by_role'"))
            .isZero();
        // Every foreign key whose name mentions a company — the V18 links to
        // companies and all V19 composite twins — is gone.
        assertThat(queryLong(conn,
            "SELECT COUNT(*) FROM pg_constraint "
                + "WHERE conname LIKE 'fk\\_%company%' AND connamespace = 'public'::regnamespace"))
            .as("company foreign keys must all be dropped")
            .isZero();

        // A role outside the tenancy vocabulary is accepted again, exactly as
        // it was before V18.
        exec(conn, "INSERT INTO users (name, email, password, role, employee_code) "
            + "VALUES ('Arbitrary', 'arbitrary@rollback.test', 'x', 'BOSS', 'NX-0999')");
    }

    @Test
    void preTenancyWorkStillHangsTogether() throws Exception {
        // The original single-column FKs survived the rollback: a task cannot
        // point at a missing project.
        assertRejected(conn, "task pointing at a nonexistent project",
            "INSERT INTO tasks (title, project_id) VALUES ('x', 999999)");
        // And the demo project rows are intact.
        assertThat(queryLong(conn, "SELECT COUNT(*) FROM projects")).isPositive();
        assertThat(queryLong(conn, "SELECT COUNT(*) FROM workspaces")).isPositive();
    }
}
