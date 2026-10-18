package com.nexus.backend.domain.project;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import com.nexus.backend.domain.workspace.Workspace;
import com.nexus.backend.tenant.TenantAware;
import com.nexus.backend.tenant.TenantStampListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "projects")
@EntityListeners(TenantStampListener.class)
public class Project implements TenantAware {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Project name is required")
    @Size(max = 200, message = "Project name must not exceed 200 characters")
    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ProjectStatus status = ProjectStatus.PLANNING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ProjectHealth health = ProjectHealth.ON_TRACK;

    @Column(nullable = false)
    private Integer progress = 0;

    @Column(name = "sprint_number", nullable = false)
    private Integer sprintNumber = 0;

    @Column(name = "member_count", nullable = false)
    private Integer memberCount = 0;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_id")
    private Workspace workspace;

    /**
     * Owning company, stamped on insert. Every composite foreign key in V19
     * pairs this with the referenced id, so a project can only ever sit under a
     * workspace of its own company.
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
    public Project() {}

    public Project(String name, String description, ProjectStatus status, ProjectHealth health) {
        this.name = name;
        this.description = description;
        this.status = status;
        this.health = health;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public ProjectStatus getStatus() { return status; }
    public void setStatus(ProjectStatus status) { this.status = status; }

    public ProjectHealth getHealth() { return health; }
    public void setHealth(ProjectHealth health) { this.health = health; }

    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }

    public Integer getSprintNumber() { return sprintNumber; }
    public void setSprintNumber(Integer sprintNumber) { this.sprintNumber = sprintNumber; }

    public Integer getMemberCount() { return memberCount; }
    public void setMemberCount(Integer memberCount) { this.memberCount = memberCount; }

    @Override
    public Long getCompanyId() { return companyId; }

    @Override
    public void setCompanyId(Long companyId) { this.companyId = companyId; }

    public Workspace getWorkspace() { return workspace; }
    public void setWorkspace(Workspace workspace) { this.workspace = workspace; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}