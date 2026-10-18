package com.nexus.backend.domain.user;

import com.nexus.backend.domain.company.Company;
import com.nexus.backend.tenant.TenantAware;
import com.nexus.backend.tenant.TenantStampListener;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@EntityListeners(TenantStampListener.class)
public class User implements TenantAware {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 100, message = "Name must be between 2 and 100 characters")
    @Column(nullable = false, length = 100)
    private String name;

    @Email(message = "Email should be valid")
    @NotBlank(message = "Email is required")
    @Column(nullable = false, length = 255)
    private String email;

    /**
     * BCrypt hash of the local password. Null for accounts provisioned by
     * Supabase, which authenticate through Supabase instead. Registration still
     * requires a password: that rule lives in {@code RegisterRequest}.
     */
    @Column(length = 255)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private UserRole role = UserRole.MEMBER;

    /**
     * Supabase Auth user UUID when the account is (also) managed by Supabase.
     * Null for accounts that only use the built-in email/password flow.
     */
    @Column(name = "supabase_id", unique = true)
    private java.util.UUID supabaseId;

    /**
     * Human-facing employee code (NX-0007). Unique per company, not globally,
     * and null only for SUPER_ADMIN — a global role that belongs to no company.
     * The per-company numbering lives in {@code companies.employee_seq}.
     */
    @Column(name = "employee_code", length = 20)
    private String employeeCode;

    /**
     * The company this account belongs to. Deliberately a relation rather than
     * a bare id: the account's company is read on nearly every request (to scope
     * data) and its name is shown in the UI. Null only for SUPER_ADMIN, which is
     * enforced by {@code chk_users_company_by_role}.
     *
     * <p>Not {@code updatable = false}: promoting a user to SUPER_ADMIN (or
     * demoting one back) has to be able to move the account in and out of a
     * company.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Constructors
    public User() {}

    public User(String name, String email, String password, UserRole role) {
        this.name = name;
        this.email = email;
        this.password = password;
        this.role = role;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public UserRole getRole() { return role; }
    public void setRole(UserRole role) { this.role = role; }

    public java.util.UUID getSupabaseId() { return supabaseId; }
    public void setSupabaseId(java.util.UUID supabaseId) { this.supabaseId = supabaseId; }

    public String getEmployeeCode() { return employeeCode; }
    public void setEmployeeCode(String employeeCode) { this.employeeCode = employeeCode; }

    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }

    /**
     * The company id, or null for a SUPER_ADMIN. Reading it from the relation
     * keeps a single source of truth: a duplicate id column could disagree with
     * the relation, and only one of them would reach the database.
     */
    @Override
    public Long getCompanyId() {
        return company != null ? company.getId() : null;
    }

    /**
     * Points the account at a company by id, or at none when the id is null
     * (SUPER_ADMIN). Only the id is used for the insert — the referenced row is
     * assumed to exist, which the foreign key verifies — so a caller with an id
     * from a token does not have to load the company first.
     */
    @Override
    public void setCompanyId(Long companyId) {
        if (companyId == null) {
            this.company = null;
            return;
        }
        Company reference = new Company();
        reference.setId(companyId);
        this.company = reference;
    }

    public boolean isMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}