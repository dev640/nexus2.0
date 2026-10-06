package com.nexus.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.backend.config.SupabaseProperties;
import com.nexus.backend.domain.user.PasswordResetRequest;
import com.nexus.backend.domain.user.ResetRequestStatus;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import com.nexus.backend.dto.CreateUserRequest;
import com.nexus.backend.exception.ValidationException;
import com.nexus.backend.repository.PasswordResetRequestRepository;
import com.nexus.backend.repository.UserRepository;
import com.nexus.backend.security.SupabaseAdminClient;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AdminAccountServiceTest {

    private static final UUID SUPABASE_ID = UUID.fromString("947fcac6-bcc9-49e7-88e3-7b0e35dd181e");

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordResetRequestRepository resetRequestRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private SupabaseAdminClient supabaseAdminClient;

    @Mock
    private SupabaseProperties supabaseProperties;

    @InjectMocks
    private AdminAccountService service;

    /** Assigns an id on save; stubbed per test because only creation persists a user. */
    private void stubSave() {
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(42L);
            return u;
        });
    }

    // ---------- create user ----------

    @Test
    void createUserDefaultsToMemberWhenNoRoleGiven() {
        stubSave();
        when(userRepository.findByEmail("new@nexus.com")).thenReturn(Optional.empty());
        when(userRepository.findFirstByEmailIgnoreCase("new@nexus.com")).thenReturn(Optional.empty());
        when(supabaseAdminClient.isAvailable()).thenReturn(true);
        when(supabaseAdminClient.createUser(anyString(), anyString(), anyString())).thenReturn(SUPABASE_ID);

        var created = service.createUser(new CreateUserRequest("New Person", "New@Nexus.com", null, null));

        // An omitted role must never default to ADMIN.
        assertThat(created.role()).isEqualTo(UserRole.MEMBER);
        assertThat(created.email()).isEqualTo("new@nexus.com");
        assertThat(created.password()).isNotBlank();
    }

    @Test
    void createUserStoresPasswordInSupabaseNotLocally() {
        stubSave();
        when(userRepository.findByEmail("new@nexus.com")).thenReturn(Optional.empty());
        when(userRepository.findFirstByEmailIgnoreCase("new@nexus.com")).thenReturn(Optional.empty());
        when(supabaseAdminClient.isAvailable()).thenReturn(true);
        when(supabaseAdminClient.createUser(anyString(), anyString(), anyString())).thenReturn(SUPABASE_ID);

        service.createUser(new CreateUserRequest("New Person", "new@nexus.com", null, UserRole.DEVELOPER));

        var saved = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        // Supabase owns the credential, so no local hash is written.
        assertThat(saved.getValue().getPassword()).isNull();
        assertThat(saved.getValue().getSupabaseId()).isEqualTo(SUPABASE_ID);
    }

    @Test
    void createUserDoesNotEchoBackAnAdminSuppliedPassword() {
        stubSave();
        when(userRepository.findByEmail("new@nexus.com")).thenReturn(Optional.empty());
        when(userRepository.findFirstByEmailIgnoreCase("new@nexus.com")).thenReturn(Optional.empty());
        when(supabaseAdminClient.isAvailable()).thenReturn(true);
        when(supabaseAdminClient.createUser(anyString(), anyString(), anyString())).thenReturn(SUPABASE_ID);

        var created = service.createUser(new CreateUserRequest("New", "new@nexus.com", "Chosen-By-Admin-1", null));

        // The admin already knows it, so it is not echoed in the response.
        assertThat(created.password()).isNull();
    }

    @Test
    void createUserRejectsDuplicateEmail() {
        when(userRepository.findByEmail("dup@nexus.com")).thenReturn(Optional.of(new User()));

        assertThatThrownBy(() -> service.createUser(new CreateUserRequest("Dup", "dup@nexus.com", null, null)))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    void createUserRefusesWhenSupabaseSignInCannotBeProvisioned() {
        // Supabase is the workspace's auth mode but the Admin API key is missing, so
        // a local-only account would be created that the login screen can never use.
        when(supabaseProperties.isEnabled()).thenReturn(true);
        when(supabaseAdminClient.isAvailable()).thenReturn(false);

        assertThatThrownBy(() -> service.createUser(new CreateUserRequest("New", "new@nexus.com", null, null)))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("NEXUS_SUPABASE_SERVICE_ROLE_KEY");

        // Nothing may be persisted when the account could not be made usable.
        verify(userRepository, never()).save(any());
    }

    @Test
    void createUserStillFallsBackToALocalPasswordWhenSupabaseIsOffEntirely() {
        stubSave();
        when(userRepository.findByEmail("local@nexus.com")).thenReturn(Optional.empty());
        when(userRepository.findFirstByEmailIgnoreCase("local@nexus.com")).thenReturn(Optional.empty());
        when(supabaseProperties.isEnabled()).thenReturn(false);
        when(supabaseAdminClient.isAvailable()).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("bcrypt-hash");

        var created = service.createUser(new CreateUserRequest("Local User", "local@nexus.com", null, null));

        // A purely local workspace has no Supabase dependency, so provisioning works.
        assertThat(created.email()).isEqualTo("local@nexus.com");
        var saved = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getPassword()).isEqualTo("bcrypt-hash");
        assertThat(saved.getValue().getSupabaseId()).isNull();
    }

    // ---------- password reset requests ----------

    @Test
    void resetRequestForUnknownEmailSilentlyDoesNothing() {
        when(userRepository.findByEmail("ghost@nexus.com")).thenReturn(Optional.empty());
        when(userRepository.findFirstByEmailIgnoreCase("ghost@nexus.com")).thenReturn(Optional.empty());

        // Must not throw and must not save: an identical outcome to a real user
        // prevents the endpoint being used to probe which emails are registered.
        assertThatCode(() -> service.requestPasswordReset("ghost@nexus.com", "locked out"))
            .doesNotThrowAnyException();
        verify(resetRequestRepository, never()).save(any());
    }

    @Test
    void resetRequestCreatesOnePendingRowForKnownUser() {
        User user = new User("Ada", "ada@nexus.com", null, UserRole.MEMBER);
        user.setId(7L);
        when(userRepository.findByEmail("ada@nexus.com")).thenReturn(Optional.of(user));
        when(resetRequestRepository.findFirstByUserIdAndStatus(7L, ResetRequestStatus.PENDING))
            .thenReturn(Optional.empty());

        service.requestPasswordReset("ada@nexus.com", "locked out");

        verify(resetRequestRepository).save(any(PasswordResetRequest.class));
    }

    @Test
    void resetRequestDoesNotDuplicateAnExistingPendingRow() {
        User user = new User("Ada", "ada@nexus.com", null, UserRole.MEMBER);
        user.setId(7L);
        when(userRepository.findByEmail("ada@nexus.com")).thenReturn(Optional.of(user));
        when(resetRequestRepository.findFirstByUserIdAndStatus(7L, ResetRequestStatus.PENDING))
            .thenReturn(Optional.of(new PasswordResetRequest(user, null)));

        service.requestPasswordReset("ada@nexus.com", null);

        verify(resetRequestRepository, never()).save(any());
    }

    @Test
    void resolveRequestSetsSupabasePasswordAndClosesTheRequest() {
        User user = new User("Ada", "ada@nexus.com", null, UserRole.MEMBER);
        user.setId(7L);
        user.setSupabaseId(SUPABASE_ID);
        PasswordResetRequest request = new PasswordResetRequest(user, "locked out");
        request.setId(5L);

        when(resetRequestRepository.findById(5L)).thenReturn(Optional.of(request));
        when(supabaseAdminClient.isAvailable()).thenReturn(true);
        when(resetRequestRepository.save(any(PasswordResetRequest.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        User admin = new User("Dev", "devendra@nexus.com", null, UserRole.ADMIN);
        var resolved = service.resolveRequest(5L, admin);

        assertThat(resolved.password()).isNotBlank();
        assertThat(request.getStatus()).isEqualTo(ResetRequestStatus.RESOLVED);
        assertThat(request.getResolvedBy()).isSameAs(admin);
        verify(supabaseAdminClient).updatePassword(org.mockito.ArgumentMatchers.eq(SUPABASE_ID), anyString());
        // The old password must stop working on cached sessions.
        verify(supabaseAdminClient).revokeSessions(SUPABASE_ID);
    }

    @Test
    void resolveRequestRefusesAnAlreadyResolvedRequest() {
        User user = new User("Ada", "ada@nexus.com", null, UserRole.MEMBER);
        PasswordResetRequest request = new PasswordResetRequest(user, null);
        request.setStatus(ResetRequestStatus.RESOLVED);
        when(resetRequestRepository.findById(5L)).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.resolveRequest(5L, new User()))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("already been resolved");
    }

    @Test
    void resolveRequestExplainsWhenSupabaseAdminKeyIsMissing() {
        User user = new User("Ada", "ada@nexus.com", null, UserRole.MEMBER);
        user.setSupabaseId(SUPABASE_ID);
        PasswordResetRequest request = new PasswordResetRequest(user, null);
        when(resetRequestRepository.findById(5L)).thenReturn(Optional.of(request));
        when(supabaseAdminClient.isAvailable()).thenReturn(false);

        assertThatThrownBy(() -> service.resolveRequest(5L, new User()))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("NEXUS_SUPABASE_SERVICE_ROLE_KEY");
    }

    // ---------- generated passwords ----------

    @Test
    void generatedPasswordMeetsLengthAndCharacterClassRequirements() {
        for (int i = 0; i < 200; i++) {
            String password = service.generatePassword();
            assertThat(password).hasSize(16);
            assertThat(password).matches(".*[a-z].*")
                .matches(".*[A-Z].*")
                .matches(".*[0-9].*")
                .matches(".*[^A-Za-z0-9].*");
            // Ambiguous glyphs are excluded so it can be read aloud.
            assertThat(password).doesNotContain("0", "O", "1", "l", "I");
        }
    }

    @Test
    void generatedPasswordsAreNotRepeated() {
        assertThat(service.generatePassword()).isNotEqualTo(service.generatePassword());
    }
}