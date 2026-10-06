package com.nexus.backend.service;

import com.nexus.backend.config.SupabaseProperties;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import com.nexus.backend.repository.UserRepository;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

/**
 * Turns a verified Supabase identity into a local {@link User} row.
 *
 * Resolution order per Supabase user UUID:
 * 1. exact match on users.supabase_id (the normal case)
 * 2. an existing local account with the same email gets linked to the
 *    Supabase identity (one person, one account, never a duplicate)
 * 3. otherwise a brand-new account is provisioned with MEMBER role and no
 *    local password — it authenticates only through Supabase.
 */
@Service
public class SupabaseUserService {

    private static final Logger log = LoggerFactory.getLogger(SupabaseUserService.class);

    private final UserRepository userRepository;
    private final SupabaseProperties properties;

    public SupabaseUserService(UserRepository userRepository, SupabaseProperties properties) {
        this.userRepository = userRepository;
        this.properties = properties;
    }

    /** Email from the verified token claims (Supabase stores it in user metadata too). */
    public String emailOf(Claims claims) {
        String email = claims.get("email", String.class);
        if (email == null || email.isBlank()) {
            Object meta = claims.get("user_metadata");
            if (meta instanceof java.util.Map<?, ?> map) {
                Object fromMeta = map.get("email");
                if (fromMeta instanceof String s && !s.isBlank()) {
                    email = s;
                }
            }
        }
        return email;
    }

    /** Preferred display name: metadata → email local part. */
    public String nameOf(Claims claims) {
        Object meta = claims.get("user_metadata");
        if (meta instanceof java.util.Map<?, ?> map) {
            for (String key : new String[] {"full_name", "name", "user_name", "preferred_username"}) {
                Object value = map.get(key);
                if (value instanceof String s && !s.isBlank()) {
                    return s.trim();
                }
            }
        }
        String email = emailOf(claims);
        if (email != null && email.contains("@")) {
            return email.substring(0, email.indexOf('@'));
        }
        return "New user";
    }

    /**
     * Returns the local account for a verified Supabase identity, provisioning
     * or linking one when needed. Never returns null.
     */
    @Transactional
    public User resolveUser(Claims claims) {
        UUID supabaseId = UUID.fromString(claims.getSubject());
        String email = emailOf(claims);

        User user = userRepository.findBySupabaseId(supabaseId).orElse(null);
        if (user != null) {
            return user;
        }

        if (email != null && !email.isBlank()) {
            user = findByEmail(email);
            if (user != null) {
                if (user.getSupabaseId() == null) {
                    user.setSupabaseId(supabaseId);
                    // Null out the local password: from now on this account
                    // authenticates through Supabase only.
                    user.setPassword(null);
                    user = userRepository.save(user);
                    log.info("Linked existing account {} to Supabase identity {}", user.getEmail(), supabaseId);
                }
                return user;
            }
        }

        user = new User();
        user.setSupabaseId(supabaseId);
        user.setEmail(email != null && !email.isBlank() ? normalize(email) : "user-" + supabaseId + "@supabase.local");
        user.setName(nameOf(claims));
        user.setPassword(null);
        user.setRole(UserRole.MEMBER);
        User saved = userRepository.save(user);
        log.info("Provisioned new account {} from Supabase identity {}", saved.getEmail(), supabaseId);
        return saved;
    }

    /**
     * Existing local account for an email, preferring an exact match. Supabase
     * lowercases the emails it issues, so a mixed-case local account is found
     * by the case-insensitive fallback rather than duplicated.
     */
    private User findByEmail(String email) {
        String normalized = normalize(email);
        return userRepository.findByEmail(normalized)
            .or(() -> userRepository.findFirstByEmailIgnoreCase(normalized))
            .orElse(null);
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /** True when Supabase authentication is configured and should be attempted. */
    public boolean isEnabled() {
        return properties.isEnabled();
    }
}
