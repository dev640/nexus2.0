package com.nexus.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexus.backend.domain.company.Company;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import com.nexus.backend.dto.AuthResponse;
import com.nexus.backend.dto.LoginRequest;
import com.nexus.backend.exception.CompanySuspendedException;
import com.nexus.backend.exception.ValidationException;
import com.nexus.backend.repository.UserRepository;
import com.nexus.backend.security.JwtUtil;
import com.nexus.backend.tenant.CurrentTenant;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

/**
 * Sign-in and refresh are refused for a suspended company.
 *
 * <p>These two endpoints run before any authentication exists, so there is no
 * principal for the servlet filter to gate — they have to check the company row
 * themselves. Refusing here is what stops a suspended workspace's people from
 * minting a fresh credential their very next request would bounce with.
 */
class UserServiceSuspensionTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final JwtUtil jwtUtil = mock(JwtUtil.class);

    private final UserService userService = new UserService(
        userRepository, passwordEncoder, jwtUtil,
        mock(EmployeeCodeService.class), mock(AuditService.class), mock(CurrentTenant.class));

    private static User account(String companyStatus) {
        User user = new User("Ada", "ada@nexus.com", "hash", UserRole.MEMBER);
        user.setId(9L);
        Company company = new Company("Acme");
        company.setId(3L);
        company.setStatus(companyStatus);
        user.setCompany(company);
        return user;
    }

    @Test
    void loginIsRefusedForASuspendedCompany() {
        when(userRepository.findByEmailWithCompany("ada@nexus.com"))
            .thenReturn(Optional.of(account(Company.STATUS_SUSPENDED)));
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);

        assertThatThrownBy(() -> userService.login(new LoginRequest("ada@nexus.com", "pw")))
            .isInstanceOf(CompanySuspendedException.class);
    }

    @Test
    void refreshIsRefusedForASuspendedCompany() {
        when(jwtUtil.isRefreshToken("rt")).thenReturn(true);
        when(jwtUtil.extractUsername("rt")).thenReturn("ada@nexus.com");
        when(userRepository.findByEmailWithCompany("ada@nexus.com"))
            .thenReturn(Optional.of(account(Company.STATUS_SUSPENDED)));

        assertThatThrownBy(() -> userService.refresh("rt"))
            .isInstanceOf(CompanySuspendedException.class);
    }

    @Test
    void refreshIsRefusedWhenTheAccountVersionHasChanged() {
        User user = account(Company.STATUS_ACTIVE);
        user.setTokenVersion(1);
        when(jwtUtil.isRefreshToken("rt")).thenReturn(true);
        when(jwtUtil.extractUsername("rt")).thenReturn("ada@nexus.com");
        when(userRepository.findByEmailWithCompany("ada@nexus.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.refresh("rt"))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Invalid or expired refresh token");
    }

    /** The correct password is still refused when the workspace is suspended. */
    @Test
    void loginStillChecksThePasswordBeforeTheSuspensionVerdict() {
        when(userRepository.findByEmailWithCompany("ada@nexus.com"))
            .thenReturn(Optional.of(account(Company.STATUS_SUSPENDED)));
        when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);

        // A wrong password is reported as such even for a suspended company, so
        // the door does not disclose the suspension to someone who never got in.
        assertThatThrownBy(() -> userService.login(new LoginRequest("ada@nexus.com", "wrong")))
            .isNotInstanceOf(CompanySuspendedException.class);
    }

    /**
     * An active company's correct password returns a session signed at the
     * account's own version — 4 here, not the initial 0.
     *
     * <p>The version is asserted because a token minted at a constant would pass
     * a test that only checked "no exception": the session would authenticate
     * forever and no later password change could end it. This is also the test
     * that had been stubbing the unversioned factory, which the service stopped
     * calling when V22 landed, so both tokens came back null and nothing noticed.
     */
    @Test
    void loginMintsTokensAtTheAccountsOwnVersionForAnActiveCompany() {
        User user = account(Company.STATUS_ACTIVE);
        user.setTokenVersion(4);
        when(userRepository.findByEmailWithCompany("ada@nexus.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);
        when(jwtUtil.generateToken("ada@nexus.com", 4)).thenReturn("access");
        when(jwtUtil.generateRefreshToken("ada@nexus.com", 4)).thenReturn("refresh");

        AuthResponse response = userService.login(new LoginRequest("ada@nexus.com", "pw"));

        // Both assertions fail if the service signs at a version other than the
        // row's: the matching stub would go unused and the token would be null.
        assertThat(response.token()).isEqualTo("access");
        assertThat(response.refreshToken()).isEqualTo("refresh");
    }
}
