package com.ragagent.knowledge.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.TestSchema;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.ChunkRevision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * chunk 仓储语义（H2）——对照 Go internal/application/repository/chunk.go 的
 * HTTP 面方法闭包。
 *
 * <p>重点钉住 mock 测不出来的 SQL 行为：租户隔离、GORM 软删的三张面孔
 * （SELECT/UPDATE/DELETE 都带 {@code deleted_at IS NULL}）、ListPaged 的
 * {@code chunk_type IN} + {@code status IN (2,0)} 与双排序键、乐观锁 UPDATE 的
 * 影响行数判定、Save 的"全字段更新但 Omit seq_id + updated_at 刷成 now"。</p>
 *
 * <p><b>范围说明</b>：ListPaged 的 FAQ 关键词搜索分支（searchField 的四条 JSON 路径）
 * 非 PG 侧是 MySQL 语法、H2 执行不了——该分支只随后续 FAQ 模块在真 PG 上 e2e 验证；
 * 这里的 FAQ 用例只覆盖排序键（updated_at），不带 keyword。</p>
 *
 * <p>⚠️ {@code @AutoConfigureMockMvc} 是为了与其余契约测试共用同一个 Spring 上下文
 * 缓存键（理由见 {@code TenantAPIKeyRepositoryTest} 的类注释）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChunkRepositoryTest {

    private static final long TENANT = 10002L;
    private static final long OTHER_TENANT = 20002L;
    private static final String KB = "kb-1";
    /** 播种用的"过去"，用于断言 Save 把 updated_at 刷成 now。 */
    private static final OffsetDateTime PAST = OffsetDateTime.parse("2020-01-01T00:00:00Z");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ChunkMapper chunkMapper;
    @Autowired
    private ChunkRevisionMapper revisionMapper;
    @Autowired
    private ChunkRepository repo;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
    }

    // ── 播种辅助（Mapper 直插）─────────────────────────────────────────────

    private Chunk chunk(long tenantId, String knowledgeId, int index, String type, int status) {
        return chunk(tenantId, knowledgeId, index, type, status, "content-" + index);
    }

    private Chunk chunk(long tenantId, String knowledgeId, int index, String type, int status, String content) {
        Chunk c = buildChunk(tenantId, knowledgeId, index, type, status, content);
        chunkMapper.insert(c);
        return c;
    }

    /** 只构建不插入——播种前还要改 tag/is_enabled 等字段的用例用这个。 */
    private Chunk buildChunk(long tenantId, String knowledgeId, int index, String type, int status, String content) {
        Chunk c = new Chunk();
        c.setId(UUID.randomUUID().toString());
        c.setTenantId(tenantId);
        c.setKnowledgeId(knowledgeId);
        c.setKnowledgeBaseId(KB);
        c.setContent(content);
        c.setChunkIndex(index);
        c.setChunkType(type);
        c.setStatus(status);
        c.setUpdatedAt(PAST);
        return c;
    }

    /** 把已播种的行软删（模拟 GORM Delete 的产物）。 */
    private void softDelete(String chunkId) {
        jdbc.update("UPDATE chunks SET deleted_at = ? WHERE id = ?", OffsetDateTime.now(), chunkId);
    }

    /** 只构建不插入——saveChunkRevision 的入参 revision 由仓储负责落库。 */
    private ChunkRevision buildRevision(long tenantId, String knowledgeId, String chunkId,
                                        int revision, String content, boolean enabled) {
        ChunkRevision r = new ChunkRevision();
        r.setId(UUID.randomUUID().toString());
        r.setTenantId(tenantId);
        r.setKnowledgeBaseId(KB);
        r.setKnowledgeId(knowledgeId);
        r.setChunkId(chunkId);
        r.setRevision(revision);
        r.setContent(content);
        r.setEnabled(enabled);
        r.setEditorId("editor-1");
        r.setEditSource("user");
        r.setEditedAt(PAST);
        r.setCreatedAt(PAST);
        return r;
    }

    /** 构建并直插（ListChunkRevisions/GetChunkRevision 的播种用）。 */
    private ChunkRevision revision(long tenantId, String knowledgeId, String chunkId,
                                   int revision, String content, boolean enabled) {
        ChunkRevision r = buildRevision(tenantId, knowledgeId, chunkId, revision, content, enabled);
        revisionMapper.insert(r);
        return r;
    }

    private static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── GetChunkByID / GetChunkByIDOnly ────────────────────────────────────

    @Test
    void getChunkByIdIsolatesTenantsAndThrowsOnMissing() {
        Chunk mine = chunk(TENANT, "k1", 0, "text", 2);
        Chunk other = chunk(OTHER_TENANT, "k1", 1, "text", 2);

        assertThat(repo.getChunkById(TENANT, mine.getId()).getId()).isEqualTo(mine.getId());
        // 跨租户不可见（Go 的 tenant_id 过滤）
        assertThatThrownBy(() -> repo.getChunkById(TENANT, other.getId()))
                .isInstanceOf(ChunkNotFoundException.class);
        assertThatThrownBy(() -> repo.getChunkById(TENANT, "missing"))
                .isInstanceOf(ChunkNotFoundException.class);
    }

    @Test
    void getChunkByIdOnlySeesAcrossTenantsButNotSoftDeleted() {
        Chunk other = chunk(OTHER_TENANT, "k1", 0, "text", 2);
        // 无租户过滤：权限解析路径能看到跨租户行
        assertThat(repo.getChunkByIdOnly(other.getId()).getTenantId()).isEqualTo(OTHER_TENANT);

        Chunk gone = chunk(TENANT, "k1", 1, "text", 2);
        softDelete(gone.getId());
        assertThatThrownBy(() -> repo.getChunkByIdOnly(gone.getId()))
                .isInstanceOf(ChunkNotFoundException.class);
    }

    // ── ListChunksByID ─────────────────────────────────────────────────────

    @Test
    void listChunksByIdFiltersTenantAndSoftDeleteAndHandlesEmptyInput() {
        Chunk a = chunk(TENANT, "k1", 0, "text", 2);
        Chunk b = chunk(TENANT, "k1", 1, "text", 2);
        Chunk gone = chunk(TENANT, "k1", 2, "text", 2);
        softDelete(gone.getId());
        Chunk other = chunk(OTHER_TENANT, "k1", 3, "text", 2);

        List<Chunk> out = repo.listChunksById(TENANT,
                List.of(a.getId(), b.getId(), gone.getId(), other.getId()));
        assertThat(out).extracting(Chunk::getId).containsExactlyInAnyOrder(a.getId(), b.getId());

        // Go 的空切片展开成 IN (NULL) 匹配零行——Java 短路为空列表，净效果相同
        assertThat(repo.listChunksById(TENANT, List.of())).isEmpty();
    }

    // ── ListChunkByParentID ────────────────────────────────────────────────

    @Test
    void listChunkByParentIdScopesToTenantAndSoftDelete() {
        Chunk parent = chunk(TENANT, "k1", 0, "text", 2);
        Chunk child1 = chunk(TENANT, "k1", 1, "text", 2);
        Chunk child2 = chunk(TENANT, "k1", 2, "image_ocr", 2);
        jdbc.update("UPDATE chunks SET parent_chunk_id = ? WHERE id IN (?, ?)",
                parent.getId(), child1.getId(), child2.getId());
        Chunk foreignChild = chunk(OTHER_TENANT, "k1", 3, "text", 2);
        jdbc.update("UPDATE chunks SET parent_chunk_id = ? WHERE id = ?", parent.getId(), foreignChild.getId());
        Chunk goneChild = chunk(TENANT, "k1", 4, "text", 2);
        jdbc.update("UPDATE chunks SET parent_chunk_id = ? WHERE id = ?", parent.getId(), goneChild.getId());
        softDelete(goneChild.getId());

        List<Chunk> out = repo.listChunkByParentId(TENANT, parent.getId());
        assertThat(out).extracting(Chunk::getId)
                .containsExactlyInAnyOrder(child1.getId(), child2.getId());
        // Find 非 nil 语义：查不到也是空列表
        assertThat(repo.listChunkByParentId(TENANT, "missing-parent")).isEmpty();
    }

    // ── ListPagedChunksByKnowledgeID ───────────────────────────────────────

    @Test
    void listPagedOrdersByChunkIndexAndFiltersTypeStatusTenant() {
        Chunk c2 = chunk(TENANT, "k1", 2, "text", 2);
        Chunk c0 = chunk(TENANT, "k1", 0, "text", 2);
        Chunk c1 = chunk(TENANT, "k1", 1, "text", 2);
        chunk(TENANT, "k1", 3, "text", 1);      // Stored=1 → 不在 status IN (2,0) 里
        chunk(TENANT, "k1", 4, "faq", 2);       // chunk_type 白名单外
        chunk(TENANT, "k2", 5, "text", 2);      // 其他知识
        chunk(OTHER_TENANT, "k1", 6, "text", 2); // 跨租户

        ChunkRepository.ChunkPage page = repo.listPagedChunksByKnowledgeId(
                TENANT, "k1", 0, 10, List.of("text"), null, "", "", "", "doc", null);
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.items()).extracting(Chunk::getId)
                .containsExactly(c0.getId(), c1.getId(), c2.getId());
    }

    @Test
    void listPagedAppliesOffsetLimitAndDescOrder() {
        chunk(TENANT, "k1", 0, "text", 2);
        chunk(TENANT, "k1", 1, "text", 2);
        chunk(TENANT, "k1", 2, "text", 2);

        ChunkRepository.ChunkPage desc = repo.listPagedChunksByKnowledgeId(
                TENANT, "k1", 0, 2, List.of("text"), null, "", "", "desc", "doc", null);
        assertThat(desc.total()).isEqualTo(3);
        assertThat(desc.items()).extracting(Chunk::getChunkIndex).containsExactly(2, 1);

        ChunkRepository.ChunkPage second = repo.listPagedChunksByKnowledgeId(
                TENANT, "k1", 1, 1, List.of("text"), null, "", "", "asc", "doc", null);
        assertThat(second.total()).isEqualTo(3);
        assertThat(second.items()).extracting(Chunk::getChunkIndex).containsExactly(1);
    }

    @Test
    void listPagedKeywordSearchesContentForDocumentsAndTrims() {
        chunk(TENANT, "k1", 0, "text", 2, "the needle in the haystack");
        chunk(TENANT, "k1", 1, "text", 2, "nothing to see");

        ChunkRepository.ChunkPage hit = repo.listPagedChunksByKnowledgeId(
                TENANT, "k1", 0, 10, List.of("text"), null, "needle", "", "", "doc", null);
        assertThat(hit.total()).isEqualTo(1);
        assertThat(hit.items()).extracting(Chunk::getContent)
                .containsExactly("the needle in the haystack");

        // keyword 先 TrimSpace（Go strings.TrimSpace）
        ChunkRepository.ChunkPage trimmed = repo.listPagedChunksByKnowledgeId(
                TENANT, "k1", 0, 10, List.of("text"), null, "  needle\t", "", "", "doc", null);
        assertThat(trimmed.total()).isEqualTo(1);
    }

    @Test
    void listPagedAppliesTagAndEnabledFilters() {
        Chunk tagged = buildChunk(TENANT, "k1", 0, "text", 2, "content-0");
        tagged.setTagId("tag-1");
        chunkMapper.insert(tagged);
        chunk(TENANT, "k1", 1, "text", 2);
        Chunk disabled = buildChunk(TENANT, "k1", 2, "text", 2, "content-2");
        disabled.setIsEnabled(false);
        chunkMapper.insert(disabled);

        ChunkRepository.ChunkPage byTag = repo.listPagedChunksByKnowledgeId(
                TENANT, "k1", 0, 10, List.of("text"), List.of("tag-1"), "", "", "", "doc", null);
        assertThat(byTag.total()).isEqualTo(1);
        assertThat(byTag.items()).extracting(Chunk::getId).containsExactly(tagged.getId());

        ChunkRepository.ChunkPage off = repo.listPagedChunksByKnowledgeId(
                TENANT, "k1", 0, 10, List.of("text"), null, "", "", "", "doc", false);
        assertThat(off.total()).isEqualTo(1);
        assertThat(off.items()).extracting(Chunk::getId).containsExactly(disabled.getId());
    }

    @Test
    void listPagedFaqOrdersByUpdatedAtWithoutKeyword() {
        // FAQ 关键词搜索的 JSON 路径是 MySQL 语法、H2 跑不了（见类注释），这里只钉排序键
        Chunk old = faq(TENANT, "k1", "2021-01-01T00:00:00Z");
        Chunk mid = faq(TENANT, "k1", "2022-01-01T00:00:00Z");
        Chunk newest = faq(TENANT, "k1", "2023-01-01T00:00:00Z");

        ChunkRepository.ChunkPage desc = repo.listPagedChunksByKnowledgeId(
                TENANT, "k1", 0, 10, List.of("faq"), null, "", "", "", "faq", null);
        assertThat(desc.items()).extracting(Chunk::getId)
                .containsExactly(newest.getId(), mid.getId(), old.getId());

        ChunkRepository.ChunkPage asc = repo.listPagedChunksByKnowledgeId(
                TENANT, "k1", 0, 10, List.of("faq"), null, "", "", "asc", "faq", null);
        assertThat(asc.items()).extracting(Chunk::getId)
                .containsExactly(old.getId(), mid.getId(), newest.getId());
    }

    private Chunk faq(long tenantId, String knowledgeId, String updatedAt) {
        Chunk c = chunk(tenantId, knowledgeId, 0, "faq", 2);
        jdbc.update("UPDATE chunks SET updated_at = ? WHERE id = ?", OffsetDateTime.parse(updatedAt), c.getId());
        return c;
    }

    // ── UpdateChunk（Save 全字段但 Omit seq_id）────────────────────────────

    @Test
    void updateChunkRewritesAllFieldsExceptSeqIdAndBumpsUpdatedAt() {
        Chunk c = chunk(TENANT, "k1", 0, "text", 2);
        jdbc.update("UPDATE chunks SET seq_id = 42 WHERE id = ?", c.getId());

        Chunk loaded = repo.getChunkById(TENANT, c.getId());
        loaded.setContent("edited");
        loaded.setSourceContent("");
        loaded.setIsEnabled(false);
        loaded.setFlags(7);
        loaded.setMetadata(json("{\"b\":2,\"a\":1}"));
        loaded.setRelationChunks(json("[1,2]"));
        loaded.setIndirectRelationChunks(null); // Go nil JSON → SQL NULL
        loaded.setContextHeader("# heading");
        repo.updateChunk(loaded);

        // seq_id 不被覆盖（Omit("SeqID")）
        assertThat(jdbc.queryForObject(
                "SELECT seq_id FROM chunks WHERE id = ?", Long.class, c.getId())).isEqualTo(42L);
        // 三个 json 列原样写读（结构相等，与键序无关）
        Chunk after = repo.getChunkById(TENANT, c.getId());
        assertThat(after.getContent()).isEqualTo("edited");
        assertThat(after.getSourceContent()).isEmpty();      // Save 全字段：零值也写
        assertThat(after.isIsEnabled()).isFalse();
        assertThat(after.getFlags()).isEqualTo(7);
        assertThat(after.getMetadata()).isEqualTo(json("{\"b\":2,\"a\":1}"));
        assertThat(after.getRelationChunks()).isEqualTo(json("[1,2]"));
        assertThat(after.getIndirectRelationChunks()).isNull();
        // GORM autoUpdateTime：Save 把 updated_at 刷成 now 并回写
        assertThat(after.getUpdatedAt()).isAfter(PAST);
        // 回写实体（H2 截到微秒，这里只断言"确实被刷新"，不做字节级相等）
        assertThat(loaded.getUpdatedAt()).isAfter(PAST);
        // context_header（json:"-"）也落库
        assertThat(jdbc.queryForObject(
                "SELECT context_header FROM chunks WHERE id = ?", String.class, c.getId()))
                .isEqualTo("# heading");
    }

    // ── SaveChunkRevision（乐观锁）─────────────────────────────────────────

    @Test
    void saveChunkRevisionUpdatesWithOptimisticLockCleansUtf8AndInsertsSnapshot() {
        Chunk c = chunk(TENANT, "k1", 0, "text", 2);
        jdbc.update("UPDATE chunks SET content_revision = 1, content = ?, source_content = ? WHERE id = ?",
                "old-content", "old-src", c.getId());

        Chunk loaded = repo.getChunkById(TENANT, c.getId()); // contentRevision=1
        loaded.setContent("new\u0000text");                  // NUL → 清洗
        loaded.setSourceContent("src\u0000");
        loaded.setContentRevision(2);
        loaded.setIndexStatus("processing");
        loaded.setLastEditorId("user-1");
        loaded.setMetadata(json("{\"standard_question\":\"q1\"}"));
        // Go service 在调用前显式 chunk.UpdatedAt = now（map 里的 updated_at 键原样写）
        loaded.setUpdatedAt(OffsetDateTime.now());
        // saveChunkRevision 自己负责落库快照——这里只构建，不预插
        ChunkRevision rev = buildRevision(TENANT, "k1", c.getId(), 1, "old-content", true);

        repo.saveChunkRevision(loaded, rev, 1);

        Chunk after = repo.getChunkById(TENANT, c.getId());
        assertThat(after.getContent()).isEqualTo("newtext");  // CleanInvalidUtf8 清掉 NUL
        assertThat(after.getSourceContent()).isEqualTo("src");
        assertThat(after.getContentRevision()).isEqualTo(2);
        assertThat(after.getIndexStatus()).isEqualTo("processing");
        assertThat(after.getLastEditorId()).isEqualTo("user-1");
        assertThat(after.getMetadata()).isEqualTo(json("{\"standard_question\":\"q1\"}"));
        assertThat(after.getUpdatedAt()).isAfter(PAST);

        // 快照已插入
        assertThat(jdbc.queryForObject(
                "SELECT content FROM chunk_revisions WHERE id = ?", String.class, rev.getId()))
                .isEqualTo("old-content");

        // map 语义不跳零值：把 content 改成空串同样生效（这是 GORM Updates(结构体) 做不到的）
        loaded.setContent("");
        loaded.setContentRevision(3);
        repo.saveChunkRevision(loaded, buildRevision(TENANT, "k1", c.getId(), 2, "newtext", true), 2);
        assertThat(repo.getChunkById(TENANT, c.getId()).getContent()).isEmpty();
    }

    @Test
    void saveChunkRevisionConflictRollsBackAndInsertsNothing() {
        Chunk c = chunk(TENANT, "k1", 0, "text", 2);
        jdbc.update("UPDATE chunks SET content_revision = 2 WHERE id = ?", c.getId());

        Chunk loaded = repo.getChunkById(TENANT, c.getId());
        loaded.setContent("conflicting");
        loaded.setContentRevision(3);
        loaded.setUpdatedAt(OffsetDateTime.now());
        // 冲突时事务回滚：快照只构建、未落库，失败后不该出现在表里
        ChunkRevision rev = buildRevision(TENANT, "k1", c.getId(), 2, "old-content", true);

        // expectedRevision=1 与库里的 2 不符 → 影响行数 0 → ErrChunkRevisionConflict
        assertThatThrownBy(() -> repo.saveChunkRevision(loaded, rev, 1))
                .isInstanceOf(ChunkRevisionConflictException.class);

        // UPDATE 没生效、快照没插入（事务回滚）
        assertThat(jdbc.queryForObject(
                "SELECT content_revision FROM chunks WHERE id = ?", Integer.class, c.getId()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT content FROM chunks WHERE id = ?", String.class, c.getId()))
                .isEqualTo("content-0");
        assertThat(repo.listChunkRevisions(TENANT, c.getId())).isEmpty();
    }

    // ── 修订历史 ───────────────────────────────────────────────────────────

    @Test
    void listChunkRevisionsOrdersByRevisionDesc() {
        Chunk c = chunk(TENANT, "k1", 0, "text", 2);
        revision(TENANT, "k1", c.getId(), 1, "v1", true);
        revision(TENANT, "k1", c.getId(), 3, "v3", true);
        revision(TENANT, "k1", c.getId(), 2, "v2", false);

        List<ChunkRevision> out = repo.listChunkRevisions(TENANT, c.getId());
        assertThat(out).extracting(ChunkRevision::getRevision).containsExactly(3, 2, 1);
        // Find 非 nil 语义：查不到也是空列表，不是 null
        assertThat(repo.listChunkRevisions(TENANT, "missing")).isEmpty();
    }

    @Test
    void getChunkRevisionReturnsRowOrNullWhenAbsent() {
        Chunk c = chunk(TENANT, "k1", 0, "text", 2);
        revision(TENANT, "k1", c.getId(), 2, "v2", true);

        ChunkRevision hit = repo.getChunkRevision(TENANT, c.getId(), 2);
        assertThat(hit).isNotNull();
        assertThat(hit.getContent()).isEqualTo("v2");

        // 找不到返回 null（Go 把 gorm.ErrRecordNotFound 原样上抛，service 层再翻 404）
        assertThat(repo.getChunkRevision(TENANT, c.getId(), 9)).isNull();
        // 租户隔离
        assertThat(repo.getChunkRevision(OTHER_TENANT, c.getId(), 2)).isNull();
    }

    // ── 删除（软删三张面孔）────────────────────────────────────────────────

    @Test
    void deleteChunkSoftDeletesOnlyTargetRowOfTenantAndRepeatIsNoOp() {
        Chunk target = chunk(TENANT, "k1", 0, "text", 2);
        Chunk keep = chunk(TENANT, "k1", 1, "text", 2);
        Chunk other = chunk(OTHER_TENANT, "k1", 2, "text", 2);

        repo.deleteChunk(TENANT, target.getId());

        OffsetDateTime deletedAt = jdbc.queryForObject(
                "SELECT deleted_at FROM chunks WHERE id = ?", OffsetDateTime.class, target.getId());
        assertThat(deletedAt).isNotNull(); // 软删：UPDATE deleted_at，不是 DELETE
        assertThat(jdbc.queryForObject(
                "SELECT deleted_at FROM chunks WHERE id = ?", OffsetDateTime.class, keep.getId())).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT deleted_at FROM chunks WHERE id = ?", OffsetDateTime.class, other.getId())).isNull();
        // 软删后普通读不可见
        assertThatThrownBy(() -> repo.getChunkById(TENANT, target.getId()))
                .isInstanceOf(ChunkNotFoundException.class);

        // 重复删除是 no-op（WHERE 里的 deleted_at IS NULL 不再命中，时间戳不刷新）
        repo.deleteChunk(TENANT, target.getId());
        OffsetDateTime deletedAtAgain = jdbc.queryForObject(
                "SELECT deleted_at FROM chunks WHERE id = ?", OffsetDateTime.class, target.getId());
        assertThat(deletedAtAgain).isEqualTo(deletedAt);
    }

    @Test
    void deleteChunksScopesToTenantAndListedIds() {
        Chunk a1 = chunk(TENANT, "k1", 0, "text", 2);
        Chunk a2 = chunk(TENANT, "k1", 1, "text", 2);
        Chunk a3 = chunk(TENANT, "k1", 2, "text", 2);
        Chunk b1 = chunk(OTHER_TENANT, "k1", 3, "text", 2);

        // 同一批 id 里的跨租户行删不到（tenant_id AND id IN）
        repo.deleteChunks(TENANT, List.of(a1.getId(), a2.getId(), b1.getId()));

        assertThat(jdbc.queryForObject(
                "SELECT deleted_at FROM chunks WHERE id = ?", OffsetDateTime.class, a1.getId())).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT deleted_at FROM chunks WHERE id = ?", OffsetDateTime.class, a2.getId())).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT deleted_at FROM chunks WHERE id = ?", OffsetDateTime.class, a3.getId())).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT deleted_at FROM chunks WHERE id = ?", OffsetDateTime.class, b1.getId())).isNull();
        // 空列表短路（Go 同款：len == 0 直接返回）
        repo.deleteChunks(TENANT, List.of());
    }

    @Test
    void deleteChunksByKnowledgeIdScopesToTenantAndKnowledge() {
        chunk(TENANT, "k1", 0, "text", 2);
        chunk(TENANT, "k1", 1, "text", 2);
        Chunk keepOtherKnowledge = chunk(TENANT, "k2", 2, "text", 2);
        Chunk keepOtherTenant = chunk(OTHER_TENANT, "k1", 3, "text", 2);

        repo.deleteChunksByKnowledgeId(TENANT, "k1");

        Integer liveInK1 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chunks WHERE knowledge_id = 'k1' AND tenant_id = ? AND deleted_at IS NULL",
                Integer.class, TENANT);
        assertThat(liveInK1).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT deleted_at FROM chunks WHERE id = ?", OffsetDateTime.class, keepOtherKnowledge.getId()))
                .isNull();
        assertThat(jdbc.queryForObject(
                "SELECT deleted_at FROM chunks WHERE id = ?", OffsetDateTime.class, keepOtherTenant.getId()))
                .isNull();
    }

    @Test
    void deleteByKnowledgeListScopesToTenantAndIgnoresEmptyList() {
        Chunk k1 = chunk(TENANT, "k1", 0, "text", 2);
        Chunk k2 = chunk(TENANT, "k2", 1, "text", 2);
        Chunk otherTenant = chunk(OTHER_TENANT, "k1", 2, "text", 2);

        repo.deleteByKnowledgeList(TENANT, List.of("k1", "k2"));

        assertThat(jdbc.queryForObject(
                "SELECT deleted_at FROM chunks WHERE id = ?", OffsetDateTime.class, k1.getId())).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT deleted_at FROM chunks WHERE id = ?", OffsetDateTime.class, k2.getId())).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT deleted_at FROM chunks WHERE id = ?", OffsetDateTime.class, otherTenant.getId()))
                .isNull();

        // Go 无判空：空列表展开成 IN (NULL) 删不到任何行——Java 短路，净效果相同
        repo.deleteByKnowledgeList(TENANT, List.of());
    }
}
