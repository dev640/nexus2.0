package com.nexus.backend.domain.push;

import com.nexus.backend.domain.user.User;
import com.nexus.backend.tenant.TenantAware;
import com.nexus.backend.tenant.TenantStampListener;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * One browser that has agreed to receive notifications, and the three values
 * needed to reach it.
 *
 * <p>The endpoint is the push service's own address for the device. The two keys
 * are the browser's: {@code p256dh} is the public half of a throwaway key pair
 * the browser generated, and {@code auth} is the shared secret that
 * authenticates the payload. All three are handed over by the browser at
 * registration and mean nothing without it.
 *
 * <p>Rows are per device rather than per user on purpose — a phone and a laptop
 * have separate endpoints — and the endpoint is unique across the table because
 * two accounts serving the same browser would each think they own it.
 */
@Entity
@Table(name = "push_subscriptions")
@EntityListeners(TenantStampListener.class)
public class PushSubscription implements TenantAware {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** The push service's URL for this browser. Always https. */
    @Column(nullable = false, length = 1000)
    private String endpoint;

    /** The browser's public key for the payload, base64url. */
    @Column(nullable = false, length = 300)
    private String p256dh;

    /** The shared auth secret that authenticates the payload, base64url. */
    @Column(nullable = false, length = 300)
    private String auth;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * Owning company, stamped on insert (V18/V19). V19 pairs it with the user,
     * so a device registration cannot be attached to another company's account.
     */
    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getP256dh() { return p256dh; }
    public void setP256dh(String p256dh) { this.p256dh = p256dh; }

    public String getAuth() { return auth; }
    public void setAuth(String auth) { this.auth = auth; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    @Override
    public Long getCompanyId() { return companyId; }

    @Override
    public void setCompanyId(Long companyId) { this.companyId = companyId; }
}
