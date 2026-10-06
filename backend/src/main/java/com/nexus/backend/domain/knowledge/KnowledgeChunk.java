package com.nexus.backend.domain.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * One indexed, permission-aware slice of Nexus data. Chunks carry enough
 * metadata (source, record id, title, category, project, tags, dates, scope)
 * to be cited and filtered without joining back to the source tables.
 *
 * The generated {@code search_vector} column and its GIN index live only in
 * the Flyway migration (V8); Hibernate validates only the mapped columns.
 * {@code embedding} stores the OpenAI embedding as a JSON float array.
 */
@Entity
@Table(name = "knowledge_chunks")
public class KnowledgeChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30)
    private KnowledgeSourceType sourceType;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex = 0;

    @Column(nullable = false, length = 300)
    private String title = "";

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content = "";

    @Column(length = 50)
    private String category;

    @Column(name = "project_id")
    private Long projectId;

    @Column(columnDefinition = "TEXT")
    private String tags;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_scope", nullable = false, length = 20)
    private AccessScope accessScope = AccessScope.WORKSPACE;

    @Column(name = "owner_id")
    private Long ownerId;

    /** Updated timestamp of the source record, used for stale-index detection. */
    @Column(name = "source_updated_at")
    private LocalDateTime sourceUpdatedAt;

    /** OpenAI embedding stored as a JSON float array; null until embedded. */
    @Column(columnDefinition = "TEXT")
    private String embedding;

    @Column(name = "embedding_model", length = 50)
    private String embeddingModel;

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

    public KnowledgeSourceType getSourceType() { return sourceType; }
    public void setSourceType(KnowledgeSourceType sourceType) { this.sourceType = sourceType; }

    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }

    public Integer getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }

    public AccessScope getAccessScope() { return accessScope; }
    public void setAccessScope(AccessScope accessScope) { this.accessScope = accessScope; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }

    public LocalDateTime getSourceUpdatedAt() { return sourceUpdatedAt; }
    public void setSourceUpdatedAt(LocalDateTime sourceUpdatedAt) { this.sourceUpdatedAt = sourceUpdatedAt; }

    public String getEmbedding() { return embedding; }
    public void setEmbedding(String embedding) { this.embedding = embedding; }

    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
