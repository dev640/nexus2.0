package com.nexus.backend.domain.sprint;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import com.nexus.backend.domain.project.Project;
import com.nexus.backend.tenant.TenantAware;
import com.nexus.backend.tenant.TenantStampListener;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "sprints")
@EntityListeners(TenantStampListener.class)
public class Sprint implements TenantAware {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false)
    private Integer number;

    @NotBlank(message = "Sprint goal is required")
    @Column(nullable = false, columnDefinition = "TEXT")
    private String goal;

    @NotNull(message = "Start date is required")
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @NotNull(message = "End date is required")
    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "committed_points", nullable = false)
    private Integer committedPoints = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private SprintStatus status = SprintStatus.PLANNED;

    /** Owning company, stamped on insert (V18/V19). Never reassigned. */
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
    public Sprint() {}

    public Sprint(Project project, Integer number, String goal, LocalDate startDate, LocalDate endDate) {
        this.project = project;
        this.number = number;
        this.goal = goal;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Project getProject() { return project; }
    public void setProject(Project project) { this.project = project; }

    public Integer getNumber() { return number; }
    public void setNumber(Integer number) { this.number = number; }

    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }

    public Integer getCommittedPoints() { return committedPoints; }
    public void setCommittedPoints(Integer committedPoints) { this.committedPoints = committedPoints; }

    public SprintStatus getStatus() { return status; }
    public void setStatus(SprintStatus status) { this.status = status; }

    @Override
    public Long getCompanyId() { return companyId; }

    @Override
    public void setCompanyId(Long companyId) { this.companyId = companyId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}