package com.nexus.backend.service;

import com.nexus.backend.domain.audit.AuditAction;
import com.nexus.backend.domain.company.Company;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import com.nexus.backend.dto.CreateCompanyRequest;
import com.nexus.backend.exception.DuplicateEmailException;
import com.nexus.backend.exception.ValidationException;
import com.nexus.backend.repository.CompanyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanyServiceTest {

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private AdminAccountService adminAccountService;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private CompanyService service;

    @Test
    void createsCompanyAndProvisionsItsFirstAdmin() {
        when(companyRepository.findByNameIgnoreCase("Acme")).thenReturn(Optional.empty());
        when(companyRepository.save(any(Company.class))).thenAnswer(invocation -> {
            Company company = invocation.getArgument(0);
            company.setId(51L);
            return company;
        });

        User admin = new User();
        admin.setId(72L);
        admin.setName("Ada Admin");
        admin.setEmail("ada@example.com");
        admin.setRole(UserRole.ADMIN);
        admin.setCompanyId(51L);
        admin.setMustChangePassword(true);
        var provisioned = new AdminAccountService.ProvisionedUser(admin, "one-time-password", true);
        when(adminAccountService.provisionUser(51L, "Ada Admin", "ada@example.com", UserRole.ADMIN, null))
            .thenReturn(provisioned);

        var response = service.create(new CreateCompanyRequest(
            " Acme ", " Ada Admin ", " Ada@Example.com "));

        ArgumentCaptor<Company> companyCaptor = ArgumentCaptor.forClass(Company.class);
        verify(companyRepository).save(companyCaptor.capture());
        Company savedCompany = companyCaptor.getValue();
        assertThat(savedCompany.getName()).isEqualTo("Acme");
        assertThat(savedCompany.getStatus()).isEqualTo(Company.STATUS_ACTIVE);
        assertThat(savedCompany.getEmployeeSeq()).isZero();

        verify(adminAccountService).provisionUser(
            51L, "Ada Admin", "ada@example.com", UserRole.ADMIN, null);
        verify(auditService).record(
            AuditAction.COMPANY_CREATED,
            "Company",
            51L,
            Map.of("name", "Acme", "adminEmail", "ada@example.com"));
        assertThat(response.companyId()).isEqualTo(51L);
        assertThat(response.adminId()).isEqualTo(72L);
        assertThat(response.role()).isEqualTo(UserRole.ADMIN);
        assertThat(response.generatedPassword()).isEqualTo("one-time-password");
        assertThat(admin.isMustChangePassword()).isTrue();
    }

    @Test
    void duplicateCompanyNameIsRefusedCaseInsensitively() {
        when(companyRepository.findByNameIgnoreCase("acme")).thenReturn(Optional.of(new Company("Acme")));

        assertThatThrownBy(() -> service.create(new CreateCompanyRequest(
            " acme ", "Ada Admin", "ada@example.com")))
            .isInstanceOf(ValidationException.class)
            .hasMessage("A workspace with that name already exists");

        verify(companyRepository, never()).save(any());
        verify(adminAccountService, never()).provisionUser(
            anyLong(), any(), any(), any(), any());
    }

    @Test
    void blankCompanyNameIsRefusedBeforePersistence() {
        assertThatThrownBy(() -> service.create(new CreateCompanyRequest(
            "  ", "Ada Admin", "ada@example.com")))
            .isInstanceOf(ValidationException.class);

        verify(companyRepository, never()).save(any());
        verify(adminAccountService, never()).provisionUser(
            anyLong(), any(), any(), any(), any());
    }

    @Test
    void concurrentUniqueIndexConflictBecomesAValidationError() {
        when(companyRepository.findByNameIgnoreCase("Acme")).thenReturn(Optional.empty());
        when(companyRepository.save(any(Company.class))).thenThrow(new DataIntegrityViolationException(
            "duplicate", new SQLException("violates ux_companies_name_lower", "23505")));

        assertThatThrownBy(() -> service.create(new CreateCompanyRequest(
            "Acme", "Ada Admin", "ada@example.com")))
            .isInstanceOf(ValidationException.class)
            .hasMessage("A workspace with that name already exists");

        verify(adminAccountService, never()).provisionUser(
            anyLong(), any(), any(), any(), any());
        verify(auditService, never()).record(
            eq(AuditAction.COMPANY_CREATED), any(), any(), any());
    }

    @Test
    void duplicateAdminEmailIsAConflictWithoutEchoingTheEmail() {
        when(companyRepository.findByNameIgnoreCase("Acme")).thenReturn(Optional.empty());
        when(companyRepository.save(any(Company.class))).thenAnswer(invocation -> {
            Company company = invocation.getArgument(0);
            company.setId(51L);
            return company;
        });
        when(adminAccountService.provisionUser(51L, "Ada Admin", "ada@example.com", UserRole.ADMIN, null))
            .thenThrow(new DuplicateEmailException());

        Throwable thrown = catchThrowable(() -> service.create(new CreateCompanyRequest(
            "Acme", "Ada Admin", "ada@example.com")));
        assertThat(thrown)
            .isInstanceOf(DuplicateEmailException.class)
            .hasMessage(DuplicateEmailException.MESSAGE);
        assertThat(thrown.getMessage()).doesNotContain("ada@example.com");

        verify(auditService, never()).record(
            eq(AuditAction.COMPANY_CREATED), any(), any(), any());
    }
}
