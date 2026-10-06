package com.nexus.backend.service.knowledge;

import com.nexus.backend.domain.knowledge.KnowledgeSourceType;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Null-safe publishers for knowledge events. Existing services receive the
 * publisher via constructor injection; unit tests that construct services with
 * mocks pass null, which this helper tolerates.
 */
public final class KnowledgeEvents {

    private KnowledgeEvents() {}

    public static void changed(ApplicationEventPublisher publisher, KnowledgeSourceType type, Long id) {
        if (publisher != null && id != null) {
            publisher.publishEvent(new KnowledgeChangeEvent(type, id));
        }
    }

    public static void removed(ApplicationEventPublisher publisher, KnowledgeSourceType type, Long id) {
        if (publisher != null && id != null) {
            publisher.publishEvent(new KnowledgeRemoveEvent(type, id));
        }
    }
}
