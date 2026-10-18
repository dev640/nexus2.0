package com.nexus.backend.domain.workspace;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import com.nexus.backend.tenant.TenantAware;
import com.nexus.backend.tenant.TenantStampListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "workspaces")
@EntityListeners(TenantStampListener.class)
public class Workspace implements TenantAware {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Workspace name is required")
    @Size(max = 200, message = "Workspace name must not exceed 200 characters")
    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * Owning company. Stamped from {@link com.nexus.backend.tenant.TenantContext}
     * on insert; never reassigned afterwards, because moving a workspace would
     * orphan every project, task and message beneath it.
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

    // Constructors
    public Workspace() {}

    public Workspace(String name, String description) {
        this.name = name;
        this.description = description;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    @Override
    public Long getCompanyId() { return companyId; }

    @Override
    public void setCompanyId(Long companyId) { this.companyId = companyId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}