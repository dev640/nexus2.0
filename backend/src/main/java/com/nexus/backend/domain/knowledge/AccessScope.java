package com.nexus.backend.domain.knowledge;

/**
 * Who may retrieve a knowledge chunk. Every indexed item carries a scope so the
 * retrieval service can filter permission-aware in SQL and again against the
 * in-memory vector index before any context reaches the AI.
 *
 * The current workspace is shared (all members see all projects), so source
 * data indexes as WORKSPACE. PRIVATE reserves per-owner data and ADMIN reserves
 * admin-only data for when those models land.
 */
public enum AccessScope {
    /** Visible to every authenticated workspace member. */
    WORKSPACE,
    /** Visible only to the owning user. */
    PRIVATE,
    /** Visible only to users with the ADMIN role. */
    ADMIN
}
