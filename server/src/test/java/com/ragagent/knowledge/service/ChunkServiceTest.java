package com.ragagent.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.TestSchema;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.BizException;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.ChunkRevision;
import com.ragagent.knowledge.domain.GeneratedQuestion;
import com.ragagent.knowledge.domain.KbIndexingStrategy;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.mapper.ChunkMapper;
import com.ragagent.knowledge.mapper.ChunkRepository;
import com.ragagent.knowledge.mapper.ChunkRevisionConflictException;
import com.ragagent.knowledge.mapper.ChunkRevisionMapper;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.model.domain.Model;
import com.ragagent.model.mapper.ModelMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * chunk service 层（编辑链路）语义（H2）——对照 Go
 * internal/application/service/{chunk,chunk_write,knowledge_write,knowledge_summary_refresh,
 * knowledge_process}.go 与 internal/searchutil、types/faq.go 的纯逻辑。
 *
 * <p>重点钉住：乐观锁冲突（409 面）、非 AppError 校验的 500 面文案、source_content 惰性
 * 回填、revision 快照记"上一个 editor"、writableChunk 的四类错误 + moving 409、
 * Upsert/Delete 生成问题的 400 原文、rebuildParentContent 的倒序替换与冲突追加、
 * 图片子块的停用联动、syncChunkIndex 执行体（策略关 → 与 Go 一致 return；
 * 策略开+模型在 → 真实出站，不可达/失败 → 上层标 failed）、生成问题行解析纯逻辑。</p>
 *
 * <p>⚠️ {@code @AutoConfigureMockMvc} 是为了与其余契约测试共用同一个 Spring 上下文缓存键
 * （理由见 {@code ChunkRepositoryTest} 的类注释）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChunkServiceTest {

    private static final long TENANT = 10002L;
    private static final String KB = "kb-1";
    private static final String DOC = "doc-1";
    private static final ObjectMapper M = new ObjectMapper();
    private static final OffsetDateTime PAST = OffsetDateTime.parse("2020-01-01T00:00:00Z");
    private static final OffsetDateTime OLDER = OffsetDateTime.parse("2021-01-01T00:00:00Z");
    private static final OffsetDateTime NEWER = OffsetDateTime.parse("2022-01-01T00:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ChunkMapper chunkMapper;
    @Autowired
    private ChunkRevisionMapper revisionMapper;
    @Autowired
    private KnowledgeMapper knowledgeMapper;
    @Autowired
    private KnowledgeBaseMapper kbMapper;
    @Autowired
    private ModelMapper modelMapper;
    @Autowired
    private ChunkRepository repo;
    @Autowired
    private ChunkService service;
    @Autowired
    private com.ragagent.common.security.SsrfGuard ssrfGuard;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        TenantContext.set(TENANT, TenantContext.webUserPrincipal("user-1"), "owner", false, "user-1", false);
        // 2026-09-25 接线批：deleteGeneratedQuestion 经 ModelRuntimeFactory.getEmbeddingModel
        // 建真实 embedder——构造期做 base URL SSRF 校验，桩 URL（127.0.0.1:1）需注白名单
        whitelistSnapshot = com.ragagent.common.security.SsrfGuard.snapshotWhitelist();
        ssrfGuard.reloadWhitelist("127.0.0.1,::1,localhost");
    }

    private com.ragagent.common.security.SsrfGuard.Whitelist whitelistSnapshot;

    @AfterEach
    void cleanup() {
        com.ragagent.common.security.SsrfGuard.restoreWhitelist(whitelistSnapshot);
        TenantContext.clear();
    }

    // ── 播种辅助 ───────────────────────────────────────────────────────────

    private KnowledgeBase kb(String id, boolean vectorEnabled) {
        KnowledgeBase k = new KnowledgeBase();
        k.setId(id);
        k.setName("kb " + id);
        k.setTenantId(TENANT);
        k.setType("document");
        KbIndexingStrategy s = new KbIndexingStrategy();
        s.setVectorEnabled(vectorEnabled);
        s.setKeywordEnabled(vectorEnabled);
        k.setIndexingStrategy(s);
        kbMapper.insert(k);
        return k;
    }

    private Knowledge knowledge(String id, String kbId) {
        return knowledge(id, kbId, null);
    }

    private Knowledge knowledge(String id, String kbId, JsonNode metadata) {
        Knowledge k = new Knowledge();
        k.setId(id);
        k.setTenantId(TENANT);
        k.setKnowledgeBaseId(kbId);
        k.setType("file");
        k.setTitle("doc " + id);
        k.setUpdatedAt(PAST);
        k.setMetadata(metadata);
        knowledgeMapper.insert(k);
        return k;
    }

    private Chunk chunk(String knowledgeId, String content) {
        return chunk(knowledgeId, content, "text");
    }

    private Chunk chunk(String knowledgeId, String content, String type) {
        Chunk c = new Chunk();
        c.setId(UUID.randomUUID().toString());
        c.setTenantId(TENANT);
        c.setKnowledgeId(knowledgeId);
        c.setKnowledgeBaseId(KB);
        c.setContent(content);
        c.setChunkIndex(0);
        c.setChunkType(type);
        c.setUpdatedAt(PAST);
        chunkMapper.insert(c);
        return c;
    }

    private Model model(String id) {
        Model m = new Model();
        m.setId(id);
        m.setTenantId(TENANT);
        m.setName("model " + id);
        m.setType("embedding");
        m.setSource("remote");
        m.setStatus("active");
        // 2026-09-22 接线后 syncChunkIndex / regenerateChunkQuestions 会真实出站：
        // baseUrl 指向 127.0.0.1:1 的不可达端口，让出站快速确定性地失败（测试禁真实网络）。
        com.ragagent.model.domain.ModelParameters p = new com.ragagent.model.domain.ModelParameters();
        p.setBaseUrl("http://127.0.0.1:1/v1");
        m.setParameters(p);
        modelMapper.insert(m);
        return m;
    }

    private void setParent(Chunk child, Chunk parent) {
        jdbc.update("UPDATE chunks SET parent_chunk_id = ? WHERE id = ?", parent.getId(), child.getId());
        child.setParentChunkId(parent.getId());
    }

    private JsonNode json(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String metadataJson(String chunkId) {
        return jdbc.queryForObject("SELECT metadata FROM chunks WHERE id = ?", String.class, chunkId);
    }

    private String indexStatus(String chunkId) {
        return jdbc.queryForObject("SELECT index_status FROM chunks WHERE id = ?", String.class, chunkId);
    }

    private String sourceContent(String chunkId) {
        return jdbc.queryForObject("SELECT source_content FROM chunks WHERE id = ?", String.class, chunkId);
    }

    // ── UpdateDocumentChunk 成功全链 ───────────────────────────────────────

    @Test
    void updateDocumentChunkFullChainBumpsRevisionSnapshotsAndBackfillsSourceContent() {
        kb(KB, false);
        knowledge(DOC, KB);
        Chunk c = chunk(DOC, "old body");
        jdbc.update("UPDATE chunks SET last_editor_id = 'prev-editor' WHERE id = ?", c.getId());

        Chunk out = service.updateDocumentChunk(c.getId(), "  new body  ", null, null);

        // 返回体（内存对象）：trim 过的内容、revision+1、ready、editor 换人
        assertThat(out.getContent()).isEqualTo("new body");
        assertThat(out.getContentRevision()).isEqualTo(1);
        assertThat(out.getIndexStatus()).isEqualTo("ready");
        assertThat(out.getLastEditorId()).isEqualTo("user-1");

        // 落库：source_content 惰性回填为编辑前正文；index_status=ready
        assertThat(repo.getChunkById(TENANT, c.getId()).getContent()).isEqualTo("new body");
        assertThat(sourceContent(c.getId())).isEqualTo("old body");
        assertThat(indexStatus(c.getId())).isEqualTo("ready");

        // 快照：记旧内容与"上一个 editor"（不是本次 actor）
        List<ChunkRevision> revisions = repo.listChunkRevisions(TENANT, c.getId());
        assertThat(revisions).hasSize(1);
        ChunkRevision snap = revisions.get(0);
        assertThat(snap.getRevision()).isEqualTo(0);
        assertThat(snap.getContent()).isEqualTo("old body");
        assertThat(snap.getEditorId()).isEqualTo("prev-editor");
        assertThat(snap.getEditSource()).isEqualTo("user");
        assertThat(snap.isEnabled()).isTrue();
        assertThat(snap.getEditedAt()).isEqualTo(PAST);
    }

    @Test
    void updateDocumentChunkNoChangeRetriesFailedIndexBackToReady() {
        kb(KB, false);
        knowledge(DOC, KB);
        Chunk c = chunk(DOC, "same body");
        jdbc.update("UPDATE chunks SET index_status = 'failed' WHERE id = ?", c.getId());

        Chunk out = service.updateDocumentChunk(c.getId(), "same body", null, null);

        // 无变化但卡在 failed：processing → syncChunkIndex（策略关，直接过）→ ready
        assertThat(out.getIndexStatus()).isEqualTo("ready");
        assertThat(indexStatus(c.getId())).isEqualTo("ready");
        // revision 不动、快照不产生
        assertThat(repo.getChunkById(TENANT, c.getId()).getContentRevision()).isZero();
        assertThat(repo.listChunkRevisions(TENANT, c.getId())).isEmpty();
    }

    @Test
    void updateDocumentChunkConflictOnStaleExpectedRevision() {
        kb(KB, false);
        knowledge(DOC, KB);
        Chunk c = chunk(DOC, "body");

        assertThatThrownBy(() -> service.updateDocumentChunk(c.getId(), "new", null, 5))
                .isInstanceOf(ChunkRevisionConflictException.class);

        // 库未动
        assertThat(repo.getChunkById(TENANT, c.getId()).getContent()).isEqualTo("body");
        assertThat(repo.listChunkRevisions(TENANT, c.getId())).isEmpty();
    }

    @Test
    void updateDocumentChunkRejectsEmptyAndOversizedAndNonText() {
        kb(KB, false);
        knowledge(DOC, KB);
        Chunk c = chunk(DOC, "body");
        Chunk image = chunk(DOC, "ocr text", "image_ocr");

        // 纯空白 → "chunk content cannot be empty"（Go fmt.Errorf → handler 500 面）
        assertThatThrownBy(() -> service.updateDocumentChunk(c.getId(), "   \n\t ", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("chunk content cannot be empty");
        // 超过 200000 字节（UTF-8 字节计）
        assertThatThrownBy(() -> service.updateDocumentChunk(c.getId(), "x".repeat(200001), null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("chunk content exceeds 200000 bytes");
        // 恰好 200000 字节可以通过（边界）
        Chunk ok = service.updateDocumentChunk(c.getId(), "y".repeat(200000), null, null);
        assertThat(ok.getContent()).hasSize(200000);
        // 非 text 块
        assertThatThrownBy(() -> service.updateDocumentChunk(image.getId(), "new", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("only text chunks can be edited");
    }

    // ── 图片校验与图片子块联动 ─────────────────────────────────────────────

    @Test
    void updateDocumentChunkRejectsAddingNewImagesButKeepsExisting() {
        kb(KB, false);
        knowledge(DOC, KB);
        Chunk plain = chunk(DOC, "body");
        // 新增 Markdown 图 → 拒绝
        assertThatThrownBy(() -> service.updateDocumentChunk(plain.getId(),
                "body\n![x](local://1/new.png)", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("adding images to an existing chunk is not supported: local://1/new.png");
        // 新增 HTML 图 → 同样拒绝
        assertThatThrownBy(() -> service.updateDocumentChunk(plain.getId(),
                "body <img src=\"local://2/new.png\">", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("adding images to an existing chunk is not supported: local://2/new.png");

        // 已有图（Markdown + HTML 混排）保留后编辑 → 通过
        Chunk withImages = chunk(DOC, "![cap](local://1/a.png)\n<img class=\"k\" src=\" local://1/b.png \">tail");
        Chunk out = service.updateDocumentChunk(withImages.getId(),
                "![cap](local://1/a.png)\n<img class=\"k\" src=\" local://1/b.png \">edited", null, null);
        assertThat(out.getContentRevision()).isEqualTo(1);
    }

    @Test
    void removingImageDisablesItsOcrChild() {
        kb(KB, false);
        knowledge(DOC, KB);
        Chunk c = chunk(DOC, "![x](local://1/a.png) body");
        Chunk ocr = chunk(DOC, "OCR TEXT", "image_ocr");
        ocr.setImageInfo("[{\"url\":\"local://1/a.png\",\"original_url\":\"\",\"ocr_text\":\"OCR TEXT\"}]");
        setParent(ocr, c);
        chunkMapper.updateById(ocr);

        service.updateDocumentChunk(c.getId(), "body", null, null);

        // 图被删 → image_ocr 子块停用（软停用），索引同步走完回到 ready
        Chunk after = repo.getChunkById(TENANT, ocr.getId());
        assertThat(after.isIsEnabled()).isFalse();
        assertThat(after.getIndexStatus()).isEqualTo("ready");
    }

    @Test
    void keepingImageLeavesEnabledChildUntouched() {
        kb(KB, false);
        knowledge(DOC, KB);
        Chunk c = chunk(DOC, "![x](local://1/a.png) body");
        Chunk ocr = chunk(DOC, "OCR TEXT", "image_ocr");
        ocr.setImageInfo("[{\"url\":\"local://1/a.png\"}]");
        setParent(ocr, c);
        chunkMapper.updateById(ocr);
        OffsetDateTime before = repo.getChunkById(TENANT, ocr.getId()).getUpdatedAt();

        service.updateDocumentChunk(c.getId(), "![x](local://1/a.png) edited body", null, null);

        // 图仍在：子块已 enabled 且 ready → 完全跳过（updated_at 不刷新）
        Chunk after = repo.getChunkById(TENANT, ocr.getId());
        assertThat(after.isIsEnabled()).isTrue();
        assertThat(after.getUpdatedAt()).isEqualTo(before);
    }

    // ── writableChunk 四类错误 + moving 409 ───────────────────────────────

    @Test
    void writableChunkErrorFamilies() {
        kb(KB, false);
        knowledge(DOC, KB);
        Chunk c = chunk(DOC, "body");

        // 1. 租户缺 → 401 workspace context unavailable
        TenantContext.clear();
        assertThatThrownBy(() -> service.deleteChunk(c.getId()))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.appError().httpCode()).isEqualTo(401);
                    assertThat(e.appError().message()).isEqualTo("workspace context unavailable");
                });
        TenantContext.set(TENANT, TenantContext.webUserPrincipal("user-1"), "owner", false, "user-1", false);

        // 2. chunk 缺 → 404 chunk not found
        assertThatThrownBy(() -> service.deleteChunk("missing"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.appError().httpCode()).isEqualTo(404);
                    assertThat(e.appError().message()).isEqualTo("chunk not found");
                });

        // 3. knowledge 缺 → 404 knowledge not found
        Chunk orphan = chunk("no-such-doc", "body");
        assertThatThrownBy(() -> service.deleteChunk(orphan.getId()))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.appError().httpCode()).isEqualTo(404);
                    assertThat(e.appError().message()).isEqualTo("knowledge not found");
                });

        // 4. chunk 不在 knowledge 的 KB 上 → 403
        Chunk mismatched = chunk(DOC, "body");
        jdbc.update("UPDATE chunks SET knowledge_base_id = 'kb-other' WHERE id = ?", mismatched.getId());
        assertThatThrownBy(() -> service.deleteChunk(mismatched.getId()))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.appError().httpCode()).isEqualTo(403);
                    assertThat(e.appError().message()).isEqualTo("chunk does not belong to its knowledge base");
                });

        // 5. moving 中的文档 → 409（RejectMovingKnowledge）
        Knowledge moving = knowledge("doc-moving", KB,
                json("{\"_knowledge_transfer\":{\"operation\":\"move\",\"phase\":\"moving\"}}"));
        Chunk movingChunk = chunk("doc-moving", "body");
        assertThat(moving.getMetadata()).isNotNull();
        assertThatThrownBy(() -> service.deleteChunk(movingChunk.getId()))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.appError().httpCode()).isEqualTo(409);
                    assertThat(e.appError().message())
                            .isEqualTo("knowledge has an unfinished move; retry the move first");
                });
        // phase 非 moving → 放行
        jdbc.update("UPDATE knowledges SET metadata = ? WHERE id = ?",
                json("{\"_knowledge_transfer\":{\"operation\":\"move\",\"phase\":\"done\"}}").toString(),
                "doc-moving");
        service.deleteChunk(movingChunk.getId());
        assertThat(jdbc.queryForObject(
                "SELECT deleted_at FROM chunks WHERE id = ?", OffsetDateTime.class, movingChunk.getId()))
                .isNotNull();
    }

    // ── Revert ─────────────────────────────────────────────────────────────

    @Test
    void revertDocumentChunkRestoresSnapshotContentAndBumpsRevision() {
        kb(KB, false);
        knowledge(DOC, KB);
        Chunk c = chunk(DOC, "v2 body");
        jdbc.update("UPDATE chunks SET content_revision = 2 WHERE id = ?", c.getId());
        ChunkRevision snap = new ChunkRevision();
        snap.setId(UUID.randomUUID().toString());
        snap.setTenantId(TENANT);
        snap.setKnowledgeBaseId(KB);
        snap.setKnowledgeId(DOC);
        snap.setChunkId(c.getId());
        snap.setRevision(1);
        snap.setContent("v1 body");
        snap.setEnabled(true);
        snap.setEditorId("old-editor");
        snap.setEditSource("user");
        snap.setEditedAt(PAST);
        snap.setCreatedAt(PAST);
        revisionMapper.insert(snap);

        Chunk out = service.revertDocumentChunk(c.getId(), 1, null);

        assertThat(out.getContent()).isEqualTo("v1 body");
        assertThat(out.getContentRevision()).isEqualTo(3);
        assertThat(sourceContent(c.getId())).isEqualTo("v2 body"); // 惰性回填的是回滚前正文
        // 回滚本身也产生快照（revision=2，内容是回滚前的 v2 body）
        List<ChunkRevision> revisions = repo.listChunkRevisions(TENANT, c.getId());
        assertThat(revisions).extracting(ChunkRevision::getRevision).containsExactly(2, 1);
        assertThat(revisions.get(0).getContent()).isEqualTo("v2 body");
        assertThat(indexStatus(c.getId())).isEqualTo("ready");
    }

    @Test
    void revertDocumentChunkUnknownRevisionIsBadRequestWithGormText() {
        kb(KB, false);
        knowledge(DOC, KB);
        Chunk c = chunk(DOC, "body");

        // Go：gorm.ErrRecordNotFound 原文 → RevertChunk handler 包 400
        assertThatThrownBy(() -> service.revertDocumentChunk(c.getId(), 9, null))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.appError().httpCode()).isEqualTo(400);
                    assertThat(e.appError().message()).isEqualTo("record not found");
                });
    }

    // ── Upsert 生成问题 ────────────────────────────────────────────────────

    @Test
    void upsertGeneratedQuestionCreatesThenUpdates() {
        kb(KB, false);
        knowledge(DOC, KB);
        Chunk c = chunk(DOC, "body");

        // 创建（question trim；content_revision 钉到当前版本 0）
        GeneratedQuestion created = service.upsertGeneratedQuestion(c.getId(), "", "  What is X?  ");
        assertThat(created.getId()).isNotBlank();
        assertThat(created.getQuestion()).isEqualTo("What is X?");
        assertThat(created.getContentRevision()).isEqualTo(0);

        JsonNode meta = json(metadataJson(c.getId()));
        assertThat(meta.path("generated_questions")).hasSize(1);
        assertThat(meta.path("generated_questions").get(0).path("id").asText())
                .isEqualTo(created.getId());
        // generated_questions_revision=0 被 omitempty 省略（Go 同款）
        assertThat(meta.has("generated_questions_revision")).isFalse();

        // 更新（同 ID 覆盖问题文本）
        GeneratedQuestion updated = service.upsertGeneratedQuestion(c.getId(), created.getId(), "What is Y?");
        assertThat(updated.getId()).isEqualTo(created.getId());
        assertThat(updated.getQuestion()).isEqualTo("What is Y?");
        assertThat(json(metadataJson(c.getId())).path("generated_questions")).hasSize(1);

        // revision 提升后，再次更新把 content_revision 钉到新版本
        service.updateDocumentChunk(c.getId(), "edited body", null, null);
        GeneratedQuestion repinned = service.upsertGeneratedQuestion(c.getId(), created.getId(), "What is Z?");
        assertThat(repinned.getContentRevision()).isEqualTo(1);
        assertThat(json(metadataJson(c.getId())).path("generated_questions").get(0)
                .path("content_revision").asInt()).isEqualTo(1);
    }

    @Test
    void upsertGeneratedQuestionToleratesUnknownMetadataKeys() {
        kb(KB, false);
        knowledge(DOC, KB);
        Chunk c = chunk(DOC, "body");
        jdbc.update("UPDATE chunks SET metadata = ? WHERE id = ?",
                json("{\"generated_questions\":[{\"id\":\"q1\",\"question\":\"A\"}],\"future_key\":123}")
                        .toString(),
                c.getId());

        GeneratedQuestion out = service.upsertGeneratedQuestion(c.getId(), "q1", "A2");
        assertThat(out.getQuestion()).isEqualTo("A2");
        // 未知键在写入时被丢弃（Go 的 struct 序列化同款）
        assertThat(json(metadataJson(c.getId())).has("future_key")).isFalse();
    }

    @Test
    void upsertGeneratedQuestionFailuresUseGoTexts() {
        kb(KB, false);
        knowledge(DOC, KB);
        Chunk c = chunk(DOC, "body");

        // 空问题
        assertThatThrownBy(() -> service.upsertGeneratedQuestion(c.getId(), "", "   "))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.appError().httpCode()).isEqualTo(400);
                    assertThat(e.appError().message()).isEqualTo("question cannot be empty");
                });
        // questionId 不存在
        assertThatThrownBy(() -> service.upsertGeneratedQuestion(c.getId(), "nope", "q"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.appError().httpCode()).isEqualTo(400);
                    assertThat(e.appError().message()).isEqualTo("question not found");
                });
        // chunk 缺失：writableChunk 的 AppError 被 400 原文包装（Go handler 同款：
        // 信封 code=1000，message = "error code: 1003, error message: chunk not found"）
        assertThatThrownBy(() -> service.upsertGeneratedQuestion("missing", "", "q"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.appError().httpCode()).isEqualTo(400);
                    assertThat(e.appError().code()).isEqualTo(1000);
                    assertThat(e.appError().message())
                            .isEqualTo("error code: 1003, error message: chunk not found");
                });
    }

    // ── Delete 生成问题 ────────────────────────────────────────────────────

    @Test
    void deleteGeneratedQuestionUpdatesMetadata() {
        kb(KB, false);
        knowledge(DOC, KB);
        model("emb-1");
        jdbc.update("UPDATE knowledge_bases SET embedding_model_id = 'emb-1' WHERE id = ?", KB);
        Chunk c = chunk(DOC, "body");
        jdbc.update("UPDATE chunks SET metadata = ? WHERE id = ?",
                json("{\"generated_questions\":[{\"id\":\"q1\",\"question\":\"A\"},{\"id\":\"q2\",\"question\":\"B\"}]}")
                        .toString(),
                c.getId());

        service.deleteGeneratedQuestion(c.getId(), "q1");

        JsonNode meta = json(metadataJson(c.getId()));
        assertThat(meta.path("generated_questions")).hasSize(1);
        assertThat(meta.path("generated_questions").get(0).path("id").asText()).isEqualTo("q2");

        // 再删不存在的 → Go 原文
        assertThatThrownBy(() -> service.deleteGeneratedQuestion(c.getId(), "q1"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.appError().message())
                            .isEqualTo("question with ID q1 not found in chunk " + c.getId());
                });
    }

    @Test
    void deleteGeneratedQuestionFailureBranchesUseGoTexts() {
        kb(KB, false);
        knowledge(DOC, KB);
        Chunk c = chunk(DOC, "body");

        // 无 metadata
        assertThatThrownBy(() -> service.deleteGeneratedQuestion(c.getId(), "q1"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.appError().message())
                            .isEqualTo("no generated questions found for chunk " + c.getId());
                });

        // 嵌入模型未配置（KB 无 embedding_model_id）→ Go 的
        // "failed to get embedding model: model ID cannot be empty"
        jdbc.update("UPDATE chunks SET metadata = ? WHERE id = ?",
                json("{\"generated_questions\":[{\"id\":\"q1\",\"question\":\"A\"}]}").toString(), c.getId());
        assertThatThrownBy(() -> service.deleteGeneratedQuestion(c.getId(), "q1"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.appError().message())
                            .isEqualTo("failed to get embedding model: model ID cannot be empty");
                });

        // 模型 ID 配了但行不存在 → "failed to get embedding model: model not found"
        jdbc.update("UPDATE knowledge_bases SET embedding_model_id = 'ghost' WHERE id = ?", KB);
        assertThatThrownBy(() -> service.deleteGeneratedQuestion(c.getId(), "q1"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.appError().message())
                            .isEqualTo("failed to get embedding model: model not found");
                });
    }

    // ── RegenerateChunkQuestions ───────────────────────────────────────────

    @Test
    void regenerateChunkQuestionsDeterministicBranches() {
        kb(KB, false);
        knowledge(DOC, KB);
        Chunk image = chunk(DOC, "ocr", "image_ocr");
        Chunk text = chunk(DOC, "body");

        // chunk 缺失 → Go 哨兵原文 400
        assertThatThrownBy(() -> service.regenerateChunkQuestions("missing"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.appError().message()).isEqualTo("chunk not found");
                });
        // 非 text 块
        assertThatThrownBy(() -> service.regenerateChunkQuestions(image.getId()))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.appError().message())
                            .isEqualTo("questions can only be generated for text chunks");
                });
        // 无 summary model
        assertThatThrownBy(() -> service.regenerateChunkQuestions(text.getId()))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.appError().message())
                            .isEqualTo("summary model is required for question generation");
                });

        // summary model 行存在 → 2026-09-22 走查批接线：真实出站（baseUrl=127.0.0.1:1
        // 不可达）→ 失败按 Go err.Error() 包 400（不再是阶段占位文案）
        model("chat-1");
        jdbc.update("UPDATE knowledge_bases SET summary_model_id = 'chat-1' WHERE id = ?", KB);
        assertThatThrownBy(() -> service.regenerateChunkQuestions(text.getId()))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.appError().message())
                            .isNotEqualTo("summary model is not available in this deployment");
                });
    }

    // ── 生成问题行解析（接线后 LLM 输出的确定性片段）────────────────────────

    @Test
    void parseGeneratedQuestionsTrimsPrefixesDropsShortLinesAndCapsCount() {
        String output = "1. 什么是知识库？\n"
                + "- 如何配置嵌入模型\n"
                + "  *  支持哪些文件格式？  \n"
                + "\n"
                + "短\n"                          // <6 字节 → 丢弃
                + "3) 这是第五个问题吗？\n"
                + "4. 超出数量上限的问题";
        // count=3：前三行生效即止（"短" 与超限行不入）
        assertThat(ChunkService.parseGeneratedQuestions(output, 3))
                .containsExactly("什么是知识库？", "如何配置嵌入模型", "支持哪些文件格式？");
        // count=10：短行仍被丢弃（字节数 <=5）
        assertThat(ChunkService.parseGeneratedQuestions(output, 10))
                .containsExactly("什么是知识库？", "如何配置嵌入模型", "支持哪些文件格式？",
                        "这是第五个问题吗？", "超出数量上限的问题");
        // 非正 count / 空输出 → 空列表（对照 Go content=="" || count<=0 → nil）
        assertThat(ChunkService.parseGeneratedQuestions(output, 0)).isEmpty();
        assertThat(ChunkService.parseGeneratedQuestions(null, 3)).isEmpty();
    }

    // ── DeleteChunk / DeleteChunksByKnowledgeID ───────────────────────────

    @Test
    void deleteChunkSoftDeletesThroughWritableGuard() {
        kb(KB, false);
        knowledge(DOC, KB);
        Chunk c = chunk(DOC, "body");

        service.deleteChunk(c.getId());
        assertThat(jdbc.queryForObject(
                "SELECT deleted_at FROM chunks WHERE id = ?", OffsetDateTime.class, c.getId()))
                .isNotNull();
        // 软删后不可再写
        assertThatThrownBy(() -> service.deleteChunk(c.getId()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("chunk not found");
    }

    @Test
    void deleteChunksByKnowledgeIdValidatesBeforeDeleting() {
        kb(KB, false);
        knowledge(DOC, KB);
        Chunk a = chunk(DOC, "a");
        Chunk b = chunk(DOC, "b");

        // blank → 400 resource ID cannot be empty
        assertThatThrownBy(() -> service.deleteChunksByKnowledgeId("   "))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.appError().message()).isEqualTo("resource ID cannot be empty");
                });
        // knowledge 缺失 → 404
        assertThatThrownBy(() -> service.deleteChunksByKnowledgeId("no-doc"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.appError().httpCode()).isEqualTo(404);
                    assertThat(e.appError().message()).isEqualTo("knowledge not found");
                });
        // 成功：软删该 knowledge 下全部块
        service.deleteChunksByKnowledgeId(DOC);
        assertThat(jdbc.queryForObject(
                "SELECT deleted_at FROM chunks WHERE id = ?", OffsetDateTime.class, a.getId()))
                .isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT deleted_at FROM chunks WHERE id = ?", OffsetDateTime.class, b.getId()))
                .isNotNull();
    }

    // ── rebuildParentContent ───────────────────────────────────────────────

    @Test
    void rebuildParentContentOverlaysEditedChildByOffsetAndBackfillsSource() {
        kb(KB, false);
        knowledge(DOC, KB);
        Chunk parent = chunk(DOC, "AAAA.BBBB.CCCC.DDDD", "parent_text");
        parent.setStartAt(0);
        parent.setEndAt(19);
        chunkMapper.updateById(parent);

        Chunk child = chunk(DOC, "BBBB");
        child.setStartAt(5);
        child.setEndAt(9);
        child.setContentRevision(1);
        setParent(child, parent);
        chunkMapper.updateById(child);

        // 编辑子块触发 rebuild（bodyChanged && parent 非空）
        service.updateDocumentChunk(child.getId(), "XXXX", null, null);

        Chunk after = repo.getChunkById(TENANT, parent.getId());
        assertThat(after.getContent()).isEqualTo("AAAA.XXXX.CCCC.DDDD");
        // 父块 source_content 首次回填（原解析原文不丢）
        assertThat(after.getSourceContent()).isEqualTo("AAAA.BBBB.CCCC.DDDD");
    }

    @Test
    void rebuildParentContentKeepsLatestEditAndAppendsConflictingBody() {
        kb(KB, false);
        knowledge(DOC, KB);
        Chunk parent = chunk(DOC, "0123456789abcdefghij", "parent_text");
        parent.setStartAt(0);
        parent.setEndAt(20);
        parent.setSourceContent("0123456789abcdefghij");
        chunkMapper.updateById(parent);

        // 两个已编辑子块区间重叠：[2,6) 与 [4,10)
        Chunk newer = chunk(DOC, "NEW");
        newer.setStartAt(2);
        newer.setEndAt(6);
        newer.setContentRevision(1);
        newer.setUpdatedAt(NEWER);
        setParent(newer, parent);
        chunkMapper.updateById(newer);

        Chunk older = chunk(DOC, "OLD");
        older.setStartAt(4);
        older.setEndAt(10);
        older.setContentRevision(1);
        older.setUpdatedAt(OLDER);
        setParent(older, parent);
        chunkMapper.updateById(older);

        // 再编辑 newer 子块触发 rebuild；其当前正文 "NEW2" 落在 [2,6)
        service.updateDocumentChunk(newer.getId(), "NEW2", null, null);

        Chunk after = repo.getChunkById(TENANT, parent.getId());
        // 最新编辑占住区间；被挤掉的 older 正文经 JoinChunkContent 追加（无重叠 → "\n\n" 相连）
        assertThat(after.getContent()).isEqualTo("01NEW26789abcdefghij\n\nOLD");
        assertThat(after.getSourceContent()).isEqualTo("0123456789abcdefghij");
    }

    // ── syncChunkIndex 执行体（2026-09-22 接线侧）────────────────────────────

    @Test
    void updateDocumentChunkMarksFailedWhenEmbeddingOutboundUnreachable() {
        kb(KB, true); // 策略开向量 → 需要 embedding 模型
        knowledge(DOC, KB);
        model("emb-1");
        jdbc.update("UPDATE knowledge_bases SET embedding_model_id = 'emb-1' WHERE id = ?", KB);
        Chunk c = chunk(DOC, "body");

        // 模型在、出站不可达（测试模型 baseUrl=127.0.0.1:1）→ 上层标 index_status=failed
        // 并返回 chunk（不抛）——Go 在 BatchIndex 失败时同款（index_status=failed 仍落库）
        Chunk out = service.updateDocumentChunk(c.getId(), "edited", null, null);
        assertThat(out.getIndexStatus()).isEqualTo("failed");
        assertThat(indexStatus(c.getId())).isEqualTo("failed");
        // 行仍然保存（revision+1、快照在——UI 不能拿到假成功）
        assertThat(out.getContentRevision()).isEqualTo(1);
        assertThat(repo.listChunkRevisions(TENANT, c.getId())).hasSize(1);
    }

    @Test
    void updateDocumentChunkMarksFailedWhenEmbeddingModelRowMissing() {
        kb(KB, true); // 策略开、模型行缺 → Go GetEmbeddingModel 失败同款
        knowledge(DOC, KB);
        jdbc.update("UPDATE knowledge_bases SET embedding_model_id = 'ghost' WHERE id = ?", KB);
        Chunk c = chunk(DOC, "body");

        Chunk out = service.updateDocumentChunk(c.getId(), "edited", null, null);
        assertThat(out.getIndexStatus()).isEqualTo("failed");
        assertThat(indexStatus(c.getId())).isEqualTo("failed");
    }

    // ── ListChunkRevisions ─────────────────────────────────────────────────

    @Test
    void listChunkRevisionsReturnsDescOrder() {
        kb(KB, false);
        knowledge(DOC, KB);
        Chunk c = chunk(DOC, "body");
        for (int r : new int[] {1, 3, 2}) {
            ChunkRevision rev = new ChunkRevision();
            rev.setId(UUID.randomUUID().toString());
            rev.setTenantId(TENANT);
            rev.setKnowledgeBaseId(KB);
            rev.setKnowledgeId(DOC);
            rev.setChunkId(c.getId());
            rev.setRevision(r);
            rev.setContent("v" + r);
            rev.setEnabled(true);
            rev.setEditorId("");
            rev.setEditSource("user");
            rev.setEditedAt(PAST);
            rev.setCreatedAt(PAST);
            revisionMapper.insert(rev);
        }
        assertThat(service.listChunkRevisions(c.getId()))
                .extracting(ChunkRevision::getRevision)
                .containsExactly(3, 2, 1);
    }

    // ── 纯逻辑（searchutil / faq.go）───────────────────────────────────────

    @Test
    void imageURLsInContentCoversMarkdownAndHtmlButNotDataSrcOrUnquoted() {
        String content = "![alt](local://1/a.png)\n"
                + "<img class=\"x\" src=\"local://1/b.png\" alt=\"k\">\n"
                + "<img data-src=\"lazy.png\" src=\"local://1/c.png\">\n"
                + "<img src=unquoted.png>\n"
                + "<IMG SRC=' local://1/d.png '>";
        Set<String> urls = ChunkSearchUtil.imageURLsInContent(content);
        // Markdown 原文精确匹配；HTML src 经 TrimSpace；data-src 与无引号 src 不算
        assertThat(urls).containsExactlyInAnyOrder(
                "local://1/a.png", "local://1/b.png", "local://1/c.png", "local://1/d.png");
        assertThat(ChunkSearchUtil.imageURLsInContent("plain text")).isEmpty();
        assertThat(ChunkSearchUtil.imageURLsInContent("")).isEmpty();
    }

    @Test
    void imageURLsFromInfoParsesUrlAndOriginalAndToleratesGarbage() {
        assertThat(ChunkSearchUtil.imageURLsFromInfo(
                "[{\"url\":\"u1\",\"original_url\":\"o1\"},{\"url\":\"\",\"original_url\":\"o2\"}]"))
                .containsExactlyInAnyOrder("u1", "o1", "o2");
        assertThat(ChunkSearchUtil.imageURLsFromInfo("not json")).isEmpty();
        assertThat(ChunkSearchUtil.imageURLsFromInfo("")).isEmpty();
        assertThat(ChunkSearchUtil.imageURLsFromInfo(null)).isEmpty();
    }

    @Test
    void generatedQuestionSourceIdShortKeptLongHashed() {
        // 短 ID：历史表示原样保留
        assertThat(ChunkSearchUtil.generatedQuestionSourceId("chunk", "q1")).isEqualTo("chunk-q1");
        // 恰好 64 字节：仍是原样（36 + 1 + 27）
        String kb36 = "11111111-1111-1111-1111-111111111111";
        String q27 = "aaaaaaaaaaaaaaaaaaaaaaaaaaa";
        assertThat(kb36.length() + 1 + q27.length()).isEqualTo(64);
        assertThat(ChunkSearchUtil.generatedQuestionSourceId(kb36, q27)).isEqualTo(kb36 + "-" + q27);
        // 73 字节的 UUID 对：sha256(questionID) 前 12 字节 hex（Go 实录语料）
        String q36 = "3f2b8a1c-9d4e-4f0a-8b7c-1d2e3f4a5b6c";
        assertThat(ChunkSearchUtil.generatedQuestionSourceId(kb36, q36))
                .isEqualTo(kb36 + "-q976c5fc9daf703cb3aff0926")
                .hasSize(62);
    }

    @Test
    void joinChunkContentCollapsesContainmentOverlapAndJoins() {
        // 空 ×2
        assertThat(ChunkSearchUtil.joinChunkContent("", "next", "\n\n")).isEqualTo("next");
        assertThat(ChunkSearchUtil.joinChunkContent("acc", "", "\n\n")).isEqualTo("acc");
        // 完全包含（≥12 rune）→ 折叠
        assertThat(ChunkSearchUtil.joinChunkContent(
                "the quick brown fox jumps over", "quick brown fox", "\n\n"))
                .isEqualTo("the quick brown fox jumps over");
        // 真实后缀/前缀重叠（15 rune ≥ 12）→ 去重拼接
        assertThat(ChunkSearchUtil.joinChunkContent(
                "abcdefghijklmnopqrst", "fghijklmnopqrstuvwxy", "\n\n"))
                .isEqualTo("abcdefghijklmnopqrstuvwxy");
        // 重叠不足 12 rune（如 10）→ 不算重叠，按 separator 相连
        assertThat(ChunkSearchUtil.joinChunkContent(
                "0123456789abcdefghij", "abcdefghij0123456789", "\n\n"))
                .isEqualTo("0123456789abcdefghij\n\nabcdefghij0123456789");
    }

    @Test
    void goTrimSpaceMatchesUnicodeIsSpaceSet() {
        // Java strip() 缺 U+00A0/U+0085；Go TrimSpace 是 unicode.IsSpace 全集
        assertThat(ChunkSearchUtil.goTrimSpace("\u00A0\u3000 x \u2028")).isEqualTo("x");
        assertThat(ChunkSearchUtil.goTrimSpace("  ")).isEmpty();
        assertThat(ChunkSearchUtil.goTrimSpace(null)).isEmpty();
    }
}
