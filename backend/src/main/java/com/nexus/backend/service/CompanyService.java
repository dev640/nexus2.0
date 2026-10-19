package com.nexus.backend.service;

import com.nexus.backend.domain.audit.AuditAction;
import com.nexus.backend.domain.company.Company;
import com.nexus.backend.domain.user.UserRole;
import com.nexus.backend.dto.CreateCompanyRequest;
import com.nexus.backend.dto.CreatedCompanyResponse;
import com.nexus.backend.exception.DuplicateEmailException;
import com.nexus.backend.exception.ValidationException;
import com.nexus.backend.repository.CompanyRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;

/** Creates a company and its first administrator as one audited operation. */
@Service
public class CompanyService {

    private static final String DUPLICATE_COMPANY_MESSAGE = "A workspace with that name already exists";

    private final CompanyRepository companyRepository;
    private final AdminAccountService adminAccountService;
    private final AuditService auditService;

    public CompanyService(
        CompanyRepository companyRepository,
        AdminAccountService adminAccountService,
        AuditService auditService
    ) {
        this.companyRepository = companyRepository;
        this.adminAccountService = adminAccountService;
        this.auditService = auditService;
    }

    /**
     * SUPER_ADMIN acts outside any company, so this method intentionally does
     * not use CurrentTenant. The admin account is explicitly attached to the
     * newly created company.
     */
    @Transactional
    public CreatedCompanyResponse create(CreateCompanyRequest request) {
        String companyName = request == null || request.name() == null ? "" : request.name().trim();
        if (companyName.length() < 2 || companyName.length() > 200) {
            throw new ValidationException("Name must be between 2 and 200 characters");
        }

        if (companyRepository.findByNameIgnoreCase(companyName).isPresent()) {
            throw new ValidationException(DUPLICATE_COMPANY_MESSAGE);
        }

        Company company = new Company(companyName);
        Company savedCompany;
        try {
            savedCompany = companyRepository.save(company);
        } catch (DataIntegrityViolationException ex) {
            if (isDuplicateCompanyName(ex)) {
                throw new ValidationException(DUPLICATE_COMPANY_MESSAGE);
            }
            throw ex;
        }

        String adminName = request.adminName().trim();
        String adminEmail = normalizeEmail(request.adminEmail());
        AdminAccountService.ProvisionedUser provisioned;
        try {
            provisioned = adminAccountService.provisionUser(
                savedCompany.getId(), adminName, adminEmail, UserRole.ADMIN, null);
        } catch (DuplicateEmailException ex) {
            throw ex;
        }

        var admin = provisioned.user();
        auditService.record(AuditAction.COMPANY_CREATED, "Company", savedCompany.getId(),
            Map.of("name", savedCompany.getName(), "adminEmail", admin.getEmail()));

        return new CreatedCompanyResponse(
            savedCompany.getId(),
            savedCompany.getName(),
            admin.getId(),
            admin.getName(),
            admin.getEmail(),
            admin.getRole(),
            provisioned.password()
        );
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isDuplicateCompanyName(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                && "ux_companies_name_lower".equals(violation.getConstraintName())) {
                return true;
            }
            if (cause instanceof SQLException sql
                && "23505".equals(sql.getSQLState())
                && sql.getMessage() != null
                && sql.getMessage().contains("ux_companies_name_lower")) {
                return true;
            }
        }
        return false;
    }
}
