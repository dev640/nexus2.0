package com.nexus.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.backend.domain.company.Company;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import com.nexus.backend.repository.CompanyRepository;
import com.nexus.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * What the cross-company listings actually return: every company with the status
 * an operator suspends and the count they lock out, and every account tagged
 * with the company it belongs to (the reason a flat cross-company list is useful
 * at all).
 */
class SuperAdminServiceTest {

    private final CompanyRepository companyRepository = mock(CompanyRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final SuperAdminService service = new SuperAdminService(companyRepository, userRepository);

    private static Company company(Long id, String name, String status) {
        Company c = new Company(name);
        c.setId(id);
        c.setStatus(status);
        return c;
    }

    private static User account(Long id, String email, Company company) {
        User u = new User("Ada", email, null, UserRole.MEMBER);
        u.setId(id);
        u.setCompany(company);
        return u;
    }

    @Test
    void listCompaniesCarriesStatusAndAccountCount() {
        when(companyRepository.findAll()).thenReturn(List.of(
            company(1L, "Acme", Company.STATUS_SUSPENDED),
            company(2L, "Globex", Company.STATUS_ACTIVE)));
        when(userRepository.countGroupedByCompany()).thenReturn(List.of(
            new Object[]{1L, 5L},
            new Object[]{2L, 3L}));

        var result = service.listCompanies();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).status()).isEqualTo(Company.STATUS_SUSPENDED);
        assertThat(result.get(0).employeeCount()).isEqualTo(5L);
        assertThat(result.get(1).status()).isEqualTo(Company.STATUS_ACTIVE);
        assertThat(result.get(1).employeeCount()).isEqualTo(3L);
        // One grouped query, not one count per company.
        verify(userRepository).countGroupedByCompany();
    }

    /** A company nobody has joined yet still appears, at zero accounts. */
    @Test
    void aCompanyWithNoAccountsCountsAsZero() {
        when(companyRepository.findAll()).thenReturn(List.of(company(9L, "Empty", Company.STATUS_ACTIVE)));
        when(userRepository.countGroupedByCompany()).thenReturn(List.of());

        assertThat(service.listCompanies()).singleElement()
            .satisfies(c -> assertThat(c.employeeCount()).isEqualTo(0L));
    }

    @Test
    void listAccountsTagsEachAccountWithItsCompanyAndState() {
        Company acme = company(1L, "Acme", Company.STATUS_SUSPENDED);
        when(userRepository.findAll()).thenReturn(List.of(
            account(9L, "ada@nexus.com", acme)));

        var result = service.listAccounts(null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).companyId()).isEqualTo(1L);
        assertThat(result.get(0).companyName()).isEqualTo("Acme");
        assertThat(result.get(0).companyStatus()).isEqualTo(Company.STATUS_SUSPENDED);
    }

    /** With a company id, only that company's accounts are read. */
    @Test
    void listAccountsCanBeScopedToOneCompany() {
        Company acme = company(1L, "Acme", Company.STATUS_ACTIVE);
        when(userRepository.findByCompanyIdOrderByIdAsc(1L))
            .thenReturn(List.of(account(9L, "ada@nexus.com", acme)));

        var result = service.listAccounts(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).companyId()).isEqualTo(1L);
        verify(userRepository).findByCompanyIdOrderByIdAsc(1L);
    }
}
