package com.nexus.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexus.backend.domain.company.Company;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import com.nexus.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

/**
 * The socket handshake's suspension rule.
 *
 * <p>The servlet filter refuses a suspended company's HTTP requests, but a
 * browser opens a socket with a token in the query and no principal in scope, so
 * the filter never sees it. Without this the workspace's people would keep
 * streaming chat and whiteboard traffic while their API calls bounced — the same
 * hole the password-change gate closes, for the same reason.
 */
class SocketPrincipalResolverSuspensionTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final SocketPrincipalResolver resolver = new SocketPrincipalResolver(userRepository);

    private void find(User user) {
        when(userRepository.findByEmailWithCompany("ada@nexus.com")).thenReturn(Optional.of(user));
    }

    private static User account(String companyStatus) {
        User user = new User("Ada", "ada@nexus.com", null, UserRole.MEMBER);
        user.setId(9L);
        Company company = new Company("Acme");
        company.setId(3L);
        if (companyStatus != null) {
            company.setStatus(companyStatus);
        }
        user.setCompany(company);
        return user;
    }

    @Test
    void refusesAnAccountWhoseCompanyIsSuspended() {
        find(account(Company.STATUS_SUSPENDED));

        assertThat(resolver.resolve("ada@nexus.com")).isEmpty();
    }

    @Test
    void resolvesAnAccountWhoseCompanyIsActive() {
        find(account(Company.STATUS_ACTIVE));

        assertThat(resolver.resolve("ada@nexus.com")).isPresent();
    }

    /** A company with no status set is treated as active (the column default). */
    @Test
    void treatsAMissingStatusAsActive() {
        find(account(null));

        assertThat(resolver.resolve("ada@nexus.com")).isPresent();
    }
}
