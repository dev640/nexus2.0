package com.nexus.backend.web;

import com.nexus.backend.dto.AdminAccountResponse;
import com.nexus.backend.dto.CompanyAdminResponse;
import com.nexus.backend.dto.CreateCompanyRequest;
import com.nexus.backend.dto.CreatedCompanyResponse;
import com.nexus.backend.service.CompanyService;
import com.nexus.backend.service.CompanySuspensionService;
import com.nexus.backend.service.SuperAdminService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * The SUPER_ADMIN cross-company surface: create and see workspaces and their
 * people, and suspend or reinstate one.
 *
 * <p>All of it is deliberately cross-company: a suspension targets a company by
 * id and locks its people out of every session, and the listings exist precisely
 * because {@code CurrentTenant.require()} refuses a SUPER_ADMIN (who has no
 * company) with 403. So this controller takes its company from the path or query
 * rather than from the ambient tenant, and every route is
 * {@code hasRole('SUPER_ADMIN')} — the one place a cross-company view is allowed,
 * never a tenant-scoped method reused with the guard relaxed. See
 * {@link SuperAdminService}.
 *
 * <p>Enforcement of a suspension does not depend on this endpoint: the filter,
 * the login/refresh checks and the socket handshake all read the company row, so
 * setting it here is what locks the workspace out on the next request.
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class SuperAdminController {

    private final CompanySuspensionService suspensionService;
    private final SuperAdminService superAdminService;
    private final CompanyService companyService;

    public SuperAdminController(
        CompanySuspensionService suspensionService,
        SuperAdminService superAdminService,
        CompanyService companyService
    ) {
        this.suspensionService = suspensionService;
        this.superAdminService = superAdminService;
        this.companyService = companyService;
    }

    /** Every company with its suspension status and account count. */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @GetMapping("/companies")
    public ResponseEntity<List<CompanyAdminResponse>> listCompanies() {
        return ResponseEntity.ok(superAdminService.listCompanies());
    }

    /** Creates a company and its first administrator as one audited operation. */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PostMapping("/companies")
    public ResponseEntity<CreatedCompanyResponse> createCompany(
        @Valid @RequestBody CreateCompanyRequest request
    ) {
        CreatedCompanyResponse created = companyService.create(request);
        return ResponseEntity.created(URI.create("/api/admin/companies"))
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(created);
    }

    /**
     * Accounts across companies — all of them, or one company's with
     * {@code ?companyId=}. Each is tagged with its company so the operator can
     * tell at a glance whose account they are looking at.
     */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @GetMapping("/accounts")
    public ResponseEntity<List<AdminAccountResponse>> listAccounts(
        @RequestParam(required = false) Long companyId
    ) {
        return ResponseEntity.ok(superAdminService.listAccounts(companyId));
    }

    /** Locks a company's accounts out and ends its live sockets. Idempotent. */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PostMapping("/companies/{id}/suspend")
    public ResponseEntity<Void> suspend(@PathVariable Long id) {
        suspensionService.suspend(id);
        return ResponseEntity.ok().build();
    }

    /** Restores a suspended company's access. Idempotent. */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PostMapping("/companies/{id}/resume")
    public ResponseEntity<Void> resume(@PathVariable Long id) {
        suspensionService.resume(id);
        return ResponseEntity.ok().build();
    }
}
