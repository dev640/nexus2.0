package com.nexus.backend.domain.user;

public enum UserRole {
    ADMIN,
    MEMBER,
    VIEWER,
    DEVELOPER,
    /**
     * Runs the workspace rather than contributing to it: may create projects
     * and hand work to other people. Appended last so no existing ordinal
     * moves.
     */
    MANAGER
}
