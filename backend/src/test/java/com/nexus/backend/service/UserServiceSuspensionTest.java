package com.nexus.backend.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexus.backend.domain.company.Company;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
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

    @Test
    void loginSucceedsForAnActiveCompany() {
        when(userRepository.findByEmailWithCompany("ada@nexus.com"))
            .thenReturn(Optional.of(account(Company.STATUS_ACTIVE)));
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);
        when(jwtUtil.generateToken("ada@nexus.com")).thenReturn("access");
        when(jwtUtil.generateRefreshToken("ada@nexus.com")).thenReturn("refresh");

        assertThatCode(() -> userService.login(new LoginRequest("ada@nexus.com", "pw")))
            .doesNotThrowAnyException();
    }
}
