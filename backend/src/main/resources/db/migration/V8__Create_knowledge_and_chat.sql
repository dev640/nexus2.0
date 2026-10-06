-- Nexus AI assistant (RAG): permission-aware knowledge index + chat storage.
--
-- knowledge_chunks holds normalized, chunked copies of Nexus data (wiki pages,
-- projects, tasks, sprints, whiteboard notes) with:
--   * metadata (source, record id, title, category, project, tags, timestamps)
--   * access scope + owner for permission-aware retrieval
--   * a generated tsvector for full-text search (GIN indexed)
--   * the OpenAI embedding as a JSON float array (TEXT), embedded server-side
-- No foreign keys on purpose: this is a derived index, kept in sync by the
-- application (incremental re-index on change, reconcile on reindex).

CREATE TABLE knowledge_chunks (
    id BIGSERIAL PRIMARY KEY,
    source_type VARCHAR(30) NOT NULL,
    source_id BIGINT NOT NULL,
    chunk_index INT NOT NULL DEFAULT 0,
    title VARCHAR(300) NOT NULL DEFAULT '',
    content TEXT NOT NULL DEFAULT '',
    category VARCHAR(50),
    project_id BIGINT,
    tags TEXT,
    access_scope VARCHAR(20) NOT NULL DEFAULT 'WORKSPACE',
    owner_id BIGINT,
    source_updated_at TIMESTAMP,
    embedding TEXT,
    embedding_model VARCHAR(50),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    search_vector tsvector GENERATED ALWAYS AS (
        to_tsvector('english', coalesce(title, '') || ' ' || coalesce(content, ''))
    ) STORED
);

CREATE INDEX idx_knowledge_chunks_search ON knowledge_chunks USING GIN (search_vector);
CREATE INDEX idx_knowledge_chunks_source ON knowledge_chunks (source_type, source_id);
CREATE INDEX idx_knowledge_chunks_project ON knowledge_chunks (project_id);
CREATE INDEX idx_knowledge_chunks_scope ON knowledge_chunks (access_scope, owner_id);
CREATE INDEX idx_knowledge_chunks_updated ON knowledge_chunks (updated_at DESC);

-- Per-user chat conversations and their messages. Sources of an assistant
-- answer are persisted as a JSON array of citation references.
CREATE TABLE chat_conversations (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(200) NOT NULL DEFAULT 'New conversation',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_chat_conversations_user ON chat_conversations (user_id, updated_at DESC);

CREATE TABLE chat_messages (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES chat_conversations(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    sources TEXT,
    mode VARCHAR(20),
    model VARCHAR(50),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_chat_messages_conversation ON chat_messages (conversation_id, created_at);
