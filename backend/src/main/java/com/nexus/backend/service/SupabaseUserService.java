package com.nexus.backend.service;

import com.nexus.backend.config.SupabaseProperties;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import com.nexus.backend.repository.UserRepository;
import io.jsonwebtoken.Claims;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
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
 * 3. otherwise there is no account, and the caller must treat the request as
 *    unauthenticated.
 *
 * <p>Step 3 used to create an account on the spot. That is no longer possible,
 * and would not be right if it were: an account now belongs to exactly one
 * company and carries an employee code, so a self-provisioned row has no company
 * to belong to — the database rejects it — and granting one by guessing would
 * hand a stranger entry to whichever company was guessed. The policy is already
 * that the only way into a workspace is through an admin, who creates the account
 * first; a later Supabase sign-in with that address then links to it (step 2).
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
     * Returns the local account for a verified Supabase identity, linking one
     * when the address already exists.
     *
     * @return the account, or {@code null} when this identity has no account here
     *         and must therefore not be signed in
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

        // No local account for this identity. Refuse rather than provision: see
        // the class comment. The thrown exception is deliberately not one the
        // filter swallows, so a misconfiguration here is visible instead of
        // looking like a wrong password.
        log.info("Supabase identity {} ({}) has no account in this workspace; refusing sign-in",
            supabaseId, email == null ? "no email claim" : email);
        throw new UsernameNotFoundException(
            "No account exists for this address. Ask an administrator to create one.");
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
