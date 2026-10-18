package com.nexus.backend.domain.whiteboard;

import com.nexus.backend.tenant.TenantAware;
import com.nexus.backend.tenant.TenantStampListener;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "whiteboard_notes")
@EntityListeners(TenantStampListener.class)
public class WhiteboardNote implements TenantAware {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String board = "default";

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text = "";

    @Column(nullable = false, length = 20)
    private String color = "#fff2a8";

    @Column(nullable = false)
    private Double x = 40.0;

    @Column(nullable = false)
    private Double y = 40.0;

    @Column(length = 100)
    private String author;

    /**
     * Owning company, stamped on insert (V18/V19). The board name is only
     * unique within a company, so two companies may each have a "default"
     * board without seeing each other's notes.
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

    public String getBoard() { return board; }
    public void setBoard(String board) { this.board = board; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    public Double getX() { return x; }
    public void setX(Double x) { this.x = x; }

    public Double getY() { return y; }
    public void setY(Double y) { this.y = y; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    @Override
    public Long getCompanyId() { return companyId; }

    @Override
    public void setCompanyId(Long companyId) { this.companyId = companyId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
