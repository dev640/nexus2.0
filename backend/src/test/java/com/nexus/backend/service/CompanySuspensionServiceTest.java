package com.nexus.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.backend.domain.audit.AuditAction;
import com.nexus.backend.domain.company.Company;
import com.nexus.backend.exception.ResourceNotFoundException;
import com.nexus.backend.repository.CompanyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;
import java.util.Optional;

/**
 * What suspending a company actually does beyond flipping a row: it ends the
 * live sockets on this instance, tells the other instances to end theirs, and
 * leaves an audit row — and it is idempotent, so a repeated operator action does
 * not double-close or double-record.
 */
class CompanySuspensionServiceTest {

    private final CompanyRepository companyRepository = mock(CompanyRepository.class);
    private final CompanySocketCloser socketCloser = mock(CompanySocketCloser.class);
    private final AuditService auditService = mock(AuditService.class);

    @SuppressWarnings("unchecked")
    private static ObjectProvider<StringRedisTemplate> provider(StringRedisTemplate template) {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(template);
        return provider;
    }

    private CompanySuspensionService service(StringRedisTemplate redis) {
        return new CompanySuspensionService(
            companyRepository, socketCloser, auditService, provider(redis));
    }

    private static Company company(String status) {
        Company company = new Company("Acme");
        company.setId(3L);
        company.setStatus(status);
        return company;
    }

    @Test
    void suspendMarksTheRowAndClosesSockets() {
        Company company = company(Company.STATUS_ACTIVE);
        when(companyRepository.findById(3L)).thenReturn(Optional.of(company));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);

        service(redis).suspend(3L);

        verify(companyRepository).save(company);
        assertThat(company.isSuspended()).isTrue();
        verify(socketCloser).closeCompany(3L);
        verify(auditService).record(eq(AuditAction.COMPANY_SUSPENDED), eq("Company"), eq(3L), any());
    }

    @Test
    void suspendPublishesAControlMessageForOtherInstances() {
        when(companyRepository.findById(3L)).thenReturn(Optional.of(company(Company.STATUS_ACTIVE)));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);

        service(redis).suspend(3L);

        verify(redis).convertAndSend(CompanySuspensionService.CHANNEL, "3");
    }

    /** Re-suspending is a no-op: no second close, no duplicate audit row. */
    @Test
    void suspendIsIdempotent() {
        when(companyRepository.findById(3L))
            .thenReturn(Optional.of(company(Company.STATUS_SUSPENDED)));

        service(mock(StringRedisTemplate.class)).suspend(3L);

        verify(socketCloser, never()).closeCompany(any());
        verify(auditService, never()).record(any(), anyString(), any(), any());
    }

    @Test
    void resumeRestoresTheRow() {
        Company company = company(Company.STATUS_SUSPENDED);
        when(companyRepository.findById(3L)).thenReturn(Optional.of(company));

        service(mock(StringRedisTemplate.class)).resume(3L);

        verify(companyRepository).save(company);
        assertThat(company.isSuspended()).isFalse();
        verify(auditService).record(eq(AuditAction.COMPANY_REINSTATED), eq("Company"), eq(3L), any());
    }

    /** An unknown company is a 404, not a silent success that looks like a lockout. */
    @Test
    void suspendingAnUnknownCompanyFails() {
        when(companyRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(mock(StringRedisTemplate.class)).suspend(404L))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(socketCloser, never()).closeCompany(any());
    }

    /** Redis being absent must not stop the local lockout. */
    @Test
    void suspendStillClosesSocketsWithoutRedis() {
        when(companyRepository.findById(3L)).thenReturn(Optional.of(company(Company.STATUS_ACTIVE)));

        service(null).suspend(3L);

        verify(socketCloser).closeCompany(3L);
    }
}
