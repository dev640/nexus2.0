package com.nexus.backend.domain.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * One recorded administrative or security-relevant action.
 *
 * <p>Two deliberate differences from every other tenant table:
 *
 * <ul>
 *   <li><b>No foreign key on {@code actor_user_id}.</b> An audit row has to
 *       survive the deletion of the account that performed the action —
 *       otherwise wiping an account erases the record of what it did. The email
 *       is stored alongside as the human-readable identity.
 *   <li><b>{@code company_id} is nullable and assigned explicitly.</b> A
 *       SUPER_ADMIN acts outside any company, so this entity deliberately does
 *       <em>not</em> implement {@link com.nexus.backend.tenant.TenantAware} and
 *       is not stamped by {@code TenantStampListener}: stamping would refuse to
 *       write exactly the global actions that most need recording. It is listed
 *       as an explicit exception in docs/MULTI_TENANCY.md.
 * </ul>
 *
 * <p>Nothing in here may hold a password, token or other secret; {@code metadata}
 * is for values an operator would be willing to read in a report.
 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null once the acting account is deleted; never a foreign key. */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    /** The actor's email at the time of the action — the identity that survives. */
    @Column(name = "actor_email", length = 255)
    private String actorEmail;

    /** Null for a global (SUPER_ADMIN) action that belongs to no company. */
    @Column(name = "company_id")
    private Long companyId;

    /** Short verb in SCREAMING_SNAKE_CASE, e.g. {@code USER_CREATED}. */
    @Column(nullable = false, length = 100)
    private String action;

    @Column(name = "entity_type", length = 100)
    private String entityType;

    /** Always stored as text: ids are Long for some entities and UUID for others. */
    @Column(name = "entity_id", length = 100)
    private String entityId;

    @Column(length = 64)
    private String ip;

    /** Structured detail. Mapped to {@code jsonb} by Hibernate's JSON mapping. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // Constructors

    public AuditLog() {}

    public AuditLog(String action, String entityType, String entityId) {
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
    }

    // Getters and Setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getActorUserId() { return actorUserId; }
    public void setActorUserId(Long actorUserId) { this.actorUserId = actorUserId; }

    public String getActorEmail() { return actorEmail; }
    public void setActorEmail(String actorEmail) { this.actorEmail = actorEmail; }

    public Long getCompanyId() { return companyId; }
    public void setCompanyId(Long companyId) { this.companyId = companyId; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }

    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }

    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
