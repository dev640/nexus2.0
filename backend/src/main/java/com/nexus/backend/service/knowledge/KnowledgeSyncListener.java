package com.nexus.backend.service.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Applies knowledge events after the source transaction commits, so the index
 * only ever reflects persisted data. Failures are logged, never propagated:
 * a failed embedding must not roll back the user's actual work.
 */
@Component
public class KnowledgeSyncListener {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSyncListener.class);

    private final KnowledgeIndexService indexService;

    public KnowledgeSyncListener(KnowledgeIndexService indexService) {
        this.indexService = indexService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onChange(KnowledgeChangeEvent event) {
        try {
            indexService.sync(event.sourceType(), event.sourceId());
        } catch (Exception e) {
            log.warn("Indexing {} {} failed: {}", event.sourceType(), event.sourceId(), e.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRemove(KnowledgeRemoveEvent event) {
        try {
            indexService.remove(event.sourceType(), event.sourceId());
        } catch (Exception e) {
            log.warn("Removing {} {} from index failed: {}", event.sourceType(), event.sourceId(), e.getMessage());
        }
    }
}
