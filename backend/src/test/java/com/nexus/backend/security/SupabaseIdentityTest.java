package com.nexus.backend.security;

import com.nexus.backend.config.SupabaseProperties;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import com.nexus.backend.repository.UserRepository;
import com.nexus.backend.service.SupabaseUserService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupabaseIdentityTest {

    private static final String SECRET = "test-secret-that-is-at-least-32-bytes-long!!";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes());

    @Mock
    private UserRepository userRepository;

    private SupabaseProperties properties;
    private SupabaseTokenVerifier verifier;
    private SupabaseUserService provisioning;

    @BeforeEach
    void setUp() {
        properties = new SupabaseProperties();
        verifier = new SupabaseTokenVerifier(properties);
        provisioning = new SupabaseUserService(userRepository, properties);
    }

    private String mintToken(UUID subject, String email, String issuer, Date expiry) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("email", email);
        claims.put("role", "authenticated");
        return Jwts.builder()
            .claims(claims)
            .subject(subject.toString())
            .issuer(issuer)
            .issuedAt(Date.from(Instant.now().minusSeconds(60)))
            .expiration(expiry)
            .signWith(KEY)
            .compact();
    }

    // ---------- SupabaseTokenVerifier (HS256 mode) ----------

    @Test
    void verifiesAWellFormedSupabaseToken() {
        properties.setUrl("https://abcdefgh.supabase.co");
        properties.setJwtSecret(SECRET);
        UUID subject = UUID.randomUUID();

        Claims claims = verifier.verify(mintToken(subject, "ada@nexus.com",
            "https://abcdefgh.supabase.co/auth/v1", Date.from(Instant.now().plusSeconds(600))));

        assertThat(claims.getSubject()).isEqualTo(subject.toString());
        assertThat(claims.get("email", String.class)).isEqualTo("ada@nexus.com");
    }

    @Test
    void rejectsATokenWithWrongIssuer() {
        properties.setUrl("https://abcdefgh.supabase.co");
        properties.setJwtSecret(SECRET);

        String token = mintToken(UUID.randomUUID(), "ada@nexus.com",
            "https://evil.example.com/auth/v1", Date.from(Instant.now().plusSeconds(600)));

        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsAnExpiredToken() {
        properties.setUrl("https://abcdefgh.supabase.co");
        properties.setJwtSecret(SECRET);

        String token = mintToken(UUID.randomUUID(), "ada@nexus.com",
            "https://abcdefgh.supabase.co/auth/v1", Date.from(Instant.now().minusSeconds(60)));

        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsATokenSignedWithADifferentSecret() {
        properties.setUrl("https://abcdefgh.supabase.co");
        properties.setJwtSecret(SECRET);

        SecretKey otherKey = Keys.hmacShaKeyFor(
            "another-secret-that-is-also-32-bytes-long!".getBytes());
        String forged = Jwts.builder()
            .subject(UUID.randomUUID().toString())
            .issuer("https://abcdefgh.supabase.co/auth/v1")
            .expiration(Date.from(Instant.now().plusSeconds(600)))
            .signWith(otherKey)
            .compact();

        assertThatThrownBy(() -> verifier.verify(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void isDisabledWhenNoPropertiesAreSet() {
        assertThat(properties.isEnabled()).isFalse();
        assertThatThrownBy(() -> verifier.verify("any-token"))
            .isInstanceOf(JwtException.class)
            .hasMessageContaining("not configured");
    }

    // ---------- SupabaseUserService ----------

    private Claims claimsFor(UUID subject, String email, String fullName) {
        var builder = Jwts.claims().subject(subject.toString()).add("email", email);
        if (fullName != null) {
            builder.add("user_metadata", Map.of("full_name", fullName));
        }
        return builder.build();
    }

    @Test
    void provisionsANewAccountForAnUnknownSupabaseIdentity() {
        properties.setUrl("https://abcdefgh.supabase.co");
        UUID subject = UUID.randomUUID();
        when(userRepository.findBySupabaseId(subject)).thenReturn(Optional.empty());
        when(userRepository.findByEmail("ada@nexus.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(42L);
            return u;
        });

        User user = provisioning.resolveUser(claimsFor(subject, "ada@nexus.com", "Ada Lovelace"));

        assertThat(user.getId()).isEqualTo(42L);
        assertThat(user.getSupabaseId()).isEqualTo(subject);
        assertThat(user.getEmail()).isEqualTo("ada@nexus.com");
        assertThat(user.getName()).isEqualTo("Ada Lovelace");
        assertThat(user.getPassword()).isNull();
        assertThat(user.getRole()).isEqualTo(UserRole.MEMBER);
    }

    @Test
    void linksAnExistingEmailAccountInsteadOfDuplicating() {
        properties.setUrl("https://abcdefgh.supabase.co");
        UUID subject = UUID.randomUUID();
        User existing = new User("Ada Lovelace", "ada@nexus.com", "$bcrypt-hash", UserRole.ADMIN);
        existing.setId(7L);

        when(userRepository.findBySupabaseId(subject)).thenReturn(Optional.empty());
        when(userRepository.findByEmail("ada@nexus.com")).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User user = provisioning.resolveUser(claimsFor(subject, "ada@nexus.com", "Ada Lovelace"));

        assertThat(user.getId()).isEqualTo(7L);
        assertThat(user.getSupabaseId()).isEqualTo(subject);
        assertThat(user.getPassword()).isNull(); // now Supabase-only
        assertThat(user.getRole()).isEqualTo(UserRole.ADMIN); // role preserved
    }

    @Test
    void returnsTheExistingLinkedAccountOnRepeatLogins() {
        properties.setUrl("https://abcdefgh.supabase.co");
        UUID subject = UUID.randomUUID();
        User linked = new User("Ada Lovelace", "ada@nexus.com", null, UserRole.MEMBER);
        linked.setId(7L);
        linked.setSupabaseId(subject);

        when(userRepository.findBySupabaseId(subject)).thenReturn(Optional.of(linked));

        User user = provisioning.resolveUser(claimsFor(subject, "ada@nexus.com", "Ada Lovelace"));

        assertThat(user.getId()).isEqualTo(7L);
        assertThat(user.getSupabaseId()).isEqualTo(subject);
    }

    @Test
    void derivesANameFromTheEmailWhenMetadataIsMissing() {
        properties.setUrl("https://abcdefgh.supabase.co");
        UUID subject = UUID.randomUUID();
        when(userRepository.findBySupabaseId(subject)).thenReturn(Optional.empty());
        when(userRepository.findByEmail("grace@nexus.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(9L);
            return u;
        });

        Claims noMeta = Jwts.parser().verifyWith(KEY).build()
            .parseSignedClaims(mintToken(subject, "grace@nexus.com",
                "https://abcdefgh.supabase.co/auth/v1", Date.from(Instant.now().plusSeconds(600))))
            .getPayload();

        assertThat(provisioning.nameOf(noMeta)).isEqualTo("grace");
        User user = provisioning.resolveUser(noMeta);
        assertThat(user.getName()).isEqualTo("grace");
    }

    @Test
    void linksAMixedCaseLocalAccountWhenSupabaseLowercasesTheEmail() {
        properties.setUrl("https://abcdefgh.supabase.co");
        UUID subject = UUID.randomUUID();
        User existing = new User("Ada Lovelace", "Ada@Nexus.com", "$bcrypt-hash", UserRole.MEMBER);
        existing.setId(11L);

        when(userRepository.findBySupabaseId(subject)).thenReturn(Optional.empty());
        when(userRepository.findByEmail("ada@nexus.com")).thenReturn(Optional.empty());
        when(userRepository.findFirstByEmailIgnoreCase("ada@nexus.com")).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User user = provisioning.resolveUser(claimsFor(subject, "ada@nexus.com", null));

        assertThat(user.getId()).isEqualTo(11L); // linked, not duplicated
        assertThat(user.getSupabaseId()).isEqualTo(subject);
        assertThat(user.getPassword()).isNull();
    }

    @Test
    void normalizesTheEmailWhenProvisioning() {
        properties.setUrl("https://abcdefgh.supabase.co");
        UUID subject = UUID.randomUUID();
        when(userRepository.findBySupabaseId(subject)).thenReturn(Optional.empty());
        when(userRepository.findByEmail("ada@nexus.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User user = provisioning.resolveUser(claimsFor(subject, "  Ada@Nexus.com  ", "Ada"));

        assertThat(user.getEmail()).isEqualTo("ada@nexus.com");
    }
}
