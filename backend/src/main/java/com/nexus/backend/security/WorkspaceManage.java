package com.nexus.backend.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint as workspace administration rather than workspace content:
 * creating projects, and assigning work to other people. ADMIN and MANAGER are
 * allowed, while MEMBER, DEVELOPER and VIEWER get 403.
 *
 * This is deliberately narrower than {@link WorkspaceWrite}: contributing to an
 * existing project stays open to every writer, but deciding what exists and who
 * owns it is a management action.
 *
 * Composed meta-annotation, like WorkspaceWrite, so the role policy lives in
 * one place instead of being repeated on every endpoint.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public @interface WorkspaceManage {
}
