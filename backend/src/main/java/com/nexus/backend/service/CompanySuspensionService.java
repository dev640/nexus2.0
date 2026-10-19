package com.nexus.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.backend.domain.audit.AuditAction;
import com.nexus.backend.domain.company.Company;
import com.nexus.backend.repository.CompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Suspends and reinstates a workspace.
 *
 * <p>Suspension is a hard lockout, not a soft flag. Setting the row is what the
 * servlet filter and the socket handshake read on every request, so it takes
 * effect on the next call; but a socket opened while the company was active
 * would keep streaming the chat and board its HTTP requests are now refused — so
 * this also closes the live sockets. That close is local to the instance handling
 * the request, so the same suspension is published on a Redis channel for the
 * other instances to apply to theirs (see {@code CompanySuspensionConfig}).
 *
 * <p>Redis is optional, exactly as for the whiteboard fan-out: a single-instance
 * deployment works with no Redis at all, and then the local close is the whole
 * effect. When Redis is present the control message reaches every instance, so a
 * multi-instance deployment locks a company out everywhere rather than only
 * wherever the request happened to land.
 *
 * <p>The audit row is written in the same transaction as the status change, so
 * the record and the change commit together — an action that cannot be recorded
 * is not performed (see {@link AuditService}).
 */
@Service
public class CompanySuspensionService {

    /** Control channel other instances listen on to close a company's sockets. */
    public static final String CHANNEL = "nexus:company:suspension";

    private static final Logger log = LoggerFactory.getLogger(CompanySuspensionService.class);

    private final CompanyRepository companyRepository;
    private final CompanySocketCloser socketCloser;
    private final AuditService auditService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CompanySuspensionService(
        CompanyRepository companyRepository,
        CompanySocketCloser socketCloser,
        AuditService auditService,
        ObjectProvider<StringRedisTemplate> redisTemplateProvider
    ) {
        this.companyRepository = companyRepository;
        this.socketCloser = socketCloser;
        this.auditService = auditService;
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
    }

    /**
     * Suspends a company: refuses all of its accounts and ends their live
     * sockets, here and — via Redis — on every other instance.
     */
    @Transactional
    public void suspend(Long companyId) {
        Company company = companyRepository.findById(companyId)
            .orElseThrow(() -> new com.nexus.backend.exception.ResourceNotFoundException(
                "Company", "id", companyId));
        if (company.isSuspended()) {
            return; // already suspended: idempotent, and no duplicate audit row
        }
        company.setStatus(Company.STATUS_SUSPENDED);
        companyRepository.save(company);

        auditService.record(AuditAction.COMPANY_SUSPENDED, "Company", company.getId(),
            Map.of("name", company.getName()));
        // After the commit boundary conceptually: the row must be written before the
        // sockets drop, so a client that reconnects and is refused at the handshake
        // finds the row already says suspended. Within one transaction the local
        // close is safe to do now; the publish is best-effort like Redis elsewhere.
        socketCloser.closeCompany(companyId);
        publish(companyId);
        log.info("Suspended company {} ({})", company.getId(), company.getName());
    }

    /**
     * Reinstates a suspended company. Its accounts may sign in again on their
     * next request; nothing has to be reopened, because a socket refused at the
     * handshake simply reconnects.
     */
    @Transactional
    public void resume(Long companyId) {
        Company company = companyRepository.findById(companyId)
            .orElseThrow(() -> new com.nexus.backend.exception.ResourceNotFoundException(
                "Company", "id", companyId));
        if (!company.isSuspended()) {
            return; // already active: idempotent, and no duplicate audit row
        }
        company.setStatus(Company.STATUS_ACTIVE);
        companyRepository.save(company);

        auditService.record(AuditAction.COMPANY_REINSTATED, "Company", company.getId(),
            Map.of("name", company.getName()));
        log.info("Reinstated company {} ({})", company.getId(), company.getName());
    }

    /**
     * Tells the other instances to close this company's sockets too. Best-effort:
     * Redis being absent or down leaves the local close in effect and the
     * instances that have not heard will refuse new handshakes anyway (they read
     * the same row), so the only gap is sockets they already hold — which the
     * next request's filter closes the account behind.
     */
    private void publish(Long companyId) {
        if (redisTemplate == null) return;
        try {
            redisTemplate.convertAndSend(CHANNEL, String.valueOf(companyId));
        } catch (Exception e) {
            log.warn("Redis suspension control unavailable, continuing locally: {}", e.getMessage());
        }
    }
}
