package com.ragagent.knowledge.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import com.ragagent.knowledge.storage.LocalStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * knowledge 文档操作面契约测试（波 2 第二批，15+1 条路由）。golden：
 * scripts/record-knowledge-golden.sh。
 *
 * <p>种子严格复刻录制脚本：KB1/KB2/KB3（creator=owner、关索引与 summary model）、
 * KG1..KG16（固定纯十六进制 id）、T1/T2 标签 + KG1↔T1 关系、KG11 的图片块 C1..C3、
 * KG1 的真实文件（local://kgdocs/kg-doc.txt，测试写入 LocalStorageService 的落盘根）。</p>
 *
 * <p>录制顺序影响状态（manual 更新推进 metadata.version、folder 移动/重命名改变
 * folders 计数、batch-delete 消费 KG3）：每个 @Test 从同一播种出发，按**录制顺序**
 * 串完自己段落的前置变更再断言。Go 侧异步任务（asynq 批量删除/重解析）在 Java 为
 * 同步尽力而为——HTTP 契约逐字节一致，后续计数因此两侧收敛。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class KnowledgeOperationsContractTest {

    private static final String BCRYPT =
            "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK";
    private static final long TENANT = 10002L;
    private static final String OWNER = "11111111-2222-3333-4444-555555555501";
    private static final String VIEWER = "11111111-2222-3333-4444-555555555504";
    private static final String CONTRIBUTOR = "11111111-2222-3333-4444-555555555505";
    private static final String OWNER_EMAIL = "kg-contract-owner@weknora.test";
    private static final String VIEWER_EMAIL = "kg-contract-viewer@weknora.test";
    private static final String CONTRA_EMAIL = "kg-contract-contrib@weknora.test";
    private static final String UNKNOWN = "11111111-2222-3333-4444-999999999999";

    private static final String KB1 = "eda00001-0000-0000-0000-000000000001";
    private static final String KB2 = "eda00001-0000-0000-0000-000000000002";
    private static final String KB3 = "eda00001-0000-0000-0000-000000000003";
    private static final String CROSS_KB = "eda00001-0000-0000-0000-000000000009";
    private static final String KG1 = "afa00001-0000-0000-0000-000000000001";
    private static final String KG2 = "afa00001-0000-0000-0000-000000000002";
    private static final String KG3 = "afa00001-0000-0000-0000-000000000003";
    private static final String KG4 = "afa00001-0000-0000-0000-000000000004";
    private static final String KG5 = "afa00001-0000-0000-0000-000000000005";
    private static final String KG6 = "afa00001-0000-0000-0000-000000000006";
    private static final String KG7 = "afa00001-0000-0000-0000-000000000007";
    private static final String KG8 = "afa00001-0000-0000-0000-000000000008";
    private static final String KG9 = "afa00001-0000-0000-0000-000000000009";
    private static final String KG10 = "afa00001-0000-0000-0000-000000000010";
    private static final String KG11 = "afa00001-0000-0000-0000-000000000011";
    private static final String KG12 = "afa00001-0000-0000-0000-000000000012";
    private static final String KG13 = "afa00001-0000-0000-0000-000000000013";
    private static final String KG14 = "afa00001-0000-0000-0000-000000000014";
    private static final String KG15 = "afa00001-0000-0000-0000-000000000015";
    private static final String KG16 = "afa00001-0000-0000-0000-000000000016";
    private static final String CROSS_KG = "afa00001-0000-0000-0000-0000000000f9";
    private static final String T1 = "bfb00001-0000-0000-0000-000000000001";
    private static final String T2 = "bfb00001-0000-0000-0000-000000000002";
    private static final String C1 = "bcc00001-0000-0000-0000-000000000001";
    private static final String C2 = "bcc00001-0000-0000-0000-000000000002";
    private static final String C3 = "bcc00001-0000-0000-0000-000000000003";

    private static final String DOC_BYTES = "kg golden download bytes\nline two\n";
    private static final String KG4_METADATA =
            "{\"content\":\"# 手工知识\\n\\n初始内容\",\"format\":\"markdown\","
                    + "\"status\":\"publish\",\"version\":1,\"updatedAt\":\"2026-09-01T00:00:00Z\"}";
    private static final String C2_IMAGE_INFO =
            "[{\"url\":\"resource://img-1\",\"original_url\":\"\",\"start_pos\":0,\"end_pos\":0,"
                    + "\"caption\":\"图一\",\"ocr_text\":\"\"}]";
    private static final String C3_IMAGE_INFO =
            "[{\"url\":\"resource://img-2\",\"original_url\":\"\",\"start_pos\":0,\"end_pos\":0,"
                    + "\"caption\":\"别图\",\"ocr_text\":\"别图OCR\"}]";
    private static final String IMG_BODY = "{\"imageInfo\":\"[{\\\"url\\\":\\\"resource://img-1\\\","
            + "\\\"original_url\\\":\\\"\\\",\\\"start_pos\\\":0,\\\"end_pos\\\":0,"
            + "\\\"caption\\\":\\\"新图说\\\",\\\"ocr_text\\\":\\\"新OCR\\\"}]\"}";

    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern UUID_VALUE = Pattern.compile(
            "\"([A-Za-z_]+)\":\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"");
    /** 错误 message 文案里内嵌的 uuid（如 "Knowledge X does not belong to knowledge base Y"） */
    private static final Pattern UUID_BARE = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
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
    @Autowired
    private LocalStorageService storage;

    private String owner;
    private String viewer;
    private String contrib;

    @BeforeEach
    void seed() throws Exception {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);

        Tenant tenant = new Tenant();
        tenant.setId(TENANT);
        tenant.setName("kg-contract-tenant");
        tenant.setStatus("active");
        tenantMapper.insert(tenant);
        Tenant crossTenant = new Tenant();
        crossTenant.setId(10000L);
        crossTenant.setName("billy-workspace");
        crossTenant.setStatus("active");
        tenantMapper.insert(crossTenant);

        seedUser(OWNER, "kgowner", OWNER_EMAIL, "owner");
        seedUser(VIEWER, "kgviewer", VIEWER_EMAIL, "viewer");
        seedUser(CONTRIBUTOR, "kgcontrib", CONTRA_EMAIL, "contributor");

        String strategy = "{\"vectorEnabled\":false,\"keywordEnabled\":false,"
                + "\"wikiEnabled\":false,\"graphEnabled\":false}";
        seedKb(KB1, "kg-golden-kb", strategy);
        seedKb(KB2, "kg-second-kb", strategy);
        seedKb(KB3, "kg-clear-kb", strategy);
        jdbc.update("INSERT INTO knowledge_bases (id, name, tenant_id, type, creator_id) "
                + "VALUES (?, 'cross-kb', 10000, 'document', ?)", CROSS_KB, OWNER);

        seedTags();
        seedKnowledges();
        seedChunks();

        Files.createDirectories(storage.baseDir().resolve("kgdocs"));
        Files.write(storage.baseDir().resolve("kgdocs/kg-doc.txt"),
                DOC_BYTES.getBytes(StandardCharsets.UTF_8));

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

    private void seedKb(String id, String name, String strategy) {
        jdbc.update("INSERT INTO knowledge_bases (id, name, tenant_id, type, description, "
                + "creator_id, summary_model_id, embedding_model_id, indexing_strategy) "
                + "VALUES (?, ?, ?, 'document', 'kg golden 专用', ?, '', '', ?)",
                id, name, TENANT, OWNER, strategy);
    }

    private void seedTags() {
        jdbc.update("INSERT INTO knowledge_tags (id, seq_id, tenant_id, knowledge_base_id, "
                + "name, color, sort_order, created_at, updated_at) VALUES "
                + "(?, 960001, ?, ?, '重要', '', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                T1, TENANT, KB1);
        jdbc.update("INSERT INTO knowledge_tags (id, seq_id, tenant_id, knowledge_base_id, "
                + "name, color, sort_order, created_at, updated_at) VALUES "
                + "(?, 960002, ?, ?, '第二库', '', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                T2, TENANT, KB2);
        jdbc.update("INSERT INTO knowledge_tag_relations (knowledge_id, tag_id) VALUES (?, ?)",
                KG1, T1);
    }

    private void seedKnowledges() {
        jdbc.update("INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, "
                + "source, parse_status, summary_status, enable_status, file_name, file_type, "
                + "file_size, file_hash, file_path, custom_metadata) VALUES "
                + "(?, ?, ?, 'document', '文档一', 'manual', 'completed', 'none', 'enabled', "
                + "'kg-doc.txt', 'txt', 38, '0000000000000000000000000000000a', 'local://kgdocs/kg-doc.txt', '{}')",
                KG1, TENANT, KB1);
        seedSimpleDoc(KG2, KB1, "文档二", "doc2.txt", "0b");
        seedSimpleDoc(KG3, KB1, "空文档", "doc3.txt", "0c");
        jdbc.update("INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, "
                + "source, parse_status, summary_status, enable_status, file_name, file_type, "
                + "file_size, file_hash, file_path, metadata, custom_metadata) VALUES "
                + "(?, ?, ?, 'manual', '手工知识', 'manual', 'completed', 'completed', 'enabled', "
                + "'手工知识.md', 'manual', 40, '0000000000000000000000000000000d', '', ?, '{}')",
                KG4, TENANT, KB1, KG4_METADATA);
        seedSimpleDoc(KG5, KB1, "待解析", "doc5.txt", "0e");
        jdbc.update("UPDATE knowledges SET parse_status='pending', enable_status='disabled' "
                + "WHERE id=?", KG5);
        seedSimpleDoc(KG6, KB1, "失败文档", "doc6.txt", "0f");
        jdbc.update("UPDATE knowledges SET parse_status='failed', summary_status='failed', "
                + "enable_status='disabled', error_message='解析失败：文档格式不支持' WHERE id=?", KG6);
        seedSimpleDoc(KG7, KB1, "取消文档", "doc7.txt", "10");
        jdbc.update("UPDATE knowledges SET parse_status='cancelled', enable_status='disabled' "
                + "WHERE id=?", KG7);
        seedSimpleDoc(KG8, KB1, "文件夹文档一", "doc8.txt", "11");
        jdbc.update("UPDATE knowledges SET folder_path='docs' WHERE id=?", KG8);
        seedSimpleDoc(KG9, KB1, "文件夹文档二", "doc9.txt", "12");
        jdbc.update("UPDATE knowledges SET folder_path='docs/readme' WHERE id=?", KG9);
        jdbc.update("INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, "
                + "source, parse_status, summary_status, enable_status, file_name, file_type, "
                + "file_size, file_hash, file_path, custom_metadata) VALUES "
                + "(?, ?, ?, 'document', '重解析文档', 'manual', 'completed', 'none', 'enabled', "
                + "'kg-doc.txt', 'txt', 38, '00000000000000000000000000000013', 'local://kgdocs/kg-doc.txt', '{}')",
                KG10, TENANT, KB1);
        seedSimpleDoc(KG11, KB1, "图片文档", "doc11.txt", "14");
        seedSimpleDoc(KG12, KB1, "摘要文档", "doc12.txt", "15");
        jdbc.update("UPDATE knowledges SET summary_status='completed', description='已有摘要' "
                + "WHERE id=?", KG12);
        seedSimpleDoc(KG13, KB1, "删除中文档", "doc13.txt", "16");
        jdbc.update("UPDATE knowledges SET parse_status='deleting', enable_status='disabled' "
                + "WHERE id=?", KG13);
        seedSimpleDoc(KG14, KB1, "处理中文档", "doc14.txt", "17");
        jdbc.update("UPDATE knowledges SET parse_status='processing', enable_status='disabled' "
                + "WHERE id=?", KG14);
        seedSimpleDoc(KG15, KB1, "穿越文档", "evil.txt", "18");
        jdbc.update("UPDATE knowledges SET file_path='../../../etc/passwd' WHERE id=?", KG15);
        seedSimpleDoc(KG16, KB2, "第二库文档", "doc16.txt", "19");
        // 跨租户探测用（对照录制脚本从租户 10000 现取一行）
        jdbc.update("INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, "
                + "source, parse_status, summary_status) VALUES "
                + "(?, 10000, ?, 'document', '别人的文档', 'manual', 'completed', 'none')",
                CROSS_KG, CROSS_KB);
    }

    private void seedSimpleDoc(String id, String kbId, String title, String fileName, String hashSuffix) {
        jdbc.update("INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, "
                + "source, parse_status, summary_status, enable_status, file_name, file_type, "
                + "file_size, file_hash, file_path, custom_metadata) VALUES "
                + "(?, ?, ?, 'document', ?, 'manual', 'completed', 'none', 'enabled', "
                + "?, 'txt', 10, ?, '', '{}')", id, TENANT, kbId, title,
                fileName, "000000000000000000000000000000" + hashSuffix);
    }

    private void seedChunks() {
        seedChunk(C1, KG11, "图片父块", 0, 0, 5, "text", null, null);
        seedChunk(C2, KG11, "图一", 1, 5, 7, "image_caption", C1, C2_IMAGE_INFO);
        seedChunk(C3, KG11, "别图OCR", 2, 7, 9, "image_ocr", C1, C3_IMAGE_INFO);
    }

    private void seedChunk(String id, String kgId, String content, int chunkIndex,
                           int startAt, int endAt, String type, String parent, String imageInfo) {
        jdbc.update("INSERT INTO chunks (id, seq_id, tenant_id, knowledge_id, knowledge_base_id, "
                + "content, chunk_index, start_at, end_at, chunk_type, parent_chunk_id, metadata, "
                + "is_enabled, flags, status, content_revision, index_status, last_editor_id, "
                + "source_content, image_info) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, TRUE, 1, 0, 0, 'ready', '', '', ?)",
                id, 970000 + chunkIndex, TENANT, kgId, KB1, content, chunkIndex, startAt, endAt,
                type, parent, imageInfo);
    }

    // ════════════════ 1) GET /knowledge/batch ════════════════

    @Test
    void batchMatchesGo() throws Exception {
        assertGet("/api/v1/knowledge/batch?ids=" + KG1, "kg-batch.json");
        assertGetTwoIds("kg-batch-multi.json", KG1, KG2);
        assertGet("/api/v1/knowledge/batch?ids=" + KG1 + "&ids=" + UNKNOWN, "kg-batch-missing.json");
        assertGet("/api/v1/knowledge/batch", "kg-batch-no-ids.json");
        assertGet("/api/v1/knowledge/batch?ids=", "kg-batch-empty-ids.json");
        assertGet("/api/v1/knowledge/batch?ids=" + KG1 + "&kbId=" + KB1, "kg-batch-kbscope.json");
        assertGet("/api/v1/knowledge/batch?ids=" + KG1 + "&kbId=" + UNKNOWN, "kg-batch-badkb.json");
    }

    private void assertGetTwoIds(String golden, String id1, String id2) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/knowledge/batch")
                .queryParam("ids", id1).queryParam("ids", id2)
                .header("Authorization", owner)).andReturn();
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden(golden)), mask(raw(r)));
    }

    // ════════════════ 2) spans ════════════════

    @Test
    void spansMatchGo() throws Exception {
        assertGet("/api/v1/knowledge/" + KG1 + "/spans", "kg-spans-completed.json");
        assertGet("/api/v1/knowledge/" + KG1 + "/stages", "kg-stages-completed.json");
        assertGet("/api/v1/knowledge/" + KG6 + "/spans", "kg-spans-failed.json");
        assertGet("/api/v1/knowledge/" + KG5 + "/spans", "kg-spans-pending.json");
        assertGet("/api/v1/knowledge/" + KG7 + "/spans", "kg-spans-cancelled.json");
        assertGet("/api/v1/knowledge/" + KG6 + "/spans?attempt=2", "kg-spans-attempt2.json");
        assertGet("/api/v1/knowledge/" + KG1 + "/spans?attempt=abc", "kg-spans-attempt-bad.json");
        assertGet("/api/v1/knowledge/" + UNKNOWN + "/spans", "kg-spans-404.json");
        assertGet("/api/v1/knowledge/" + CROSS_KG + "/spans", "kg-spans-cross.json");
    }

    // ════════════════ 3) regenerate-summary ════════════════

    @Test
    void regenerateSummaryMatchesGo() throws Exception {
        assertPost("/api/v1/knowledge/" + KG1 + "/regenerate-summary", null, "kg-regen-none.json");
        assertPost("/api/v1/knowledge/" + KG12 + "/regenerate-summary", null, "kg-regen-refresh.json");
        assertPost("/api/v1/knowledge/" + KG12 + "/regenerate-summary", null, "kg-regen-again.json");
        assertPost("/api/v1/knowledge/" + UNKNOWN + "/regenerate-summary", null, "kg-regen-404.json");
        assertPost("/api/v1/knowledge/" + CROSS_KG + "/regenerate-summary", null, "kg-regen-cross.json");
    }

    // ════════════════ 4) manual 更新（线性：publish → draft → 错误族） ════════════════

    @Test
    void manualUpdateMatchesGo() throws Exception {
        assertPut("/api/v1/knowledge/manual/" + KG4,
                "{\"title\":\"手工知识（改）\",\"content\":\"# 手工知识\\n\\n更新后的正文\",\"status\":\"publish\"}",
                "kg-manual-update.json");
        assertPut("/api/v1/knowledge/manual/" + KG4,
                "{\"title\":\"手工知识（改）\",\"content\":\"# 手工知识\\n\\n草稿正文\",\"status\":\"draft\"}",
                "kg-manual-draft.json");
        assertPut("/api/v1/knowledge/manual/" + KG4,
                "{\"title\":\"手工知识（改）\",\"content\":\"   \",\"status\":\"draft\"}",
                "kg-manual-empty.json");
        assertPut("/api/v1/knowledge/manual/" + KG4,
                "{\"title\":\"<script>alert(1)</script>\",\"content\":\"正文\",\"status\":\"draft\"}",
                "kg-manual-badtitle.json");
        assertPut("/api/v1/knowledge/manual/" + KG4,
                "{\"title\":\"手工知识（改）\",\"content\":\"正文\",\"status\":\"enabled\"}",
                "kg-manual-badstatus.json");
        assertPut("/api/v1/knowledge/manual/" + KG1,
                "{\"title\":\"x\",\"content\":\"正文\",\"status\":\"draft\"}",
                "kg-manual-notmanual.json");
        assertPut("/api/v1/knowledge/manual/" + UNKNOWN,
                "{\"title\":\"x\",\"content\":\"正文\",\"status\":\"draft\"}",
                "kg-manual-404.json");
        assertPutNoBody("/api/v1/knowledge/manual/" + KG4, "kg-manual-no-body.json");
        assertPut("/api/v1/knowledge/manual/" + CROSS_KG,
                "{\"title\":\"x\",\"content\":\"正文\",\"status\":\"draft\"}",
                "kg-manual-cross.json");
    }

    // ════════════════ 5) reparse（依赖 manual 前缀：KG4 已到 draft v3） ════════════════

    @Test
    void reparseMatchesGo() throws Exception {
        replayManualUpdates();
        assertPost("/api/v1/knowledge/" + KG10 + "/reparse", null, "kg-reparse.json");
        assertPost("/api/v1/knowledge/" + KG10 + "/reparse", "{\"processConfig\":null}",
                "kg-reparse-null-config.json");
        assertPost("/api/v1/knowledge/" + KG10 + "/reparse", "not-json", "kg-reparse-bad.json");
        assertPost("/api/v1/knowledge/" + KG4 + "/reparse", null, "kg-reparse-manual.json");
        assertPost("/api/v1/knowledge/" + UNKNOWN + "/reparse", null, "kg-reparse-404.json");
        assertPost("/api/v1/knowledge/" + CROSS_KG + "/reparse", null, "kg-reparse-cross.json");
    }

    // ════════════════ 6) cancel-parse ════════════════

    @Test
    void cancelParseMatchesGo() throws Exception {
        assertPost("/api/v1/knowledge/" + KG5 + "/cancel-parse", null, "kg-cancel.json");
        assertPost("/api/v1/knowledge/" + KG5 + "/cancel-parse", null, "kg-cancel-again.json");
        assertPost("/api/v1/knowledge/" + KG7 + "/cancel-parse", null, "kg-cancel-cancelled-row.json");
        assertPost("/api/v1/knowledge/" + KG14 + "/cancel-parse", null, "kg-cancel-processing.json");
        assertPost("/api/v1/knowledge/" + KG13 + "/cancel-parse", null, "kg-cancel-deleting.json");
        assertPost("/api/v1/knowledge/" + KG1 + "/cancel-parse", null, "kg-cancel-completed.json");
        assertPost("/api/v1/knowledge/" + KG6 + "/cancel-parse", null, "kg-cancel-failed.json");
        assertPost("/api/v1/knowledge/" + UNKNOWN + "/cancel-parse", null, "kg-cancel-404.json");
        assertPost("/api/v1/knowledge/" + CROSS_KG + "/cancel-parse", null, "kg-cancel-cross.json");
    }

    // ════════════════ 7) download / preview（依赖 manual 前缀：KG4 标题与草稿内容） ════════════════

    @Test
    void downloadPreviewMatchesGo() throws Exception {
        replayManualUpdates();

        assertFileGet("/api/v1/knowledge/" + KG1 + "/download", "kg-download.bin",
                "kg-download.bin.headers", true);
        assertFileGet("/api/v1/knowledge/" + KG4 + "/download", "kg-download-manual.bin",
                "kg-download-manual.bin.headers", true);
        assertGet("/api/v1/knowledge/" + KG15 + "/download", "kg-download-traversal.json");
        assertGet("/api/v1/knowledge/" + UNKNOWN + "/download", "kg-download-404.json");
        assertGet("/api/v1/knowledge/" + CROSS_KG + "/download", "kg-download-cross.json");
        MvcResult v = mockMvc.perform(get("/api/v1/knowledge/" + KG1 + "/download")
                .header("Authorization", viewer)).andReturn();
        assertEquals(403, v.getResponse().getStatus(), raw(v));
        assertEquals(golden("kg-download-viewer.json"), raw(v));

        assertFileGet("/api/v1/knowledge/" + KG1 + "/preview", "kg-preview.bin",
                "kg-preview.bin.headers", false);
        assertFileGet("/api/v1/knowledge/" + KG4 + "/preview", "kg-preview-manual.bin",
                "kg-preview-manual.bin.headers", false);
        assertGet("/api/v1/knowledge/" + KG15 + "/preview", "kg-preview-traversal.json");
        assertGet("/api/v1/knowledge/" + UNKNOWN + "/preview", "kg-preview-404.json");
        assertGet("/api/v1/knowledge/" + CROSS_KG + "/preview", "kg-preview-cross.json");
    }

    /**
     * W5γ5.4 ①b 新增能力：本地文件的 download 走 {@code filetransport.Serve} 的 Seekable 支路
     * → **支持 Range**（改前是"读满 byte[]"，只有 no-Range 两种形态）。
     *
     * <p>这条是**断言型**用例（非 golden）：Go 侧的 kg-* 实录没录 Range 场景，
     * 故此处不锚 golden，只钉 206/Content-Range 的形状；要升级成 golden 可照
     * {@code record-w5c-golden.sh} 的手法补录。</p>
     */
    @Test
    void downloadSupportsRange() throws Exception {
        replayManualUpdates();
        MvcResult r = mockMvc.perform(get("/api/v1/knowledge/" + KG1 + "/download")
                .header("Authorization", owner).header("Range", "bytes=0-5")).andReturn();
        assertEquals(206, r.getResponse().getStatus(), raw(r));
        assertEquals("bytes", r.getResponse().getHeader("Accept-Ranges"));
        assertEquals("bytes 0-5/34", r.getResponse().getHeader("Content-Range"));
        assertEquals(6, r.getResponse().getContentAsByteArray().length);
    }

    // ════════════════ 8) image info ════════════════

    @Test
    void imageInfoMatchesGo() throws Exception {
        assertPut("/api/v1/knowledge/image/" + KG11 + "/" + C1, IMG_BODY, "kg-image-update.json");
        assertPut("/api/v1/knowledge/image/" + KG11 + "/" + C1, IMG_BODY, "kg-image-again.json");
        assertPut("/api/v1/knowledge/image/" + KG11 + "/" + C1, "{\"imageInfo\":\"[]\"}",
                "kg-image-empty.json");
        assertPut("/api/v1/knowledge/image/" + KG11 + "/" + C1, "{\"imageInfo\":\"not-json\"}",
                "kg-image-badjson.json");
        assertPut("/api/v1/knowledge/image/" + KG11 + "/" + C2, IMG_BODY, "kg-image-mismatch.json");
        assertPut("/api/v1/knowledge/image/" + KG11 + "/" + UNKNOWN, IMG_BODY, "kg-image-404.json");
        assertPutNoBody("/api/v1/knowledge/image/" + KG11 + "/" + C1, "kg-image-no-body.json");
    }

    // ════════════════ 9) tags 批量（线性：set → get → clear → set 无 kb_id → 错误族） ════════════════

    @Test
    void tagsMatchGo() throws Exception {
        assertPut("/api/v1/knowledge/tags",
                "{\"updates\":{\"" + KG2 + "\":[\"" + T1 + "\"]},\"kbId\":\"" + KB1 + "\"}",
                "kg-tags.json");
        assertGet("/api/v1/knowledge/" + KG2, "kg-get-tagged.json");
        assertPut("/api/v1/knowledge/tags",
                "{\"updates\":{\"" + KG2 + "\":[]}}", "kg-tags-clear.json");
        assertPut("/api/v1/knowledge/tags",
                "{\"updates\":{\"" + KG2 + "\":[\"" + T1 + "\"]}}", "kg-tags-no-kbid.json");
        assertPut("/api/v1/knowledge/tags", "{\"updates\":{}}", "kg-tags-empty-updates.json");
        assertPutNoBody("/api/v1/knowledge/tags", "kg-tags-missing.json");
        assertPut("/api/v1/knowledge/tags",
                "{\"updates\":{\"" + KG2 + "\":[\"nope\"]},\"kbId\":\"" + KB1 + "\"}",
                "kg-tags-unknown-tag.json");
        assertPut("/api/v1/knowledge/tags",
                "{\"updates\":{\"" + KG2 + "\":[\"" + T2 + "\"]},\"kbId\":\"" + KB1 + "\"}",
                "kg-tags-wrong-kb.json");
        assertPut("/api/v1/knowledge/tags",
                "{\"updates\":{\"" + UNKNOWN + "\":[\"" + T1 + "\"]},\"kbId\":\"" + KB1 + "\"}",
                "kg-tags-unknown-knowledge.json");
        assertPut("/api/v1/knowledge/tags",
                "{\"updates\":{\"" + CROSS_KG + "\":[\"" + T1 + "\"]}}", "kg-tags-cross-tenant.json");
        assertPut("/api/v1/knowledge/tags",
                "{\"updates\":{\"" + KG16 + "\":[\"" + T2 + "\"]},\"kbId\":\"" + KB1 + "\"}",
                "kg-tags-cross-kb.json");
        assertPut("/api/v1/knowledge/tags",
                "{\"updates\":{\"" + KG2 + "\":[\"" + T1 + "\"]},\"kbId\":\"" + UNKNOWN + "\"}",
                "kg-tags-badkb.json");
        MvcResult c = mockMvc.perform(put("/api/v1/knowledge/tags")
                .header("Authorization", contrib)
                .contentType("application/json")
                .content("{\"updates\":{\"" + KG2 + "\":[\"" + T1 + "\"]},\"kbId\":\"" + KB1 + "\"}"))
                .andReturn();
        assertEquals(403, c.getResponse().getStatus(), raw(c));
        assertEquals(golden("kg-tags-contrib.json"), raw(c));
    }

    // ════════════════ 10) batch-delete ════════════════

    @Test
    void batchDeleteMatchesGo() throws Exception {
        assertPost("/api/v1/knowledge/batch-delete",
                "{\"kbId\":\"" + KB1 + "\",\"ids\":[\"" + KG3 + "\"]}", "kg-batch-delete.json");
        assertPost("/api/v1/knowledge/batch-delete",
                "{\"kbId\":\"" + KB1 + "\",\"ids\":[\"" + KG2 + "\",\"" + UNKNOWN + "\"]}",
                "kg-batch-delete-missing.json");
        assertPost("/api/v1/knowledge/batch-delete",
                "{\"kbId\":\"" + KB1 + "\",\"ids\":[\"" + KG16 + "\"]}", "kg-batch-delete-cross.json");
        assertPost("/api/v1/knowledge/batch-delete",
                "{\"kbId\":\"" + KB1 + "\",\"ids\":[]}", "kg-batch-delete-empty-ids.json");
        assertPostNoBody("/api/v1/knowledge/batch-delete", "kg-batch-delete-no-body.json");
        assertPost("/api/v1/knowledge/batch-delete",
                "{\"kbId\":\"" + UNKNOWN + "\",\"ids\":[\"" + KG2 + "\"]}", "kg-batch-delete-badkb.json");
        assertPostContrib("/api/v1/knowledge/batch-delete",
                "{\"kbId\":\"" + KB1 + "\",\"ids\":[\"" + KG2 + "\"]}", "kg-batch-delete-contrib.json");
    }

    // ════════════════ 11) batch-reparse ════════════════

    @Test
    void batchReparseMatchesGo() throws Exception {
        assertPost("/api/v1/knowledge/batch-reparse",
                "{\"kbId\":\"" + KB1 + "\",\"ids\":[\"" + KG2 + "\"]}", "kg-batch-reparse.json");
        assertPost("/api/v1/knowledge/batch-reparse",
                "{\"kbId\":\"" + KB1 + "\",\"ids\":[\"" + KG2 + "\",\"" + UNKNOWN + "\"]}",
                "kg-batch-reparse-missing.json");
        assertPost("/api/v1/knowledge/batch-reparse",
                "{\"kbId\":\"" + KB1 + "\",\"ids\":[]}", "kg-batch-reparse-empty-ids.json");
        assertPostNoBody("/api/v1/knowledge/batch-reparse", "kg-batch-reparse-no-body.json");
        assertPost("/api/v1/knowledge/batch-reparse",
                "{\"kbId\":\"" + UNKNOWN + "\",\"ids\":[\"" + KG2 + "\"]}", "kg-batch-reparse-badkb.json");
        assertPost("/api/v1/knowledge/batch-reparse",
                "{\"kbId\":\"" + KB1 + "\",\"ids\":[\"" + KG16 + "\"]}", "kg-batch-reparse-cross.json");
        assertPostContrib("/api/v1/knowledge/batch-reparse",
                "{\"kbId\":\"" + KB1 + "\",\"ids\":[\"" + KG2 + "\"]}", "kg-batch-reparse-contrib.json");
    }

    // ════════════════ 12) folder move ════════════════

    @Test
    void folderMoveMatchesGo() throws Exception {
        assertPost("/api/v1/knowledge/folder",
                "{\"kbId\":\"" + KB1 + "\",\"knowledgeIds\":[\"" + KG2 + "\"],\"folderPath\":\"docs/notes\"}",
                "kg-move.json");
        assertPost("/api/v1/knowledge/folder",
                "{\"kbId\":\"" + KB1 + "\",\"knowledgeIds\":[\"" + KG2 + "\"],\"folderPath\":\"\"}",
                "kg-move-back.json");
        assertPost("/api/v1/knowledge/folder",
                "{\"kbId\":\"" + KB1 + "\",\"knowledgeIds\":[]}", "kg-move-empty.json");
        assertPost("/api/v1/knowledge/folder",
                "{\"kbId\":\"" + KB1 + "\",\"knowledgeIds\":[\"" + UNKNOWN + "\"]}",
                "kg-move-missing.json");
        assertPost("/api/v1/knowledge/folder",
                "{\"kbId\":\"" + KB1 + "\",\"knowledgeIds\":[\"" + KG16 + "\"]}", "kg-move-cross.json");
        assertPost("/api/v1/knowledge/folder",
                "{\"kbId\":\"" + UNKNOWN + "\",\"knowledgeIds\":[\"" + KG2 + "\"]}",
                "kg-move-badkb.json");
        assertPost("/api/v1/knowledge/folder",
                "{\"kbId\":\"" + KB1 + "\",\"knowledgeIds\":[\"" + KG2 + "\"],\"folderPath\":\"<script>x\"}",
                "kg-move-badpath.json");
        assertPost("/api/v1/knowledge/folder",
                "{\"knowledgeIds\":[\"" + KG2 + "\"]}", "kg-move-nokb.json");
        assertPostNoBody("/api/v1/knowledge/folder", "kg-move-no-body.json");
        assertPostContrib("/api/v1/knowledge/folder",
                "{\"kbId\":\"" + KB1 + "\",\"knowledgeIds\":[\"" + KG2 + "\"]}", "kg-move-contrib.json");
    }

    // ════════════════ 13) folder rename（依赖 batch-delete + move 前缀：folders 计数收敛） ════════════════

    @Test
    void folderRenameMatchesGo() throws Exception {
        // 前缀 1：batch-delete 消费 KG3（Go 异步、Java 同步，计数收敛）
        assertPost("/api/v1/knowledge/batch-delete",
                "{\"kbId\":\"" + KB1 + "\",\"ids\":[\"" + KG3 + "\"]}", "kg-batch-delete.json");
        // 前缀 2：move 链（KG2 最终落在 <scriptx）
        assertPost("/api/v1/knowledge/folder",
                "{\"kbId\":\"" + KB1 + "\",\"knowledgeIds\":[\"" + KG2 + "\"],\"folderPath\":\"docs/notes\"}",
                "kg-move.json");
        assertPost("/api/v1/knowledge/folder",
                "{\"kbId\":\"" + KB1 + "\",\"knowledgeIds\":[\"" + KG2 + "\"],\"folderPath\":\"\"}",
                "kg-move-back.json");
        assertPost("/api/v1/knowledge/folder",
                "{\"kbId\":\"" + KB1 + "\",\"knowledgeIds\":[\"" + KG2 + "\"],\"folderPath\":\"<script>x\"}",
                "kg-move-badpath.json");

        assertPut("/api/v1/knowledge-bases/" + KB1 + "/knowledge/folders",
                "{\"from\":\"docs\",\"to\":\"documents\"}", "kg-rename.json");
        assertPut("/api/v1/knowledge-bases/" + KB1 + "/knowledge/folders",
                "{\"from\":\"documents\",\"to\":\"documents\"}", "kg-rename-same.json");
        assertPut("/api/v1/knowledge-bases/" + KB1 + "/knowledge/folders",
                "{\"from\":\"documents\",\"to\":\"documents/sub\"}", "kg-rename-into-self.json");
        assertPut("/api/v1/knowledge-bases/" + KB1 + "/knowledge/folders",
                "{\"from\":\"documents\",\"to\":\"<script>x\"}", "kg-rename-badto.json");
        assertPut("/api/v1/knowledge-bases/" + KB1 + "/knowledge/folders",
                "{\"from\":\"documents\",\"to\":\"\"}", "kg-rename-empty-to.json");
        assertPut("/api/v1/knowledge-bases/" + KB1 + "/knowledge/folders",
                "{\"to\":\"x\"}", "kg-rename-no-from.json");
        assertPut("/api/v1/knowledge-bases/" + UNKNOWN + "/knowledge/folders",
                "{\"from\":\"documents\",\"to\":\"x\"}", "kg-rename-404.json");
        assertPutContrib("/api/v1/knowledge-bases/" + KB1 + "/knowledge/folders",
                "{\"from\":\"documents\",\"to\":\"x\"}", "kg-rename-contrib.json");
        assertGet("/api/v1/knowledge-bases/" + KB1 + "/knowledge/folders", "kg-folders2.json");
    }

    // ════════════════ 14) clear contents ════════════════

    @Test
    void clearContentsMatchesGo() throws Exception {
        assertDelete("/api/v1/knowledge-bases/" + KB2 + "/knowledge", "kg-clear-nonempty.json");
        assertDelete("/api/v1/knowledge-bases/" + KB2 + "/knowledge", "kg-clear-again.json");
        assertDelete("/api/v1/knowledge-bases/" + KB3 + "/knowledge", "kg-clear-empty.json");
        assertDelete("/api/v1/knowledge-bases/" + UNKNOWN + "/knowledge", "kg-clear-404.json");
        MvcResult c = mockMvc.perform(delete("/api/v1/knowledge-bases/" + KB1 + "/knowledge")
                .header("Authorization", contrib)).andReturn();
        assertEquals(403, c.getResponse().getStatus(), raw(c));
        assertEquals(golden("kg-clear-contrib.json"), raw(c));
    }

    // ════════════════ 前缀回放 ════════════════

    /** manual 段的两个成功更新（publish v2 → draft v3），后续段落的 KG4 状态依赖。 */
    private void replayManualUpdates() throws Exception {
        assertPut("/api/v1/knowledge/manual/" + KG4,
                "{\"title\":\"手工知识（改）\",\"content\":\"# 手工知识\\n\\n更新后的正文\",\"status\":\"publish\"}",
                "kg-manual-update.json");
        assertPut("/api/v1/knowledge/manual/" + KG4,
                "{\"title\":\"手工知识（改）\",\"content\":\"# 手工知识\\n\\n草稿正文\",\"status\":\"draft\"}",
                "kg-manual-draft.json");
    }

    // ════════════════ 请求/断言工具 ════════════════

    private void assertGet(String path, String golden) throws Exception {
        MvcResult r = mockMvc.perform(get(path).header("Authorization", owner)).andReturn();
        assertEquals(expectedStatus(golden), r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden(golden)), mask(raw(r)), golden);
    }

    private void assertPost(String path, String body, String golden) throws Exception {
        MockHttpServletRequestBuilder b = post(path).header("Authorization", owner)
                .contentType("application/json");
        if (body != null) {
            b.content(body);
        }
        MvcResult r = mockMvc.perform(b).andReturn();
        assertEquals(expectedStatus(golden), r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden(golden)), mask(raw(r)), golden);
    }

    private void assertPostNoBody(String path, String golden) throws Exception {
        MvcResult r = mockMvc.perform(post(path).header("Authorization", owner)
                .contentType("application/json")).andReturn();
        assertEquals(expectedStatus(golden), r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden(golden)), mask(raw(r)), golden);
    }

    private void assertPostContrib(String path, String body, String golden) throws Exception {
        MvcResult r = mockMvc.perform(post(path).header("Authorization", contrib)
                .contentType("application/json").content(body)).andReturn();
        assertEquals(expectedStatus(golden), r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden(golden)), mask(raw(r)), golden);
    }

    private void assertPut(String path, String body, String golden) throws Exception {
        MvcResult r = mockMvc.perform(put(path).header("Authorization", owner)
                .contentType("application/json").content(body)).andReturn();
        assertEquals(expectedStatus(golden), r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden(golden)), mask(raw(r)), golden);
    }

    private void assertPutNoBody(String path, String golden) throws Exception {
        MvcResult r = mockMvc.perform(put(path).header("Authorization", owner)
                .contentType("application/json")).andReturn();
        assertEquals(expectedStatus(golden), r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden(golden)), mask(raw(r)), golden);
    }

    private void assertPutContrib(String path, String body, String golden) throws Exception {
        MvcResult r = mockMvc.perform(put(path).header("Authorization", contrib)
                .contentType("application/json").content(body)).andReturn();
        assertEquals(expectedStatus(golden), r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden(golden)), mask(raw(r)), golden);
    }

    private void assertDelete(String path, String golden) throws Exception {
        MvcResult r = mockMvc.perform(delete(path).header("Authorization", owner)).andReturn();
        assertEquals(expectedStatus(golden), r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden(golden)), mask(raw(r)), golden);
    }

    /** 下载/预览：正文字节 + 契约头（响应头文件为 key: value 行；忽略 Date/X-Request-Id
     *  等容器噪音）。headers 文件名形如 kg-download.bin.headers。 */
    private void assertFileGet(String path, String bodyGolden, String headerGolden,
                               boolean download) throws Exception {
        MvcResult r = mockMvc.perform(get(path).header("Authorization", owner)).andReturn();
        assertEquals(expectedStatus(bodyGolden), r.getResponse().getStatus(), raw(r));
        assertThat(r.getResponse().getContentAsByteArray())
                .isEqualTo(new ClassPathResource("contracts/" + bodyGolden).getInputStream().readAllBytes());
        for (String line : golden(headerGolden).split("\n")) {
            int idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String name = line.substring(0, idx).trim();
            String lower = name.toLowerCase();
            if (lower.equals("date") || lower.equals("x-request-id") || lower.equals("keep-alive")
                    || lower.equals("connection")) {
                continue;
            }
            String expected = line.substring(idx + 1).trim();
            String actual = r.getResponse().getHeader(name);
            assertThat(actual).as("%s: %s", bodyGolden, name).isEqualTo(expected);
        }
        // Go 侧 gin 写出的头集合是契约的一部分：逐个头都必须存在
        assertThat(r.getResponse().getHeaderNames())
                .as("%s headers", bodyGolden)
                .contains("Content-Type", "X-Content-Type-Options", "Content-Disposition",
                        "Cache-Control", "Accept-Ranges");
    }

    /** golden 文件名不含状态；各段断言前先按已录状态写死（避免从文件名解析）。 */
    private int expectedStatus(String golden) {
        return switch (golden) {
                case "kg-batch-no-ids.json", "kg-batch-bad-source.json", "kg-regen-none.json",
                     "kg-regen-refresh.json", "kg-regen-again.json", "kg-manual-empty.json",
                     "kg-manual-badtitle.json", "kg-manual-badstatus.json", "kg-manual-notmanual.json",
                     "kg-manual-no-body.json", "kg-reparse-bad.json", "kg-cancel-deleting.json",
                     "kg-cancel-completed.json", "kg-cancel-failed.json", "kg-tags-empty-updates.json",
                     "kg-tags-missing.json", "kg-tags-unknown-tag.json", "kg-tags-wrong-kb.json",
                     "kg-batch-delete-missing.json", "kg-batch-delete-cross.json",
                     "kg-batch-delete-empty-ids.json", "kg-batch-delete-no-body.json",
                     "kg-batch-reparse-missing.json", "kg-batch-reparse-empty-ids.json",
                     "kg-batch-reparse-no-body.json", "kg-batch-reparse-cross.json",
                     "kg-move-empty.json", "kg-move-missing.json",
                     "kg-move-cross.json", "kg-move-nokb.json", "kg-move-no-body.json",
                     "kg-rename-into-self.json", "kg-rename-empty-to.json", "kg-rename-no-from.json",
                     "kg-image-no-body.json" -> 400;
                case "kg-batch-badkb.json", "kg-spans-404.json", "kg-regen-404.json",
                     "kg-manual-404.json", "kg-reparse-404.json", "kg-cancel-404.json",
                     "kg-download-404.json", "kg-preview-404.json", "kg-tags-unknown-knowledge.json",
                     "kg-tags-badkb.json", "kg-batch-delete-badkb.json", "kg-batch-reparse-badkb.json",
                     "kg-move-badkb.json", "kg-rename-404.json", "kg-clear-404.json" -> 404;
                case "kg-spans-cross.json", "kg-regen-cross.json", "kg-manual-cross.json",
                     "kg-reparse-cross.json", "kg-cancel-cross.json", "kg-download-cross.json",
                     "kg-preview-cross.json", "kg-tags-cross-tenant.json", "kg-tags-cross-kb.json",
                     "kg-tags-contrib.json", "kg-batch-delete-contrib.json",
                     "kg-batch-reparse-contrib.json", "kg-move-contrib.json",
                     "kg-rename-contrib.json", "kg-batch-agent-unknown.json" -> 403;
                case "kg-image-update.json", "kg-image-again.json", "kg-image-badjson.json",
                     "kg-image-mismatch.json", "kg-image-404.json", "kg-download-traversal.json",
                     "kg-preview-traversal.json" -> 500;
                // 异步受理（删除/重析/清空/文件夹搬移）：任务已入队但未完成
                case "kg-batch-delete.json", "kg-batch-reparse.json", "kg-clear-empty.json",
                     "kg-clear-nonempty.json", "kg-clear-again.json" -> 202;
                // 无响应体的操作
                case "kg-image-empty.json", "kg-tags.json", "kg-tags-clear.json",
                     "kg-tags-no-kbid.json" -> 204;
                default -> 200;
        };
    }

    private String login(String email) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/auth/login")
                .contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"Passw0rd!\"}")).andReturn();
        Matcher m = TOKEN.matcher(raw(r));
        assertThat(m.find()).as("login 响应应含 token: " + raw(r)).isTrue();
        return m.group(1);
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

    /** 两侧同掩码：UUID 值 + 文案内嵌 UUID + 真实时间戳。 */
    private static String mask(String s) {
        String out = UUID_VALUE.matcher(s).replaceAll("\"$1\":\"<uuid>\"");
        out = UUID_BARE.matcher(out).replaceAll("<uuid>");
        return TS_VALUE.matcher(out).replaceAll("\"$1\":\"<ts>\"");
    }
}
