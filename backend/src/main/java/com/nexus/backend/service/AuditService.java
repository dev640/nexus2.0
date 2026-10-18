package com.nexus.backend.service;

import com.nexus.backend.domain.audit.AuditAction;
import com.nexus.backend.domain.audit.AuditLog;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.repository.AuditLogRepository;
import com.nexus.backend.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

/**
 * Writes the audit trail.
 *
 * <p>Callers supply only <em>what happened</em>; who did it, from where, and
 * under which company are read from the request itself. That split is the point
 * — a call site cannot forget to record the actor, and cannot record the wrong
 * one, because it never supplies it.
 *
 * <p>A failure to write the row is <b>not</b> swallowed: it propagates and rolls
 * back the action it describes. The rule V20 states is that every administrative
 * or security-relevant action has an answerable record, so an action that cannot
 * be recorded has not been performed. Losing the row silently would be the one
 * failure mode that makes the trail worthless when it is finally needed.
 *
 * <p>The row joins the caller's transaction rather than opening its own, so the
 * record and the change it describes commit or roll back together.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /** Matches the {@code ip VARCHAR(64)} column, so a long value cannot fail the insert. */
    private static final int MAX_IP_LENGTH = 64;

    private final AuditLogRepository auditLogRepository;

    /** Records an action by the authenticated caller. */
    public void record(AuditAction action, String entityType, Object entityId) {
        record(action, entityType, entityId, null);
    }

    public void record(AuditAction action, String entityType, Object entityId, Map<String, Object> metadata) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        AuditLog row = newRow(action, entityType, entityId, metadata);

        if (authentication != null && !(authentication instanceof AnonymousAuthenticationToken)) {
            row.setActorEmail(authentication.getName());
            if (authentication.getPrincipal() instanceof com.nexus.backend.security.NexusUserPrincipal principal) {
                row.setActorUserId(principal.getUserId());
            }
        }

        save(row);
    }

    /**
     * Records an action by a specific account rather than by the caller.
     *
     * <p>Needed wherever the acting person is not the authenticated one: filing
     * a password-reset request is public, so the caller is anonymous while the
     * account the request is for is known. Without this the trail would say
     * only that <em>somebody</em> asked.
     */
    public void recordAs(User actor, AuditAction action, String entityType, Object entityId,
                         Map<String, Object> metadata) {
        AuditLog row = newRow(action, entityType, entityId, metadata);
        if (actor != null) {
            row.setActorUserId(actor.getId());
            row.setActorEmail(actor.getEmail());
            row.setCompanyId(actor.getCompanyId());
        }
        save(row);
    }

    private AuditLog newRow(AuditAction action, String entityType, Object entityId,
                            Map<String, Object> metadata) {
        AuditLog row = new AuditLog(
            action.name(),
            entityType,
            entityId == null ? null : String.valueOf(entityId));
        row.setCompanyId(TenantContext.getTenantId());
        row.setIp(currentIp());
        if (metadata != null && !metadata.isEmpty()) {
            row.setMetadata(metadata);
        }
        return row;
    }

    private void save(AuditLog row) {
        auditLogRepository.save(row);
        log.debug("audit {} {} {} by {}", row.getAction(), row.getEntityType(), row.getEntityId(),
            row.getActorEmail());
    }

    /**
     * The caller's address, or null outside a request (tests, scheduled work).
     *
     * <p>Reads {@code getRemoteAddr()} rather than an {@code X-Forwarded-For}
     * header. The header is trivially forged by the client, so a spoofable
     * address in the audit trail is worse than none; behind a proxy the real
     * client address is only trustworthy once the container itself has been told
     * to rewrite it, which is what {@code server.forward-headers-strategy} is
     * for. With that set, this reads the forwarded address — verified by the
     * container, not asserted by the caller.
     */
    private static String currentIp() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return null;
        }
        HttpServletRequest request = servletAttributes.getRequest();
        String address = request.getRemoteAddr();
        if (address == null) {
            return null;
        }
        return address.length() <= MAX_IP_LENGTH ? address : address.substring(0, MAX_IP_LENGTH);
    }
}
