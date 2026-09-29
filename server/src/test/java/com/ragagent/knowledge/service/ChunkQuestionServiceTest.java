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
import com.ragagent.knowledge.domain.ChunkRevisionConflictException;

/**
 * chunk 生成问题链路语义（H2）：Upsert/Delete/Regenerate 的 400 原文与 metadata
 * 落库、邻块上下文拼装、生成问题行解析纯逻辑。
 * {@code @AutoConfigureMockMvc} 仅为共享 Spring 上下文缓存键（见 ChunkRepositoryTest 类注释）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChunkQuestionServiceTest {

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
    private ChunkQuestionService service;
    @Autowired
    private ChunkEditService chunkEdit;
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
        assertThat(meta.path("generatedQuestions")).hasSize(1);
        assertThat(meta.path("generatedQuestions").get(0).path("id").asText())
                .isEqualTo(created.getId());
        // generated_questions_revision=0 被 omitempty 省略（Go 同款）
        assertThat(meta.has("generatedQuestionsRevision")).isFalse();

        // 更新（同 ID 覆盖问题文本）
        GeneratedQuestion updated = service.upsertGeneratedQuestion(c.getId(), created.getId(), "What is Y?");
        assertThat(updated.getId()).isEqualTo(created.getId());
        assertThat(updated.getQuestion()).isEqualTo("What is Y?");
        assertThat(json(metadataJson(c.getId())).path("generatedQuestions")).hasSize(1);

        // revision 提升后，再次更新把 content_revision 钉到新版本
        chunkEdit.updateDocumentChunk(c.getId(), "edited body", null, null);
        GeneratedQuestion repinned = service.upsertGeneratedQuestion(c.getId(), created.getId(), "What is Z?");
        assertThat(repinned.getContentRevision()).isEqualTo(1);
        assertThat(json(metadataJson(c.getId())).path("generatedQuestions").get(0)
                .path("contentRevision").asInt()).isEqualTo(1);
    }

    @Test
    void upsertGeneratedQuestionToleratesUnknownMetadataKeys() {
        kb(KB, false);
        knowledge(DOC, KB);
        Chunk c = chunk(DOC, "body");
        jdbc.update("UPDATE chunks SET metadata = ? WHERE id = ?",
                json("{\"generatedQuestions\":[{\"id\":\"q1\",\"question\":\"A\"}],\"future_key\":123}")
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
                json("{\"generatedQuestions\":[{\"id\":\"q1\",\"question\":\"A\"},{\"id\":\"q2\",\"question\":\"B\"}]}")
                        .toString(),
                c.getId());

        service.deleteGeneratedQuestion(c.getId(), "q1");

        JsonNode meta = json(metadataJson(c.getId()));
        assertThat(meta.path("generatedQuestions")).hasSize(1);
        assertThat(meta.path("generatedQuestions").get(0).path("id").asText()).isEqualTo("q2");

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
                json("{\"generatedQuestions\":[{\"id\":\"q1\",\"question\":\"A\"}]}").toString(), c.getId());
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
        assertThat(ChunkQuestionService.parseGeneratedQuestions(output, 3))
                .containsExactly("什么是知识库？", "如何配置嵌入模型", "支持哪些文件格式？");
        // count=10：短行仍被丢弃（字节数 <=5）
        assertThat(ChunkQuestionService.parseGeneratedQuestions(output, 10))
                .containsExactly("什么是知识库？", "如何配置嵌入模型", "支持哪些文件格式？",
                        "这是第五个问题吗？", "超出数量上限的问题");
        // 非正 count / 空输出 → 空列表（对照 Go content=="" || count<=0 → nil）
        assertThat(ChunkQuestionService.parseGeneratedQuestions(output, 0)).isEmpty();
        assertThat(ChunkQuestionService.parseGeneratedQuestions(null, 3)).isEmpty();
    }
}
