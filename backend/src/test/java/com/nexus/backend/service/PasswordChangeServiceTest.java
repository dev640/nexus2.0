package com.nexus.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexus.backend.domain.audit.AuditAction;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import com.nexus.backend.dto.AuthResponse;
import com.nexus.backend.dto.ChangePasswordRequest;
import com.nexus.backend.exception.ValidationException;
import com.nexus.backend.repository.UserRepository;
import com.nexus.backend.security.NexusUserPrincipal;
import com.nexus.backend.security.JwtUtil;
import com.nexus.backend.security.SupabaseAdminClient;
import com.nexus.backend.security.SupabaseAdminClient.PasswordVerification;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The rules of a self-service password change, as executable statements.
 *
 * <p>The two that matter most are negatives: a wrong current password must not
 * change anything, and a Supabase-owned password whose check could not be made
 * must not be reported to the caller as a wrong password. The second is the one
 * that would otherwise leave somebody retyping a correct password forever.
 */
@ExtendWith(MockitoExtension.class)
class PasswordChangeServiceTest {

    private static final long USER_ID = 9L;
    private static final long COMPANY_ID = 31L;
    private static final String EMAIL = "ada@nexus.com";
    private static final String STORED_HASH = "$2a$10$storedhashstoredhashstoredhash";
    private static final UUID SUPABASE_ID = UUID.fromString("947fcac6-bcc9-49e7-88e3-7b0e35dd181e");

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private SupabaseAdminClient supabaseAdminClient;
    @Mock private AuditService auditService;
    @Mock private JwtUtil jwtUtil;

    @InjectMocks private PasswordChangeService service;

    /** A locally stored credential, and an account gated on a generated password. */
    private User localUser() {
        User user = new User("Ada", EMAIL, STORED_HASH, UserRole.MEMBER);
        user.setId(USER_ID);
        user.setCompanyId(COMPANY_ID);
        user.setMustChangePassword(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        return user;
    }

    /** An account whose password only exists in Supabase, so it has no local hash. */
    private User supabaseUser() {
        User user = new User("Ada", EMAIL, null, UserRole.MEMBER);
        user.setId(USER_ID);
        user.setCompanyId(COMPANY_ID);
        user.setSupabaseId(SUPABASE_ID);
        user.setMustChangePassword(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        return user;
    }

    private static NexusUserPrincipal principal() {
        return new NexusUserPrincipal(USER_ID, EMAIL, "", UserRole.MEMBER, COMPANY_ID, true, false);
    }

    private static ChangePasswordRequest request(String current, String next) {
        return new ChangePasswordRequest(current, next);
    }

    // ------------------------------------------------------------ local accounts

    @Test
    void replacesALocalPasswordAndClearsTheFlag() {
        User user = localUser();
        when(passwordEncoder.matches("issued-pass", STORED_HASH)).thenReturn(true);
        when(passwordEncoder.matches("chosen-pass", STORED_HASH)).thenReturn(false);
        when(passwordEncoder.encode("chosen-pass")).thenReturn("$2a$10$newhash");

        when(jwtUtil.generateToken(EMAIL, 1)).thenReturn("fresh-access");
        when(jwtUtil.generateRefreshToken(EMAIL, 1)).thenReturn("fresh-refresh");

        AuthResponse response = service.changePassword(principal(), request("issued-pass", "chosen-pass"));

        assertThat(user.getPassword()).isEqualTo("$2a$10$newhash");
        assertThat(user.isMustChangePassword()).isFalse();
        assertThat(user.getTokenVersion()).isEqualTo(1);
        assertThat(response.token()).isEqualTo("fresh-access");
        assertThat(response.refreshToken()).isEqualTo("fresh-refresh");
        assertThat(response.user().mustChangePassword()).isFalse();
        verify(userRepository).save(user);
    }

    @Test
    void recordsTheChangeWithoutRecordingThePassword() {
        localUser();
        when(passwordEncoder.matches("issued-pass", STORED_HASH)).thenReturn(true);
        when(passwordEncoder.matches("chosen-pass", STORED_HASH)).thenReturn(false);
        when(passwordEncoder.encode("chosen-pass")).thenReturn("$2a$10$newhash");

        service.changePassword(principal(), request("issued-pass", "chosen-pass"));

        ArgumentCaptor<AuditAction> action = ArgumentCaptor.forClass(AuditAction.class);
        ArgumentCaptor<String> entityType = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> entityId = ArgumentCaptor.forClass(Object.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> metadata = ArgumentCaptor.forClass(java.util.Map.class);
        verify(auditService).record(action.capture(), entityType.capture(), entityId.capture(), metadata.capture());

        assertThat(action.getValue()).isEqualTo(AuditAction.PASSWORD_CHANGED);
        assertThat(entityType.getValue()).isEqualTo("User");
        assertThat(entityId.getValue()).isEqualTo(USER_ID);
        // The trail says who changed their own password, and nothing that could
        // reconstruct either password.
        assertThat(metadata.getValue()).containsEntry("email", EMAIL);
        assertThat(metadata.getValue().values()).doesNotContain("issued-pass", "chosen-pass", "$2a$10$newhash");
    }

    @Test
    void refusesAWrongCurrentPasswordAndChangesNothing() {
        User user = localUser();
        when(passwordEncoder.matches("not-it", STORED_HASH)).thenReturn(false);

        assertThatThrownBy(() -> service.changePassword(principal(), request("not-it", "chosen-pass")))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("not your current password");

        assertThat(user.getPassword()).isEqualTo(STORED_HASH);
        assertThat(user.isMustChangePassword()).isTrue();
        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void refusesTheCurrentPasswordAsTheNewOne() {
        localUser();

        assertThatThrownBy(() -> service.changePassword(principal(), request("issued-pass", "issued-pass")))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("current password");

        // Decided before any hashing: the same string cannot be a new password.
        verifyNoInteractions(passwordEncoder);
    }

    /**
     * bcrypt only reads the first 72 bytes, so two different strings can be the
     * same password. Comparing the raw strings alone would accept this as a change
     * and leave the account on a password its owner believed they had replaced.
     */
    @Test
    void refusesReuseThatOnlyTheHashCanDetect() {
        String sharedPrefix = "a".repeat(72);
        String current = sharedPrefix + "X";
        String next = sharedPrefix + "Y";
        localUser();
        when(passwordEncoder.matches(current, STORED_HASH)).thenReturn(true);
        when(passwordEncoder.matches(next, STORED_HASH)).thenReturn(true);

        assertThatThrownBy(() -> service.changePassword(principal(), request(current, next)))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("current password");

        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(any());
    }

    // --------------------------------------------------------- Supabase accounts

    @Test
    void replacesASupabasePasswordThereAndLeavesTheLocalColumnAlone() {
        User user = supabaseUser();
        when(supabaseAdminClient.verifyPassword(EMAIL, "issued-pass")).thenReturn(PasswordVerification.MATCH);

        service.changePassword(principal(), request("issued-pass", "chosen-pass"));

        verify(supabaseAdminClient).updatePassword(SUPABASE_ID, "chosen-pass");
        // The account's sessions outliving the change is exactly what matters
        // after an admin has known the old password.
        verify(supabaseAdminClient).revokeSessions(SUPABASE_ID);
        assertThat(user.getPassword()).isNull();
        assertThat(user.isMustChangePassword()).isFalse();
        verify(userRepository).save(user);
        // Supabase holds the credential; nothing is hashed locally.
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void refusesASupabasePasswordSupabaseRejects() {
        supabaseUser();
        when(supabaseAdminClient.verifyPassword(EMAIL, "not-it")).thenReturn(PasswordVerification.NO_MATCH);

        assertThatThrownBy(() -> service.changePassword(principal(), request("not-it", "chosen-pass")))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("not your current password");

        verify(supabaseAdminClient, never()).updatePassword(any(), anyString());
        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    /**
     * The distinction that keeps a person from retyping a correct password: not
     * being able to ask Supabase is not the same answer as Supabase saying no.
     */
    @Test
    void doesNotCallAnUnavailableCheckAWrongPassword() {
        supabaseUser();
        when(supabaseAdminClient.verifyPassword(EMAIL, "issued-pass")).thenReturn(PasswordVerification.UNAVAILABLE);

        assertThatThrownBy(() -> service.changePassword(principal(), request("issued-pass", "chosen-pass")))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("could not be checked")
            .hasMessageNotContaining("not your current password");

        verify(supabaseAdminClient, never()).updatePassword(any(), anyString());
        verify(userRepository, never()).save(any());
    }

    // ----------------------------------------------------------------- refusals

    @Test
    void refusesAnAccountWithNoPasswordStore() {
        User user = new User("Ada", EMAIL, null, UserRole.MEMBER);
        user.setId(USER_ID);
        user.setCompanyId(COMPANY_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.changePassword(principal(), request("issued-pass", "chosen-pass")))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("no password store");

        verify(userRepository, never()).save(any());
    }

    @Test
    void refusesAnAccountThatNoLongerExists() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changePassword(principal(), request("issued-pass", "chosen-pass")))
            .isInstanceOf(com.nexus.backend.exception.ResourceNotFoundException.class);
    }

    /**
     * There is no account id in the request, so the only account reachable is the
     * one the authenticated principal identifies — a caller cannot aim this at
     * somebody else by editing the body.
     */
    @Test
    void onlyEverLoadsTheCallersOwnAccount() {
        localUser();
        when(passwordEncoder.matches(anyString(), eq(STORED_HASH))).thenReturn(true);
        when(passwordEncoder.matches(eq("chosen-pass"), eq(STORED_HASH))).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$newhash");

        assertThatCode(() -> service.changePassword(principal(), request("issued-pass", "chosen-pass")))
            .doesNotThrowAnyException();

        verify(userRepository).findById(USER_ID);
    }

    /**
     * The same endpoint also serves a voluntary change from Settings, so it must
     * not depend on the account being gated in the first place.
     */
    @Test
    void allowsAVoluntaryChangeFromAnAlreadyClearedAccount() {
        User user = localUser();
        user.setMustChangePassword(false);
        when(passwordEncoder.matches("issued-pass", STORED_HASH)).thenReturn(true);
        when(passwordEncoder.matches("chosen-pass", STORED_HASH)).thenReturn(false);
        when(passwordEncoder.encode("chosen-pass")).thenReturn("$2a$10$newhash");

        service.changePassword(principal(), request("issued-pass", "chosen-pass"));

        assertThat(user.isMustChangePassword()).isFalse();
    }
}
