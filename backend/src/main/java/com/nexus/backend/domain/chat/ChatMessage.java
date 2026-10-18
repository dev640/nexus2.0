package com.nexus.backend.domain.chat;

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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A single chat message. Reactions are stored inline as a JSON object
 * (emoji -> array of user ids) — small, read-heavy data that never needs its
 * own query patterns.
 */
@Entity
@Table(name = "chat_messages")
@EntityListeners(TenantStampListener.class)
public class ChatMessage implements TenantAware {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "channel_id", nullable = false)
    private ChatChannel channel;

    /** Null when the author's account is deleted. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    private User author;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reactions = "{}";

    @Column(nullable = false)
    private boolean edited = false;

    /**
     * Owning company, stamped on insert (V18/V19). V19 pairs it with both the
     * channel and the author, so a message can never be posted into another
     * company's channel or attributed to another company's user.
     */
    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public ChatChannel getChannel() { return channel; }
    public void setChannel(ChatChannel channel) { this.channel = channel; }

    public User getAuthor() { return author; }
    public void setAuthor(User author) { this.author = author; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public String getReactions() { return reactions; }
    public void setReactions(String reactions) { this.reactions = reactions; }

    public boolean isEdited() { return edited; }
    public void setEdited(boolean edited) { this.edited = edited; }

    @Override
    public Long getCompanyId() { return companyId; }

    @Override
    public void setCompanyId(Long companyId) { this.companyId = companyId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
