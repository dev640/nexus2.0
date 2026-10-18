package com.nexus.backend.domain.notification;

import com.nexus.backend.domain.user.User;
import com.nexus.backend.tenant.TenantAware;
import com.nexus.backend.tenant.TenantStampListener;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "notifications")
@EntityListeners(TenantStampListener.class)
public class Notification implements TenantAware {

    public enum Category {
        MENTIONS, TASKS, PROJECTS, AI, SYSTEM
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private Category category = Category.SYSTEM;

    @Column(nullable = false, length = 500)
    private String text;

    /**
     * App-relative deep link to whatever this notification is about, e.g.
     * {@code /board?task=12}. Null means there is nothing useful to open, and
     * only paths are ever stored — an alert must never navigate off-site.
     */
    @Column(length = 300)
    private String link;

    @Column(nullable = false)
    private boolean read;

    /** Owning company, stamped on insert (V18/V19). Never reassigned. */
    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getRecipient() { return recipient; }
    public void setRecipient(User recipient) { this.recipient = recipient; }

    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public String getLink() { return link; }
    public void setLink(String link) { this.link = link; }

    public boolean isRead() { return read; }
    public void setRead(boolean read) { this.read = read; }

    @Override
    public Long getCompanyId() { return companyId; }

    @Override
    public void setCompanyId(Long companyId) { this.companyId = companyId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
