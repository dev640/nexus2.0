package com.nexus.backend.service.ai;

import com.nexus.backend.domain.knowledge.AccessScope;
import com.nexus.backend.domain.knowledge.KnowledgeChunk;
import com.nexus.backend.domain.knowledge.KnowledgeSourceType;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import com.nexus.backend.repository.KnowledgeChunkRepository;
import com.nexus.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetrievalServiceTest {

    @Mock
    private KnowledgeChunkRepository chunkRepository;

    @Mock
    private VectorIndex vectorIndex;

    @Mock
    private AiEngine engine;

    @Mock
    private UserRepository userRepository;

    private RetrievalService retrievalService;

    private final User user = new User();

    @BeforeEach
    void setUp() {
        user.setId(1L);
        user.setRole(UserRole.MEMBER);
        retrievalService = new RetrievalService(
            chunkRepository, vectorIndex, engine, new PermissionService(userRepository), 80, 60, 6, 0.08);
    }

    private KnowledgeChunk chunk(long id, KnowledgeSourceType type, long sourceId, String title) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(id);
        chunk.setSourceType(type);
        chunk.setSourceId(sourceId);
        chunk.setTitle(title);
        chunk.setContent("content of " + title);
        chunk.setAccessScope(AccessScope.WORKSPACE);
        return chunk;
    }

    @Test
    void emptyOrNonAlphanumericQuestionsSkipSearch() {
        assertThat(retrievalService.retrieve("", null, user)).isEmpty();
        assertThat(retrievalService.retrieve("   ", null, user)).isEmpty();
        assertThat(retrievalService.retrieve("??!", null, user)).isEmpty();
    }

    @Test
    void fullTextMatchesRankAheadOfKeywordOnlyMatches() {
        KnowledgeChunk alphaDoc = chunk(1L, KnowledgeSourceType.WIKI_PAGE, 10L, "Alpha doc");
        KnowledgeChunk taskDoc = chunk(2L, KnowledgeSourceType.TASK, 20L, "Alpha task");
        KnowledgeChunk otherDoc = chunk(3L, KnowledgeSourceType.PROJECT, 30L, "Other doc");

        when(chunkRepository.searchFullText(anyString(), anyLong(), anyBoolean(), any(), anyInt()))
            .thenReturn(List.of(alphaDoc, taskDoc));
        when(chunkRepository.searchKeyword(anyString(), anyLong(), anyBoolean(), any(), anyInt()))
            .thenReturn(List.of(otherDoc));

        List<RetrievedContext> results = retrievalService.retrieve("project alpha", null, user);

        assertThat(results).extracting(RetrievedContext::sourceId).containsExactly(10L, 30L, 20L);
        // Full-text rank 1 and keyword rank 1 fuse to the same score and outrank rank 2.
        assertThat(results.get(0).score()).isEqualTo(results.get(1).score());
        assertThat(results.get(1).score()).isGreaterThan(results.get(2).score());
    }

    @Test
    void atMostTwoChunksAreTakenFromTheSameSource() {
        List<KnowledgeChunk> many = java.util.stream.IntStream.rangeClosed(1, 8)
            .mapToObj(i -> chunk(i, KnowledgeSourceType.WIKI_PAGE, 10L, "doc " + i))
            .toList();
        when(chunkRepository.searchFullText(anyString(), anyLong(), anyBoolean(), any(), anyInt()))
            .thenReturn(many);

        List<RetrievedContext> results = retrievalService.retrieve("alpha", null, user);

        assertThat(results).hasSize(2);
        assertThat(results).allSatisfy(r -> assertThat(r.sourceId()).isEqualTo(10L));
    }

    @Test
    void noMatchesYieldAnEmptyContext() {
        lenient().when(chunkRepository.searchFullText(anyString(), anyLong(), anyBoolean(), any(), anyInt()))
            .thenReturn(List.of());
        lenient().when(chunkRepository.searchKeyword(anyString(), anyLong(), anyBoolean(), any(), anyInt()))
            .thenReturn(List.of());

        assertThat(retrievalService.retrieve("zzz nothing matches this", null, user)).isEmpty();
    }

    @Test
    void contextIsTruncatedToTheChunkBudget() {
        KnowledgeChunk huge = chunk(1L, KnowledgeSourceType.WIKI_PAGE, 10L, "huge");
        huge.setContent("word ".repeat(2000));
        when(chunkRepository.searchFullText(anyString(), anyLong(), anyBoolean(), any(), anyInt()))
            .thenReturn(List.of(huge));

        List<RetrievedContext> results = retrievalService.retrieve("alpha", null, user);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).content().length()).isLessThanOrEqualTo(PromptBuilder.MAX_CHUNK_CHARS + 1);
    }

    @Test
    void keywordPatternKeepsMeaningfulTokensOnly() {
        assertThat(RetrievalService.keywordPattern("What is Project-Alpha?"))
            .isEqualTo("(project|alpha)");
        assertThat(RetrievalService.keywordPattern("the of and")).isNull();
        assertThat(RetrievalService.keywordPattern("release 2026 date"))
            .isEqualTo("(release|2026|date)");
    }
}
