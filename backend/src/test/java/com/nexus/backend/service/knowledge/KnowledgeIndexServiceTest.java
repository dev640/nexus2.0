package com.nexus.backend.service.knowledge;

import com.nexus.backend.domain.knowledge.AccessScope;
import com.nexus.backend.domain.knowledge.KnowledgeChunk;
import com.nexus.backend.domain.knowledge.KnowledgeSourceType;
import com.nexus.backend.repository.KnowledgeChunkRepository;
import com.nexus.backend.service.ai.AiEngine;
import com.nexus.backend.service.ai.VectorIndex;
import com.nexus.backend.tenant.CurrentTenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The reconcile pass is the one code path that both walks every source record
 * and writes the index, so it is where a scoping mistake would do the most
 * damage: one company's maintenance action re-indexing — and thereby exposing
 * through search — another company's content.
 *
 * <p>These tests pin the two locks on that door: the source feed itself is
 * asked for one company's records, and anything that arrives carrying a
 * different company (or none) is never written to the index.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KnowledgeIndexServiceTest {

    private static final Long COMPANY = 5L;

    @Mock private KnowledgeChunkRepository chunkRepository;
    @Mock private SourceDocuments sourceDocuments;
    @Mock private Chunker chunker;
    @Mock private AiEngine engine;
    @Mock private VectorIndex vectorIndex;
    @Mock private CurrentTenant currentTenant;

    @InjectMocks
    private KnowledgeIndexService service;

    @BeforeEach
    void setUp() {
        when(currentTenant.require()).thenReturn(COMPANY);
        when(chunkRepository.findSourceTimestamps(anyLong())).thenReturn(List.of());
        when(vectorIndex.size()).thenReturn(0);
        // Degraded engine: text is stored without vectors, which keeps these tests
        // about scoping rather than about embedding.
        when(engine.isConfigured()).thenReturn(false);
    }

    @Test
    void aReconcileReadsOnlyTheCallersOwnCompanysRecords() {
        SourceDocument mine = document(9L, COMPANY);
        when(sourceDocuments.all(COMPANY)).thenReturn(List.of(mine));
        when(sourceDocuments.find(KnowledgeSourceType.TASK, 9L)).thenReturn(Optional.of(mine));
        when(chunker.chunk(mine.content())).thenReturn(List.of("piece"));
        when(chunkRepository.save(any(KnowledgeChunk.class))).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeIndexService.ReindexResult result = service.reindex(false);

        // The feed is asked for one company, not for everything with a filter
        // applied afterwards: the whole point is that the other companies' rows
        // are never read at all.
        verify(sourceDocuments).all(COMPANY);

        assertThat(result.sources()).isEqualTo(1);
        assertThat(result.reindexed()).isEqualTo(1);
        assertThat(result.removed()).isZero();

        // And the chunk that results carries that same company.
        ArgumentCaptor<KnowledgeChunk> saved = ArgumentCaptor.forClass(KnowledgeChunk.class);
        verify(chunkRepository).save(saved.capture());
        assertThat(saved.getValue().getCompanyId()).isEqualTo(COMPANY);
    }

    /**
     * The in-memory filter is the second lock: even if a feed were to hand back
     * another company's document — a mis-scoped query, a bad mock, a future
     * refactor — the reconcile must not index it.
     */
    @Test
    void aDocumentFromAnotherCompanyIsNeverIndexed() {
        SourceDocument theirs = document(9L, COMPANY + 1);
        when(sourceDocuments.all(COMPANY)).thenReturn(List.of(theirs));

        KnowledgeIndexService.ReindexResult result = service.reindex(false);

        assertThat(result.sources()).isZero();
        assertThat(result.reindexed()).isZero();
        verify(sourceDocuments, never()).find(any(), anyLong());
        verify(chunkRepository, never()).save(any(KnowledgeChunk.class));
        verify(vectorIndex, never()).upsert(any(KnowledgeChunk.class));
    }

    /** A full rebuild is one company's rebuild; it cannot empty anyone else's index. */
    @Test
    void fullRebuildClearsOnlyTheCallersOwnIndex() {
        when(sourceDocuments.all(COMPANY)).thenReturn(List.of());

        service.reindex(true);

        verify(chunkRepository).deleteByCompanyId(COMPANY);
        verify(vectorIndex).clear();
        // The other companies' chunks are untouched: there is no delete-everything
        // call anywhere in this path.
        verify(chunkRepository, never()).deleteAll();
    }

    /**
     * A source record with no company cannot exist under V18, so the only way one
     * arrives here is a mapping gap — and a chunk stamped with a guessed company
     * would be filed under the wrong workspace forever.
     */
    @Test
    void aSourceWithNoCompanyIsRefusedRatherThanGuessed() {
        SourceDocument unattributed = document(9L, null);
        when(sourceDocuments.find(KnowledgeSourceType.TASK, 9L))
            .thenReturn(Optional.of(unattributed));

        service.sync(KnowledgeSourceType.TASK, 9L);

        verify(chunkRepository, never()).save(any(KnowledgeChunk.class));
        verify(vectorIndex, never()).upsert(any(KnowledgeChunk.class));
    }

    /** The company on the chunk comes from the source record, not the caller. */
    @Test
    void theChunkIsStampedWithTheSourceRecordsOwnCompany() {
        SourceDocument mine = document(9L, COMPANY);
        when(sourceDocuments.find(KnowledgeSourceType.TASK, 9L)).thenReturn(Optional.of(mine));
        when(chunker.chunk(mine.content())).thenReturn(List.of("piece one", "piece two"));
        when(chunkRepository.save(any(KnowledgeChunk.class))).thenAnswer(inv -> inv.getArgument(0));

        service.sync(KnowledgeSourceType.TASK, 9L);

        ArgumentCaptor<KnowledgeChunk> saved = ArgumentCaptor.forClass(KnowledgeChunk.class);
        verify(chunkRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
            .extracting(KnowledgeChunk::getCompanyId)
            .containsOnly(COMPANY);
        // The destructive half of the re-index is scoped to the same company too.
        verify(chunkRepository).deleteBySourceTypeAndSourceIdAndCompanyId(
            KnowledgeSourceType.TASK.name(), 9L, COMPANY);
    }

    @Test
    void statisticsAreReadForTheCallersOwnCompanyOnly() {
        when(chunkRepository.countByCompanyId(anyLong())).thenReturn(3L);
        when(chunkRepository.countByEmbeddingIsNotNullAndCompanyId(anyLong())).thenReturn(2L);
        when(chunkRepository.countBySourceTypeAndCompanyId(any(), anyLong())).thenReturn(1L);

        assertThat(service.totalChunks()).isEqualTo(3L);
        assertThat(service.embeddedChunks()).isEqualTo(2L);

        verify(chunkRepository).countByCompanyId(COMPANY);
        verify(chunkRepository).countByEmbeddingIsNotNullAndCompanyId(COMPANY);
        verify(chunkRepository, never()).countByCompanyId(COMPANY + 1);
    }

    private static SourceDocument document(Long id, Long companyId) {
        return new SourceDocument(
            KnowledgeSourceType.TASK,
            id,
            "Ship the release",
            "Status: TODO · Priority: HIGH · Story points: 5",
            "TASK",
            null,
            null,
            AccessScope.WORKSPACE,
            null,
            LocalDateTime.of(2026, 10, 1, 12, 0),
            companyId);
    }
}
