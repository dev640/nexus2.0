package com.nexus.backend.service;

import com.nexus.backend.domain.company.Company;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.dto.AdminAccountResponse;
import com.nexus.backend.dto.CompanyAdminResponse;
import com.nexus.backend.repository.CompanyRepository;
import com.nexus.backend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The SUPER_ADMIN cross-company view: every company, and every account with the
 * company it belongs to.
 *
 * <p>Deliberately its own service that never reuses a tenant-scoped one.
 * {@code CurrentTenant.require()} answers a SUPER_ADMIN with 403 — they belong to
 * no company, so a company-scoped listing has no correct answer for them — which
 * is the right default for every workspace read but exactly wrong for the
 * operator who has to see across workspaces. These methods take no ambient
 * tenant, and they are only reachable from a {@code hasRole('SUPER_ADMIN')}
 * endpoint, so the two guards together are what make cross-company reads
 * possible without loosening the workspace rule that protects everyone else.
 *
 * <p>Read-only and audited-free: listing is not an action that changes state, so
 * it leaves no audit row (V20's rule is that a <em>change</em> has a record —
 * suspend/resume write theirs).
 */
@Service
public class SuperAdminService {

    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;

    public SuperAdminService(CompanyRepository companyRepository, UserRepository userRepository) {
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
    }

    /**
     * Every company with its suspension status and account count.
     *
     * <p>Counts come from one grouped query rather than a count per company, so
     * listing N companies is a fixed number of round trips.
     */
    @Transactional(readOnly = true)
    public List<CompanyAdminResponse> listCompanies() {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : userRepository.countGroupedByCompany()) {
            counts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return companyRepository.findAll().stream()
            .map(c -> new CompanyAdminResponse(
                c.getId(),
                c.getName(),
                c.getStatus(),
                counts.getOrDefault(c.getId(), 0L),
                c.getCreatedAt()))
            .toList();
    }

    /**
     * Accounts across companies (all of them, or one company's when an id is
     * given), each tagged with its company.
     *
     * <p>The company is a lazy relation, so this runs inside a transaction where
     * the proxy initializes on first read — outside one it would be a detached
     * proxy and {@code getName()} would throw instead of answering.
     *
     * @param companyId optional filter; null lists every company's accounts
     */
    @Transactional(readOnly = true)
    public List<AdminAccountResponse> listAccounts(Long companyId) {
        List<User> users = companyId == null
            ? userRepository.findAll()
            : userRepository.findByCompanyIdOrderByIdAsc(companyId);
        return users.stream().map(SuperAdminService::toAdminResponse).toList();
    }

    /** Maps one account, reading its company (already initialized in-transaction). */
    private static AdminAccountResponse toAdminResponse(User user) {
        Company company = user.getCompany();
        return new AdminAccountResponse(
            user.getId(),
            user.getName(),
            user.getEmail(),
            user.getRole(),
            user.getEmployeeCode(),
            user.isMustChangePassword(),
            user.getCompanyId(),
            company != null ? company.getName() : null,
            company != null ? company.getStatus() : null);
    }
}
