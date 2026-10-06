package com.nexus.backend.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint as a workspace-content write. VIEWER accounts are
 * read-only by policy, so any endpoint annotated with this rejects them with
 * 403 while leaving ADMIN, MEMBER and DEVELOPER unaffected.
 *
 * Personal state (own profile, notification read flags, chat read receipts,
 * joining a channel to read it) is deliberately NOT guarded: a read-only user
 * still manages their own inbox and presence.
 *
 * Composed meta-annotation so the role policy lives in exactly one place —
 * when project-scoped membership rules arrive, only this file changes.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("!hasRole('VIEWER')")
public @interface WorkspaceWrite {
}
