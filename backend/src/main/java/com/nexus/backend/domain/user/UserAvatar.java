package com.nexus.backend.domain.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * A user's avatar image, stored one row per user.
 *
 * <p>This is deliberately a separate table rather than a column on
 * {@link User}. {@code spring.jpa.open-in-view} is off and the project does not
 * use bytecode enhancement, so a {@code @Basic(fetch = LAZY)} blob on the
 * users entity would either be pulled into every user listing or fail outside a
 * transaction. As its own row it is simply not read unless an avatar is
 * actually requested, so no fetch semantics are being relied upon.
 */
@Entity
@Table(name = "user_avatars")
public class UserAvatar {

    @Id
    @Column(name = "user_id")
    private Long userId;

    /**
     * Load-bearing: without {@code JdbcTypeCode(VARBINARY)} Hibernate maps
     * byte[] to Postgres' {@code oid} large-object type, which does not exist
     * here.
     */
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "bytes", nullable = false)
    private byte[] bytes;

    @Column(name = "content_type", nullable = false, length = 50)
    private String contentType;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    protected UserAvatar() {}

    public UserAvatar(Long userId, byte[] bytes, String contentType) {
        this.userId = userId;
        this.bytes = bytes;
        this.contentType = contentType;
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public byte[] getBytes() { return bytes; }
    public void setBytes(byte[] bytes) { this.bytes = bytes; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}