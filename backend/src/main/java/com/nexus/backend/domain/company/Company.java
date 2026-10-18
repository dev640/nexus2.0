package com.nexus.backend.domain.company;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;

@Entity
@Table(name = "companies")
public class Company {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Name is required")
    @Size(max = 200, message = "Name must not exceed 200 characters")
    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "legal_name", length = 200)
    private String legalName;

    @Column(name = "registration_no", length = 100)
    private String registrationNo;

    @Column(length = 100)
    private String industry;

    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(length = 100)
    private String city;

    @Column(length = 100)
    private String country;

    @Column(name = "contact_person", length = 100)
    private String contactPerson;

    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    @Column(name = "contact_phone", length = 50)
    private String contactPhone;

    @Column(length = 255)
    private String website;

    @Column(name = "company_size", length = 50)
    private String companySize;

    /**
     * Load-bearing: without {@code JdbcTypeCode(VARBINARY)} Hibernate maps
     * byte[] to Postgres' {@code oid} large-object type, which this schema does
     * not use — the column is {@code BYTEA}. See {@link com.nexus.backend.domain.user.UserAvatar}.
     */
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "logo")
    private byte[] logo;

    @Column(name = "logo_content_type", length = 50)
    private String logoContentType;

    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    @Column(name = "employee_seq", nullable = false)
    private Long employeeSeq = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Constructors
    public Company() {}

    public Company(String name) {
        this.name = name;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getLegalName() { return legalName; }
    public void setLegalName(String legalName) { this.legalName = legalName; }

    public String getRegistrationNo() { return registrationNo; }
    public void setRegistrationNo(String registrationNo) { this.registrationNo = registrationNo; }

    public String getIndustry() { return industry; }
    public void setIndustry(String industry) { this.industry = industry; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public String getContactPerson() { return contactPerson; }
    public void setContactPerson(String contactPerson) { this.contactPerson = contactPerson; }

    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }

    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }

    public String getWebsite() { return website; }
    public void setWebsite(String website) { this.website = website; }

    public String getCompanySize() { return companySize; }
    public void setCompanySize(String companySize) { this.companySize = companySize; }

    public byte[] getLogo() { return logo; }
    public void setLogo(byte[] logo) { this.logo = logo; }

    public String getLogoContentType() { return logoContentType; }
    public void setLogoContentType(String logoContentType) { this.logoContentType = logoContentType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getEmployeeSeq() { return employeeSeq; }
    public void setEmployeeSeq(Long employeeSeq) { this.employeeSeq = employeeSeq; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}