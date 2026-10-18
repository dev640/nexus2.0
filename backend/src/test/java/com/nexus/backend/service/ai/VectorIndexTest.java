package com.nexus.backend.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
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

@ExtendWith(MockitoExtension.class)
class VectorIndexTest {

    @Mock
    private KnowledgeChunkRepository chunkRepository;

    @Mock
    private UserRepository userRepository;

    private PermissionService permissionService;
    private VectorIndex index;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        permissionService = new PermissionService(userRepository);
        index = new VectorIndex(chunkRepository, permissionService, 100);
    }

    /**
     * The company whose vectors these tests index. The vector store is one shared
     * map, so every entry records its company and every query filters on it.
     */
    private static final Long COMPANY_ID = 9L;

    private KnowledgeChunk chunk(long id, long sourceId, AccessScope scope, Long ownerId,
                                 Long projectId, float[] vector) {
        return chunk(id, sourceId, scope, ownerId, projectId, vector, COMPANY_ID);
    }

    private KnowledgeChunk chunk(long id, long sourceId, AccessScope scope, Long ownerId,
                                 Long projectId, float[] vector, Long companyId) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(id);
        chunk.setSourceType(KnowledgeSourceType.WIKI_PAGE);
        chunk.setSourceId(sourceId);
        chunk.setCompanyId(companyId);
        chunk.setTitle("doc-" + sourceId);
        chunk.setContent("content");
        chunk.setAccessScope(scope);
        chunk.setOwnerId(ownerId);
        chunk.setProjectId(projectId);
        try {
            chunk.setEmbedding(mapper.writeValueAsString(vector));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        index.upsert(chunk);
        return chunk;
    }

    private static User user(long id, UserRole role) {
        return user(id, role, COMPANY_ID);
    }

    private static User user(long id, UserRole role, Long companyId) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        user.setCompanyId(companyId);
        return user;
    }

    @Test
    void cosineSimilarityMath() {
        assertThat(VectorIndex.cosine(new float[]{1, 0}, new float[]{1, 0})).isEqualTo(1f);
        assertThat(VectorIndex.cosine(new float[]{1, 0}, new float[]{0, 1})).isZero();
        assertThat(VectorIndex.cosine(new float[]{1, 1}, new float[]{2, 2})).isCloseTo(1f, org.assertj.core.data.Offset.offset(0.0001f));
        assertThat(VectorIndex.cosine(null, new float[]{1})).isZero();
        assertThat(VectorIndex.cosine(new float[]{1}, new float[]{1, 2})).isZero();
    }

    @Test
    void semanticSearchIsOrderedBySimilarity() {
        chunk(1, 10, AccessScope.WORKSPACE, null, null, new float[]{1f, 0f});
        chunk(2, 20, AccessScope.WORKSPACE, null, null, new float[]{0.7f, 0.7f});

        List<VectorIndex.Scored> results = index.topSemantic(new float[]{1f, 0f}, 10, user(1, UserRole.MEMBER), null);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).entry().chunkId()).isEqualTo(1L);
        assertThat(results.get(0).cosine()).isGreaterThan(results.get(1).cosine());
    }

    @Test
    void privateChunksOnlyReachTheirOwner() {
        chunk(1, 10, AccessScope.WORKSPACE, null, null, new float[]{1f, 0f});
        chunk(2, 20, AccessScope.PRIVATE, 7L, null, new float[]{1f, 0f});

        List<VectorIndex.Scored> stranger = index.topSemantic(new float[]{1f, 0f}, 10, user(8, UserRole.MEMBER), null);
        List<VectorIndex.Scored> owner = index.topSemantic(new float[]{1f, 0f}, 10, user(7, UserRole.MEMBER), null);

        assertThat(stranger).extracting(r -> r.entry().chunkId()).containsExactly(1L);
        assertThat(owner).extracting(r -> r.entry().chunkId()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void adminChunksAreHiddenFromNonAdmins() {
        chunk(1, 10, AccessScope.ADMIN, null, null, new float[]{1f, 0f});

        List<VectorIndex.Scored> member = index.topSemantic(new float[]{1f, 0f}, 10, user(8, UserRole.MEMBER), null);
        List<VectorIndex.Scored> admin = index.topSemantic(new float[]{1f, 0f}, 10, user(9, UserRole.ADMIN), null);

        assertThat(member).isEmpty();
        assertThat(admin).hasSize(1);
    }

    @Test
    void projectScopeExcludesOtherProjects() {
        chunk(1, 10, AccessScope.WORKSPACE, null, 1L, new float[]{1f, 0f});
        chunk(2, 20, AccessScope.WORKSPACE, null, 2L, new float[]{1f, 0f});
        chunk(3, 30, AccessScope.WORKSPACE, null, null, new float[]{1f, 0f});

        List<VectorIndex.Scored> scoped = index.topSemantic(new float[]{1f, 0f}, 10, user(1, UserRole.MEMBER), 1L);

        assertThat(scoped).extracting(r -> r.entry().chunkId()).containsExactlyInAnyOrder(1L, 3L);
    }

    /**
     * The store is one map shared by every company, so a vector belonging to
     * another company must never score — not even for a WORKSPACE-scoped chunk
     * its permission check would otherwise allow.
     */
    @Test
    void anotherCompanysChunksAreNeverReturned() {
        chunk(1, 10, AccessScope.WORKSPACE, null, null, new float[]{1f, 0f}, COMPANY_ID);
        chunk(2, 20, AccessScope.WORKSPACE, null, null, new float[]{1f, 0f}, 777L);

        List<VectorIndex.Scored> mine = index.topSemantic(
            new float[]{1f, 0f}, 10, user(1, UserRole.MEMBER, COMPANY_ID), null);
        List<VectorIndex.Scored> theirs = index.topSemantic(
            new float[]{1f, 0f}, 10, user(2, UserRole.MEMBER, 777L), null);

        assertThat(mine).extracting(r -> r.entry().chunkId()).containsExactly(1L);
        assertThat(theirs).extracting(r -> r.entry().chunkId()).containsExactly(2L);
    }

    /** An account with no company gets no semantic results at all. */
    @Test
    void anAccountWithNoCompanyHasNoSemanticResults() {
        chunk(1, 10, AccessScope.WORKSPACE, null, null, new float[]{1f, 0f});

        assertThat(index.topSemantic(new float[]{1f, 0f}, 10, user(1, UserRole.SUPER_ADMIN, null), null))
            .isEmpty();
    }

    @Test
    void removeBySourceDropsOnlyThatRecordsVectors() {
        chunk(1, 10, AccessScope.WORKSPACE, null, null, new float[]{1f, 0f});
        chunk(2, 11, AccessScope.WORKSPACE, null, null, new float[]{1f, 0f});

        index.removeBySource(KnowledgeSourceType.WIKI_PAGE, 10L);

        assertThat(index.size()).isEqualTo(1);
        assertThat(index.topSemantic(new float[]{1f, 0f}, 10, user(1, UserRole.MEMBER), null))
            .extracting(r -> r.entry().sourceId()).containsExactly(11L);
    }
}
