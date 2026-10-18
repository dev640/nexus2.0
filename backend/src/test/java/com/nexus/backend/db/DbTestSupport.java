package com.nexus.backend.db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared plumbing for the multi-tenancy migration tests.
 *
 * <p>These tests deliberately use the plain Flyway API and raw JDBC against a
 * throwaway Postgres container. They are not @SpringBootTest: the JPA entities
 * do not know about company_id until phase 2, so booting the application here
 * would fail for reasons that have nothing to do with the migrations, and H2
 * cannot execute the Postgres-only SQL these migrations use (DO blocks, tsvector
 * generated columns, partial unique indexes, JSONB).
 */
final class DbTestSupport {

    /** Matches docker-compose.yml so tests exercise the same major version. */
    static final String IMAGE = "postgres:16-alpine";

    private DbTestSupport() {
    }

    static Connection open(PostgreSQLContainer<?> container) throws SQLException {
        return DriverManager.getConnection(
            container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    static void exec(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }

    /** Returns the single column of the single row the query produces. */
    static long queryLong(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("query produced no rows: " + sql);
                }
                long value = rs.getLong(1);
                if (rs.wasNull()) {
                    throw new IllegalStateException("query produced NULL: " + sql);
                }
                return value;
            }
        }
    }

    static String queryString(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("query produced no rows: " + sql);
                }
                return rs.getString(1);
            }
        }
    }

    /**
     * Asserts the statement is rejected by the database. Every failure mode we
     * rely on — FK violation, CHECK violation, unique violation — surfaces as a
     * SQLException, so a successful execution is the only failure of the test.
     */
    static void assertRejected(Connection c, String what, String sql, Object... params)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        } catch (SQLException expected) {
            return;
        }
        throw new AssertionError("expected the database to reject: " + what);
    }

    /** Row count per table, in a stable order for readable failure output. */
    static Map<String, Long> rowCounts(Connection c, String... tables) throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : tables) {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
                rs.next();
                counts.put(table, rs.getLong(1));
            }
        }
        return counts;
    }

    /**
     * Locates docs/rollback by walking up from the working directory. Maven
     * runs tests with the backend module as the working directory, so the
     * scripts sit one level up; walking makes the test robust to being run
     * from the repository root instead.
     */
    static Path rollbackDir() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("docs").resolve("rollback");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
            "docs/rollback not found above " + Path.of("").toAbsolutePath());
    }
}
