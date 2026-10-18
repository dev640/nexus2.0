-- Reverse of V20__Add_audit_log.sql
--
-- Apply AFTER the reverse of V21 and BEFORE the reverses of V19 and V18:
-- audit_log's company_id references companies, which V18's reverse drops.
--
-- DATA LOSS NOTICE: this deletes every recorded audit row. Export the table
-- first if the history matters:
--   COPY (SELECT * FROM audit_log) TO '/tmp/audit_log.csv' WITH CSV HEADER;
--
-- Flyway note: run this manually (or via Flyway repair after deleting the
-- V20 row from flyway_schema_history). It is not itself a Flyway migration.

DROP TABLE audit_log;
