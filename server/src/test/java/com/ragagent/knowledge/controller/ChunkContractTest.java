package com.ragagent.knowledge.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ragagent.TestSchema;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.TenantMember;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.domain.UserPreferences;
import com.ragagent.auth.mapper.TenantMapper;
import com.ragagent.auth.mapper.TenantMemberMapper;
import com.ragagent.auth.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * chunk 模块契约测试（10 条路由）。golden：record-chunk-golden.sh。
 *
 * <p>种子数据与录制脚本（scripts/record-chunk-golden.sh）一致：KB 关掉
 * vector/keyword 索引（syncChunkIndex 早退 → index_status=ready 全确定性）、
 * C1..C5 的 seq_id 与 golden 逐字一致（数字不在掩码范围）、contributor/viewer
 * 用户用于 ownership 守卫的 403 纯字符串。</p>
 *
 * <p>录制顺序影响状态（C1 的 revision 会推进）：涉及状态的用例在单个 @Test 内
 * 按**录制顺序**串完同一段线性路径（@BeforeEach 重播种，跨用例互不影响）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChunkContractTest {

    private static final String BCRYPT =
            "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK";
    private static final long TENANT = 10002L;
    private static final String OWNER = "11111111-2222-3333-4444-555555555501";
    private static final String VIEWER = "11111111-2222-3333-4444-555555555504";
    private static final String CONTRIBUTOR = "11111111-2222-3333-4444-555555555505";
    private static final String OWNER_EMAIL = "chunk-contract-owner@weknora.test";
    private static final String VIEWER_EMAIL = "chunk-contract-viewer@weknora.test";
    private static final String CONTRA_EMAIL = "chunk-contract-contrib@weknora.test";
    private static final String UNKNOWN = "11111111-2222-3333-4444-999999999999";

    private static final String KB1 = "de000001-0000-0000-0000-000000000001";
    private static final String CROSS_KB = "de000001-0000-0000-0000-000000000009";
    private static final String KG1 = "aaa00001-0000-0000-0000-000000000001";
    private static final String KG2 = "aaa00001-0000-0000-0000-000000000002";
    private static final String KG3 = "aaa00001-0000-0000-0000-000000000003";
    private static final String CROSS_KG = "aaa00001-0000-0000-0000-000000000009";
    private static final String C1 = "bbb00001-0000-0000-0000-000000000001";
    private static final String C2 = "bbb00001-0000-0000-0000-000000000002";
    private static final String C3 = "bbb00001-0000-0000-0000-000000000003";
    private static final String C4 = "bbb00001-0000-0000-0000-000000000004";
    private static final String C5 = "bbb00001-0000-0000-0000-000000000005";
    private static final String C6 = "bbb00001-0000-0000-0000-000000000006";
    private static final String Q1 = "ccc00001-0000-0000-0000-000000000001";

    private static final String C5_CONTENT =
            "带图 ![img](resource://a.png) 与 HTML <img src=\"resource://b.png\"> 的段落";
    private static final String C4_METADATA =
            "{\"generatedQuestions\":[{\"id\":\"" + Q1 + "\",\"question\":\"已有问题?\","
                    + "\"contentRevision\":0}],\"generatedQuestionsRevision\":0}";
    private static final String C3_IMAGE_INFO =
            "[{\"url\":\"resource://img-1\",\"original_url\":\"\",\"start_pos\":0,\"end_pos\":0,"
                    + "\"caption\":\"图一\",\"ocr_text\":\"OCR文字\"}]";

    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern UUID_VALUE = Pattern.compile(
            "\"([A-Za-z_]+)\":\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"");
    private static final Pattern TS_VALUE = Pattern.compile(
            "\"([A-Za-z_]+)\":\"[2-9]\\d{3}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})\"");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private TenantMemberMapper memberMapper;

    private String owner;
    private String viewer;
    private String contrib;

    @BeforeEach
    void seed() throws Exception {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);

        Tenant tenant = new Tenant();
        tenant.setId(TENANT);
        tenant.setName("chunk-contract-tenant");
        tenant.setStatus("active");
        tenantMapper.insert(tenant);
        Tenant crossTenant = new Tenant();
        crossTenant.setId(10000L);
        crossTenant.setName("billy-workspace");
        crossTenant.setStatus("active");
        tenantMapper.insert(crossTenant);

        seedUser(OWNER, "chunkowner", OWNER_EMAIL, "owner");
        seedUser(VIEWER, "chunkviewer", VIEWER_EMAIL, "viewer");
        seedUser(CONTRIBUTOR, "chunkcontrib", CONTRA_EMAIL, "contributor");

        jdbc.update("INSERT INTO knowledge_bases (id, name, tenant_id, type, description, "
                + "creator_id, summary_model_id, embedding_model_id, indexing_strategy) "
                + "VALUES (?, 'chunk-golden-kb', ?, 'document', 'chunk golden 专用', ?, '', '', ?)",
                KB1, TENANT, OWNER,
                "{\"vectorEnabled\":false,\"keywordEnabled\":false,"
                        + "\"wikiEnabled\":false,\"graphEnabled\":false}");
        jdbc.update("INSERT INTO knowledge_bases (id, name, tenant_id, type, creator_id) "
                + "VALUES (?, 'cross-kb', 10000, 'document', ?)", CROSS_KB, OWNER);

        seedKnowledge(KG1, "文档一");
        seedKnowledge(KG2, "文档二");
        seedKnowledge(KG3, "空文档");
        jdbc.update("INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, "
                + "source, parse_status, summary_status) VALUES (?, 10000, ?, 'document', "
                + "'别人的文档', 'manual', 'completed', 'none')", CROSS_KG, CROSS_KB);

        seedChunk(C1, KG1, "第一段内容", 0, 0, 6, 100000968, "text", null, null, null);
        seedChunk(C2, KG1, "第二段内容", 1, 6, 12, 100000969, "text", null, null, null);
        seedChunk(C3, KG1, "图一", 2, 12, 14, 100000970, "image_ocr", C1, null, C3_IMAGE_INFO);
        seedChunk(C4, KG1, "问答内容", 3, 14, 18, 100000971, "text", null, C4_METADATA, null);
        seedChunk(C5, KG1, C5_CONTENT, 4, 18, 60, 100000972, "text", null, null, null);
        seedChunk(C6, KG2, "第二篇唯一段", 0, 0, 8, 100000973, "text", null, null, null);

        owner = "Bearer " + login(OWNER_EMAIL);
        viewer = "Bearer " + login(VIEWER_EMAIL);
        contrib = "Bearer " + login(CONTRA_EMAIL);
    }

    private void seedUser(String id, String name, String email, String role) {
        User user = new User();
        user.setId(id);
        user.setUsername(name);
        user.setEmail(email);
        user.setPasswordHash(BCRYPT);
        user.setTenantId(TENANT);
        user.setIsActive(true);
        user.setPreferences(new UserPreferences());
        userMapper.insert(user);
        TenantMember member = new TenantMember();
        member.setUserId(id);
        member.setTenantId(TENANT);
        member.setRole(role);
        member.setStatus("active");
        memberMapper.insert(member);
    }

    private void seedKnowledge(String id, String title) {
        jdbc.update("INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, "
                + "source, parse_status, summary_status) VALUES (?, ?, ?, 'document', ?, "
                + "'manual', 'completed', 'none')", id, TENANT, KB1, title);
    }

    private void seedChunk(String id, String kgId, String content, int chunkIndex,
                           int startAt, int endAt, long seqId, String type,
                           String parentChunkId, String metadata, String imageInfo) {
        jdbc.update("INSERT INTO chunks (id, seq_id, tenant_id, knowledge_id, knowledge_base_id, "
                + "content, chunk_index, start_at, end_at, chunk_type, parent_chunk_id, metadata, "
                + "is_enabled, flags, status, content_revision, index_status, last_editor_id, "
                + "source_content, image_info) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, 1, 0, 0, 'ready', '', '', ?)",
                id, seqId, TENANT, kgId, KB1, content, chunkIndex, startAt, endAt,
                type, parentChunkId, metadata, imageInfo);
    }

    // ════════════════ 1) 列表与读取 ════════════════

    @Test
    void listMatchesGo() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/chunks/" + KG1)
                .header("Authorization", owner)).andReturn();
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("chunk-list.json")), mask(raw(r)));
    }

    @Test
    void listTypeFilterMatchesGo() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/chunks/" + KG1)
                .queryParam("chunkType", "image_ocr")
                .header("Authorization", owner)).andReturn();
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("chunk-list-type-filter.json")), mask(raw(r)));
    }

    @Test
    void listPagedMatchesGo() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/chunks/" + KG1)
                .queryParam("page", "1").queryParam("pageSize", "2")
                .header("Authorization", owner)).andReturn();
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("chunk-list-paged.json")), mask(raw(r)));
    }

    /** page=0 视为未传（200 且归一化成 1）。 */
    @Test
    void listPageZeroMatchesGo() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/chunks/" + KG1)
                .queryParam("page", "0").queryParam("pageSize", "2")
                .header("Authorization", owner)).andReturn();
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("chunk-list-page0.json")), mask(raw(r)));
    }

    @Test
    void listBadPageMatchesGo() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/chunks/" + KG1)
                .queryParam("page", "abc").header("Authorization", owner)).andReturn();
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("chunk-list-badpage.json"), raw(r));
    }

    @Test
    void listBadSizeMatchesGo() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/chunks/" + KG1)
                .queryParam("pageSize", "-5").header("Authorization", owner)).andReturn();
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("chunk-list-badsize.json"), raw(r));
    }

    /** 空仓库 → "data":[]（空结果也输出数组，不落 null）。 */
    @Test
    void listEmptyMatchesGo() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/chunks/" + KG3)
                .header("Authorization", owner)).andReturn();
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("chunk-list-empty.json")), mask(raw(r)));
    }

    @Test
    void listUnknownKnowledgeIsNotFound() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/chunks/" + UNKNOWN)
                .header("Authorization", owner)).andReturn();
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("chunk-list-404.json"), raw(r));
    }

    @Test
    void listCrossTenantKnowledgeIsForbidden() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/chunks/" + CROSS_KG)
                .header("Authorization", owner)).andReturn();
        assertEquals(403, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("chunk-list-cross-tenant.json"), raw(r));
    }

    @Test
    void byIdMatchesGo() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/chunks/by-id/" + C1)
                .header("Authorization", owner)).andReturn();
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("chunk-by-id.json")), mask(raw(r)));
    }

    @Test
    void byIdUnknownIsNotFound() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/chunks/by-id/" + UNKNOWN)
                .header("Authorization", owner)).andReturn();
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("chunk-by-id-404.json"), raw(r));
    }

    // ════════════════ 2) 更新（顺序敏感） ════════════════

    @Test
    void updateThenConflictMatchesGo() throws Exception {
        MvcResult r = mockMvc.perform(jsonBody(
                put("/api/v1/chunks/" + KG1 + "/" + C1), owner,
                "{\"content\":\"第一段内容（已编辑）\",\"expectedRevision\":0}")).andReturn();
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("chunk-update.json")), mask(raw(r)));

        MvcResult c = mockMvc.perform(jsonBody(
                put("/api/v1/chunks/" + KG1 + "/" + C1), owner,
                "{\"content\":\"再改一次\",\"expectedRevision\":0}")).andReturn();
        assertEquals(409, c.getResponse().getStatus(), raw(c));
        assertEquals(golden("chunk-update-conflict.json"), raw(c));
    }

    /** 空内容 → 500 信封 code=1007 且 message=原文（不是 400）。 */
    @Test
    void updateEmptyContentIs500() throws Exception {
        MvcResult r = mockMvc.perform(jsonBody(
                put("/api/v1/chunks/" + KG1 + "/" + C2), owner, "{\"content\":\"   \"}"))
                .andReturn();
        assertEquals(500, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("chunk-update-empty.json"), raw(r));
    }

    @Test
    void updateImageChunkIs500() throws Exception {
        MvcResult r = mockMvc.perform(jsonBody(
                put("/api/v1/chunks/" + KG1 + "/" + C3), owner, "{\"content\":\"不能改图\"}"))
                .andReturn();
        assertEquals(500, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("chunk-update-image-chunk.json"), raw(r));
    }

    @Test
    void updateAddingImageIs500() throws Exception {
        MvcResult r = mockMvc.perform(jsonBody(
                put("/api/v1/chunks/" + KG1 + "/" + C5), owner,
                "{\"content\":\"带图 ![img](resource://a.png) 加新图 ![n](resource://new.png)\"}"))
                .andReturn();
        assertEquals(500, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("chunk-update-add-image.json"), raw(r));
    }

    @Test
    void updateUnknownChunkIsNotFound() throws Exception {
        MvcResult r = mockMvc.perform(jsonBody(
                put("/api/v1/chunks/" + KG1 + "/" + UNKNOWN), owner, "{\"content\":\"x\"}"))
                .andReturn();
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("chunk-update-404.json"), raw(r));
    }

    /** chunk 存在但与 URL knowledge_id 不符 → 403 信封 "No permission to access this chunk"。 */
    @Test
    void updateKnowledgeMismatchIsForbidden() throws Exception {
        MvcResult r = mockMvc.perform(jsonBody(
                put("/api/v1/chunks/" + KG2 + "/" + C1), owner, "{\"content\":\"x\"}"))
                .andReturn();
        assertEquals(403, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("chunk-update-mismatch.json"), raw(r));
    }

    /** null 字面量体 = 零值绑定（全指针字段全空 = 无变更，200 返回当前 chunk）。 */
    @Test
    void updateLiteralNullBodyIsZeroValueBinding() throws Exception {
        MvcResult r = mockMvc.perform(jsonBody(
                put("/api/v1/chunks/" + KG1 + "/" + C2), owner, "null")).andReturn();
        assertEquals(200, r.getResponse().getStatus(), raw(r));
    }

    @Test
    void updateNoBodyAndBadJsonMatchGo() throws Exception {
        MvcResult r = mockMvc.perform(jsonBody(
                put("/api/v1/chunks/" + KG1 + "/" + C2), owner, null)).andReturn();
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("chunk-update-no-body.json"), raw(r));

        MvcResult b = mockMvc.perform(jsonBody(
                put("/api/v1/chunks/" + KG1 + "/" + C2), owner, "not-json")).andReturn();
        assertEquals(400, b.getResponse().getStatus(), raw(b));
        assertEquals(golden("chunk-update-bad-json.json"), raw(b));
    }

    @Test
    void updateDisableOnlyMatchesGo() throws Exception {
        MvcResult r = mockMvc.perform(jsonBody(
                put("/api/v1/chunks/" + KG1 + "/" + C2), owner, "{\"enabled\":false}"))
                .andReturn();
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("chunk-update-disable.json")), mask(raw(r)));
    }

    // ════════════════ 3) 修订历史与回滚 ════════════════

    @Test
    void revisionsAfterUpdateMatchGo() throws Exception {
        mockMvc.perform(jsonBody(put("/api/v1/chunks/" + KG1 + "/" + C1), owner,
                "{\"content\":\"第一段内容（已编辑）\",\"expectedRevision\":0}")).andReturn();
        MvcResult r = mockMvc.perform(get("/api/v1/chunks/" + KG1 + "/" + C1 + "/revisions")
                .header("Authorization", owner)).andReturn();
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("chunk-revisions.json")), mask(raw(r)));
    }

    @Test
    void revertValidationMatchesGo() throws Exception {
        MvcResult m = mockMvc.perform(jsonBody(
                post("/api/v1/chunks/" + KG1 + "/" + C1 + "/revert"), owner, "{}")).andReturn();
        assertEquals(400, m.getResponse().getStatus(), raw(m));
        assertEquals(golden("chunk-revert-missing.json"), raw(m));

        MvcResult n = mockMvc.perform(jsonBody(
                post("/api/v1/chunks/" + KG1 + "/" + C1 + "/revert"), owner,
                "{\"revision\":-1}")).andReturn();
        assertEquals(400, n.getResponse().getStatus(), raw(n));
        assertEquals(golden("chunk-revert-negative.json"), raw(n));
    }

    /** 未知 revision → 400 且 message 为上游原文 "record not found"（不是 404）。 */
    @Test
    void revertUnknownRevisionMatchesGo() throws Exception {
        MvcResult r = mockMvc.perform(jsonBody(
                post("/api/v1/chunks/" + KG1 + "/" + C1 + "/revert"), owner,
                "{\"revision\":99}")).andReturn();
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("chunk-revert-unknown.json"), raw(r));
    }

    @Test
    void revertAfterEditMatchesGo() throws Exception {
        mockMvc.perform(jsonBody(put("/api/v1/chunks/" + KG1 + "/" + C1), owner,
                "{\"content\":\"第一段内容（已编辑）\",\"expectedRevision\":0}")).andReturn();
        MvcResult r = mockMvc.perform(jsonBody(
                post("/api/v1/chunks/" + KG1 + "/" + C1 + "/revert"), owner,
                "{\"revision\":0}")).andReturn();
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("chunk-revert.json")), mask(raw(r)));
    }

    // ════════════════ 4) 生成问题（by-id） ════════════════

    @Test
    void questionCreateMatchesGo() throws Exception {
        MvcResult r = mockMvc.perform(jsonBody(
                put("/api/v1/chunks/by-id/" + C4 + "/questions"), owner,
                "{\"question\":\"新问题?\"}")).andReturn();
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("chunk-q-create.json")), mask(raw(r)));
    }

    @Test
    void questionUpdateAndMissingMatchGo() throws Exception {
        MvcResult r = mockMvc.perform(jsonBody(
                put("/api/v1/chunks/by-id/" + C4 + "/questions"), owner,
                "{\"questionId\":\"" + Q1 + "\",\"question\":\"已有问题（改）?\"}")).andReturn();
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("chunk-q-update.json")), mask(raw(r)));

        MvcResult m = mockMvc.perform(jsonBody(
                put("/api/v1/chunks/by-id/" + C4 + "/questions"), owner,
                "{\"questionId\":\"nope\",\"question\":\"x\"}")).andReturn();
        assertEquals(400, m.getResponse().getStatus(), raw(m));
        assertEquals(golden("chunk-q-update-missing.json"), raw(m));
    }

    /** 纯空白问题过 binding（required=非零值），由 service 落 "question cannot be empty"。 */
    @Test
    void questionBlankMatchesGo() throws Exception {
        MvcResult r = mockMvc.perform(jsonBody(
                put("/api/v1/chunks/by-id/" + C4 + "/questions"), owner,
                "{\"questionId\":\"" + Q1 + "\",\"question\":\"   \"}")).andReturn();
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("chunk-q-empty.json"), raw(r));
    }

    @Test
    void questionUnknownChunkIsNotFound() throws Exception {
        MvcResult r = mockMvc.perform(jsonBody(
                put("/api/v1/chunks/by-id/" + UNKNOWN + "/questions"), owner,
                "{\"question\":\"x\"}")).andReturn();
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("chunk-q-404.json"), raw(r));
    }

    /**
     * dev KB 无 embedding 模型：三次删除都在 GetEmbeddingModel 一步落同一个 400
     * （metadata 从未被修改）——golden 三份逐字一致。
     */
    @Test
    void questionDeleteWithoutModelMatchesGo() throws Exception {
        String path = "/api/v1/chunks/by-id/" + C4 + "/questions";
        // 录制序此前已创建过一个问题（chunk-q-create）；先同样创建一个并取回其 id
        MvcResult created = mockMvc.perform(jsonBody(put(path), owner,
                "{\"question\":\"新问题?\"}")).andReturn();
        Matcher cm = Pattern.compile(
                "\"id\":\"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\"")
                .matcher(raw(created));
        assertThat(cm.find()).as("创建响应应含问题 id: " + raw(created)).isTrue();

        MvcResult a = mockMvc.perform(jsonBody(delete(path), owner,
                "{\"questionId\":\"" + Q1 + "\"}")).andReturn();
        assertEquals(400, a.getResponse().getStatus(), raw(a));
        assertEquals(golden("chunk-q-delete.json"), raw(a));

        MvcResult b = mockMvc.perform(jsonBody(delete(path), owner,
                "{\"questionId\":\"" + cm.group(1) + "\"}")).andReturn();
        assertEquals(400, b.getResponse().getStatus(), raw(b));
        assertEquals(golden("chunk-q-delete-created.json"), raw(b));

        MvcResult c = mockMvc.perform(jsonBody(delete(path), owner,
                "{\"questionId\":\"" + Q1 + "\"}")).andReturn();
        assertEquals(400, c.getResponse().getStatus(), raw(c));
        assertEquals(golden("chunk-q-delete-none.json"), raw(c));
    }

    @Test
    void questionDeleteNoBodyMatchesGo() throws Exception {
        MvcResult r = mockMvc.perform(jsonBody(
                delete("/api/v1/chunks/by-id/" + C4 + "/questions"), owner, null)).andReturn();
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("chunk-q-delete-no-body.json"), raw(r));
    }

    @Test
    void regenerateWithoutSummaryModelMatchesGo() throws Exception {
        MvcResult r = mockMvc.perform(
                post("/api/v1/chunks/by-id/" + C1 + "/questions/regenerate")
                        .header("Authorization", owner)).andReturn();
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("chunk-q-regen-no-model.json"), raw(r));
    }

    @Test
    void regenerateImageChunkMatchesGo() throws Exception {
        MvcResult r = mockMvc.perform(
                post("/api/v1/chunks/by-id/" + C3 + "/questions/regenerate")
                        .header("Authorization", owner)).andReturn();
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("chunk-q-regen-image.json"), raw(r));
    }

    @Test
    void regenerateUnknownChunkIsNotFound() throws Exception {
        MvcResult r = mockMvc.perform(
                post("/api/v1/chunks/by-id/" + UNKNOWN + "/questions/regenerate")
                        .header("Authorization", owner)).andReturn();
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("chunk-q-regen-404.json"), raw(r));
    }

    // ════════════════ 5) 权限矩阵 ════════════════

    /** 非创建者 contributor 写 → 403 纯字符串（ownership 守卫，先于 handler）。 */
    @Test
    void contributorWriteIsPureStringForbidden() throws Exception {
        MvcResult p = mockMvc.perform(jsonBody(
                put("/api/v1/chunks/" + KG1 + "/" + C2), contrib, "{\"content\":\"越权改\"}"))
                .andReturn();
        assertEquals(403, p.getResponse().getStatus(), raw(p));
        assertEquals(golden("chunk-own-contrib-put.json"), raw(p));

        MvcResult d = mockMvc.perform(delete("/api/v1/chunks/" + KG1 + "/" + C2)
                .header("Authorization", contrib)).andReturn();
        assertEquals(403, d.getResponse().getStatus(), raw(d));
        assertEquals(golden("chunk-own-contrib-delete.json"), raw(d));
    }

    /**
     * viewer 同样被 ownership 守卫拒绝；读则放行（Viewer+）。
     * chunk-viewer-read.json 录制于全部变更之后——先回放各变更（失败调用不落库，略）。
     */
    @Test
    void viewerWriteForbiddenButReadAllowed() throws Exception {
        mockMvc.perform(jsonBody(put("/api/v1/chunks/" + KG1 + "/" + C1), owner,
                "{\"content\":\"第一段内容（已编辑）\",\"expectedRevision\":0}")).andReturn();
        mockMvc.perform(jsonBody(put("/api/v1/chunks/" + KG1 + "/" + C2), owner,
                "{\"enabled\":false}")).andReturn();
        mockMvc.perform(jsonBody(post("/api/v1/chunks/" + KG1 + "/" + C1 + "/revert"), owner,
                "{\"revision\":0}")).andReturn();
        mockMvc.perform(jsonBody(put("/api/v1/chunks/by-id/" + C4 + "/questions"), owner,
                "{\"question\":\"新问题?\"}")).andReturn();
        mockMvc.perform(jsonBody(put("/api/v1/chunks/by-id/" + C4 + "/questions"), owner,
                "{\"questionId\":\"" + Q1 + "\",\"question\":\"已有问题（改）?\"}")).andReturn();

        MvcResult d = mockMvc.perform(delete("/api/v1/chunks/" + KG1 + "/" + C2)
                .header("Authorization", viewer)).andReturn();
        assertEquals(403, d.getResponse().getStatus(), raw(d));
        assertEquals(golden("chunk-own-viewer-delete.json"), raw(d));

        MvcResult r = mockMvc.perform(get("/api/v1/chunks/" + KG1)
                .header("Authorization", viewer)).andReturn();
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("chunk-viewer-read.json")), mask(raw(r)));
    }

    // ════════════════ 6) 删除（消费状态） ════════════════

    @Test
    void deleteFlowMatchesGo() throws Exception {
        MvcResult a = mockMvc.perform(delete("/api/v1/chunks/" + KG2 + "/" + C6)
                .header("Authorization", owner)).andReturn();
        assertEquals(204, a.getResponse().getStatus(), raw(a));
        assertEquals(golden("chunk-delete.json"), raw(a));

        MvcResult b = mockMvc.perform(delete("/api/v1/chunks/" + KG2 + "/" + C6)
                .header("Authorization", owner)).andReturn();
        assertEquals(404, b.getResponse().getStatus(), raw(b));
        assertEquals(golden("chunk-delete-404.json"), raw(b));

        MvcResult c = mockMvc.perform(delete("/api/v1/chunks/" + KG2)
                .header("Authorization", owner)).andReturn();
        assertEquals(204, c.getResponse().getStatus(), raw(c));
        assertEquals(golden("chunk-delete-all.json"), raw(c));

        // 知识仍在 → 幂等成功（loadKnowledgeWriteBatch 只校验 knowledge）。
        MvcResult d = mockMvc.perform(delete("/api/v1/chunks/" + KG2)
                .header("Authorization", owner)).andReturn();
        assertEquals(204, d.getResponse().getStatus(), raw(d));
        assertEquals(golden("chunk-delete-all-again.json"), raw(d));
    }

    // ════════════════ 辅助 ════════════════

    private String login(String email) throws Exception {
        MvcResult r = mockMvc.perform(jsonBody(post("/api/v1/auth/login"), null,
                "{\"email\":\"" + email + "\",\"password\":\"Passw0rd!\"}")).andReturn();
        Matcher m = TOKEN.matcher(raw(r));
        assertThat(m.find()).as("login 响应应含 token: " + raw(r)).isTrue();
        return m.group(1);
    }

    private static MockHttpServletRequestBuilder jsonBody(MockHttpServletRequestBuilder builder,
                                                          String bearer, String body) {
        if (bearer != null) {
            builder.header("Authorization", bearer);
        }
        builder.contentType("application/json");
        if (body != null) {
            builder.content(body);
        }
        return builder;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper RAW_SEMANTIC_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static String raw(MvcResult r) throws Exception {
        // PR4 语义比较：与 golden 同侧归一（非 JSON 文本原样）
        return com.ragagent.support.ContractJson.semantic(RAW_SEMANTIC_MAPPER,
                r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper GOLDEN_SEMANTIC_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static String golden(String name) throws Exception {
        // PR4 语义比较：键序/HTML 转义归一后返回（非 JSON 文本原样），断言侧不变
        var resource = new org.springframework.core.io.ClassPathResource("contracts/" + name);
        if (!resource.exists()) {
            resource = new org.springframework.core.io.ClassPathResource("contracts/" + name + ".json");
        }
        String text = new String(resource.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        return com.ragagent.support.ContractJson.semantic(GOLDEN_SEMANTIC_MAPPER, text);
    }

    /** 两侧同掩码：UUID 值 + 真实时间戳。 */
    private static String mask(String s) {
        String out = UUID_VALUE.matcher(s).replaceAll("\"$1\":\"<uuid>\"");
        return TS_VALUE.matcher(out).replaceAll("\"$1\":\"<ts>\"");
    }
}
