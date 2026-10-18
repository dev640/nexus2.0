package com.nexus.backend.security;

import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * The authenticated account, carrying the two pieces of identity the request
 * needs beyond its authorities: which company it belongs to, and its row id.
 *
 * <p>Spring's own {@code User} would do for authentication alone, but tenancy
 * needs the company on every request — it is what every scoped query filters
 * on — and the audit trail needs the row id. Both are read from the database by
 * {@link CustomUserDetailsService} rather than taken from a token claim, so a
 * company or role change takes effect on the very next request instead of when
 * the current token happens to expire.
 *
 * <p>{@link #getUsername()} is the email, which keeps every existing
 * {@code authentication.getName()} call site working unchanged.
 *
 * <p>It also carries {@link #isMustChangePassword()}, read from the account row
 * on every request for the same reason as the company: the flag is cleared by
 * the change itself, so a request made with a token minted before the change
 * must already see it cleared, and nothing can slip through on a stale claim.
 */
public class NexusUserPrincipal implements UserDetails {

    private final Long userId;
    private final String email;
    private final String password;
    private final UserRole role;

    /**
     * The company this account belongs to. Null exactly when the account is a
     * SUPER_ADMIN — a global operator that belongs to no single company, which
     * {@code chk_users_company_by_role} enforces in the database.
     */
    private final Long companyId;

    /**
     * True while the account is still on a password an admin issued. Read from
     * the row, not from the token, so it is current as of this request.
     */
    private final boolean mustChangePassword;

    public NexusUserPrincipal(Long userId, String email, String password, UserRole role, Long companyId,
                              boolean mustChangePassword) {
        this.userId = userId;
        this.email = email;
        this.password = password;
        this.role = role;
        this.companyId = companyId;
        this.mustChangePassword = mustChangePassword;
    }

    public static NexusUserPrincipal from(User user) {
        return new NexusUserPrincipal(
            user.getId(),
            user.getEmail(),
            // Supabase-managed accounts have no local hash; the empty string keeps
            // Spring's contract and is never compared, because they never reach a
            // password check on this server.
            user.getPassword() == null ? "" : user.getPassword(),
            user.getRole(),
            user.getCompanyId(),
            user.isMustChangePassword()
        );
    }

    public Long getUserId() { return userId; }

    public Long getCompanyId() { return companyId; }

    public UserRole getRole() { return role; }

    public boolean isMustChangePassword() { return mustChangePassword; }

    /** True for the one role that is not a member of any company. */
    public boolean isSuperAdmin() { return role == UserRole.SUPER_ADMIN; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() { return password; }

    @Override
    public String getUsername() { return email; }

    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public boolean isEnabled() { return true; }

    /** Never includes the password hash: this string ends up in logs. */
    @Override
    public String toString() {
        return "NexusUserPrincipal[userId=" + userId + ", email=" + email + ", role=" + role
            + ", companyId=" + companyId + ", mustChangePassword=" + mustChangePassword + "]";
    }
}
