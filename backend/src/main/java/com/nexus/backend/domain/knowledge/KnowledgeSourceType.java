package com.nexus.backend.domain.knowledge;

/** Nexus data sources that feed the AI knowledge index. */
public enum KnowledgeSourceType {
    WIKI_PAGE("Wiki page"),
    PROJECT("Project"),
    TASK("Task"),
    SPRINT("Sprint"),
    WHITEBOARD_NOTE("Whiteboard note");

    private final String label;

    KnowledgeSourceType(String label) {
        this.label = label;
    }

    /** Human-readable label used in prompts and citations. */
    public String label() {
        return label;
    }
}
