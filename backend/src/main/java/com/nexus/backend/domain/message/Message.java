package com.nexus.backend.domain.message;

import com.nexus.backend.domain.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A message one person wrote to another and the recipient reads in their Inbox.
 *
 * The two delete flags are per side on purpose: a message belongs to both the
 * sender and the recipient, so one of them clearing it from their own box must
 * not delete it out of the other's.
 */
@Entity
@Table(name = "messages")
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    /** "From" line, shown in the list. Same ceiling the request enforces. */
    @Column(nullable = false, length = 200)
    private String subject;

    /** Unbounded, so a long note is not truncated on the way in. */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String body;

    /** Set when the recipient opens it; the sender's copy is unaffected. */
    @Column(nullable = false)
    private boolean read;

    /** Set when the sender clears their own Sent copy. */
    @Column(name = "deleted_by_sender", nullable = false)
    private boolean deletedBySender;

    /** Set when the recipient clears their own Inbox copy. */
    @Column(name = "deleted_by_recipient", nullable = false)
    private boolean deletedByRecipient;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Message() {
    }

    public Message(User sender, User recipient, String subject, String body) {
        this.sender = sender;
        this.recipient = recipient;
        this.subject = subject;
        this.body = body;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getSender() { return sender; }
    public void setSender(User sender) { this.sender = sender; }

    public User getRecipient() { return recipient; }
    public void setRecipient(User recipient) { this.recipient = recipient; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public boolean isRead() { return read; }
    public void setRead(boolean read) { this.read = read; }

    public boolean isDeletedBySender() { return deletedBySender; }
    public void setDeletedBySender(boolean deletedBySender) { this.deletedBySender = deletedBySender; }

    public boolean isDeletedByRecipient() { return deletedByRecipient; }
    public void setDeletedByRecipient(boolean deletedByRecipient) { this.deletedByRecipient = deletedByRecipient; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
