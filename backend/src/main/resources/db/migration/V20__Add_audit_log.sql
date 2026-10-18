-- Multi-tenancy, phase 3 of 4 (audit trail).
--
-- Every administrative or security-relevant action in a company needs an
-- answerable record once several companies share one database: who did it,
-- to what, from where. actor_user_id deliberately has NO foreign key — an
-- audit row must survive the deletion of the account that performed the
-- action, so the email is stored alongside as the human-readable identity.
--
-- Rules for this table (enforced by the application, noted here because the
-- schema cannot see it): never store passwords, tokens or other secrets in
-- metadata.

CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    actor_user_id BIGINT,
    actor_email VARCHAR(255),
    company_id BIGINT NULL REFERENCES companies (id),
    action VARCHAR(100) NOT NULL,
    entity_type VARCHAR(100),
    entity_id VARCHAR(100),
    ip VARCHAR(64),
    metadata JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- The two read patterns: "everything this company did around time T", and the
-- global "what happened at time T" (used by SUPER_ADMIN tooling).
CREATE INDEX idx_audit_log_company_created ON audit_log (company_id, created_at);
CREATE INDEX idx_audit_log_created ON audit_log (created_at);
