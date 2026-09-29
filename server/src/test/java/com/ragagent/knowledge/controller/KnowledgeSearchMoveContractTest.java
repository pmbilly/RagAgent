package com.ragagent.knowledge.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.OffsetDateTime;
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

/**
 * knowledge「搜索与移动/复制」契约测试（波 2 第三批，8 条路由）。golden：
 * scripts/record-knowledge-search-golden.sh。
 *
 * <p>种子严格复刻录制脚本：KB1..KB7（KB4=faq、KB5 挂异构 embedding、KB6 挂
 * vector_store、KS1/2/3/7 关索引）、KG1..KG9（纯十六进制 id、created_at 互不相同且
 * DESC 序 = 录制序、custom_metadata='{}' 对照 PG 列默认）。搜索 golden 用租户内唯一
 * 关键词 ksdoc 收敛命中集合；move/copy 是异步任务——用例轮询进度到 completed 再比对
 * 终态 golden（Go 侧 sleep 4s 后录到的也是终态）。</p>
 *
 * <p>掩码：UUID 值/文案内嵌 UUID/ISO 时间戳沿用既有三件套，另加 task_id（嵌租户+时间戳
 * +uuid，两侧必然不同）与 updated_at 的 10 位 Unix 秒（进度对象的 created_at 恒 0
 * ——Go worker 覆写不带 created_at，两侧同为字面量 0，不掩）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class KnowledgeSearchMoveContractTest {

    private static final String BCRYPT =
            "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK";
    private static final long TENANT = 10002L;
    private static final String OWNER = "11111111-2222-3333-4444-555555555501";
    private static final String VIEWER = "11111111-2222-3333-4444-555555555504";
    private static final String CONTRIBUTOR = "11111111-2222-3333-4444-555555555505";
    private static final String OWNER_EMAIL = "ks-contract-owner@weknora.test";
    private static final String VIEWER_EMAIL = "ks-contract-viewer@weknora.test";
    private static final String CONTRA_EMAIL = "ks-contract-contrib@weknora.test";
    private static final String UNKNOWN = "11111111-2222-3333-4444-999999999999";

    private static final String KB1 = "eda10001-0000-0000-0000-000000000001";
    private static final String KB2 = "eda10001-0000-0000-0000-000000000002";
    private static final String KB3 = "eda10001-0000-0000-0000-000000000003";
    private static final String KB4 = "eda10001-0000-0000-0000-000000000004";
    private static final String KB5 = "eda10001-0000-0000-0000-000000000005";
    private static final String KB6 = "eda10001-0000-0000-0000-000000000006";
    private static final String KB7 = "eda10001-0000-0000-0000-000000000007";
    private static final String CROSS_KB = "eda10001-0000-0000-0000-0000000000f9";
    private static final String BACKEND = "f5a6b7c8-0000-0000-0000-000000000001";
    private static final String KG1 = "a2e00001-0000-0000-0000-000000000001";
    private static final String KG2 = "a2e00001-0000-0000-0000-000000000002";
    private static final String KG3 = "a2e00001-0000-0000-0000-000000000003";
    private static final String KG4 = "a2e00001-0000-0000-0000-000000000004";
    private static final String KG5 = "a2e00001-0000-0000-0000-000000000005";
    private static final String KG6 = "a2e00001-0000-0000-0000-000000000006";
    private static final String KG7 = "a2e00001-0000-0000-0000-000000000007";
    private static final String KG8 = "a2e00001-0000-0000-0000-000000000008";
    private static final String KG9 = "a2e00001-0000-0000-0000-000000000009";

    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern UUID_VALUE = Pattern.compile(
            "\"([A-Za-z_]+)\":\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"");
    private static final Pattern UUID_BARE = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern TS_VALUE = Pattern.compile(
            "\"([A-Za-z_]+)\":\"[2-9]\\d{3}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})\"");
    private static final Pattern TASK_ID = Pattern.compile(
            "\"(?:task_id|taskId)\":\"([^\"]*)\"");
    private static final Pattern EPOCH_TS = Pattern.compile(
            "\"([A-Za-z_]+)\":1\\d{9}");

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
        tenant.setName("ks-contract-tenant");
        tenant.setStatus("active");
        tenantMapper.insert(tenant);
        Tenant crossTenant = new Tenant();
        crossTenant.setId(10000L);
        crossTenant.setName("billy-workspace");
        crossTenant.setStatus("active");
        tenantMapper.insert(crossTenant);

        seedUser(OWNER, "ksowner", OWNER_EMAIL, "owner");
        seedUser(VIEWER, "ksviewer", VIEWER_EMAIL, "viewer");
        seedUser(CONTRIBUTOR, "kscontrib", CONTRA_EMAIL, "contributor");

        String off = "{\"vector_enabled\":false,\"keyword_enabled\":false,"
                + "\"wiki_enabled\":false,\"graph_enabled\":false}";
        // 对照 Go dev：API 建 KB 时 applyAndValidateStorageBackend 会把租户的
        // System LOCAL（legacy alias, source=env）回填进 storage_backend_id + provider=local——
        // duplicate 响应里的这两个字段依赖它（golden 钉住）。
        jdbc.update("INSERT INTO storage_backends (id, tenant_id, name, provider, config, source, "
                + "status, legacy_alias) VALUES (?, ?, 'System LOCAL', 'local', '{}', 'env', "
                + "'active', TRUE)", BACKEND, TENANT);
        seedKb(KB1, "ks-golden-kb", "document", off, "");
        seedKb(KB2, "ks-golden-second", "document", off, "");
        seedKb(KB3, "ks-golden-empty", "document", off, "");
        seedKb(KB4, "ks-golden-faq", "faq", off, "");
        seedKb(KB5, "ks-golden-emb", "document", off, "emb-other");
        seedKb(KB6, "ks-golden-store", "document", off, "");
        seedKb(KB7, "ks-golden-src", "document", off, "");
        jdbc.update("UPDATE knowledge_bases SET vector_store_id='b1c2d3d4-0000-0000-0000-000000000001' "
                + "WHERE id=?", KB6);
        jdbc.update("INSERT INTO knowledge_bases (id, name, tenant_id, type, creator_id) "
                + "VALUES (?, 'cross-kb', 10000, 'document', ?)", CROSS_KB, OWNER);

        // created_at 互不相同（Go 按 created_at DESC；并列顺序不稳定），DESC 序：
        // KG6 > KG5 > KG9 > KG4 > KG3 > KG7 > KG8 > KG2 > KG1
        seedDoc(KG1, KB1, "ksdoc alpha 指南", "ksdoc-alpha.txt", "txt", 10, "a1", "completed", 50);
        seedDoc(KG2, KB1, "beta 报表", "ksdoc-beta.txt", "txt", 10, "a2", "completed", 40);
        seedDoc(KG3, KB1, "ksdoc gamma 手册", "ksdoc-gamma.pdf", "pdf", 10, "a3", "completed", 30);
        seedDoc(KG4, KB1, "ksdoc delta 表格", "ksdoc-delta.xlsx", "xlsx", 10, "a4", "completed", 20);
        seedDoc(KG5, KB1, "ksdoc epsilon 待解析", "ksdoc-epsilon.txt", "txt", 10, "a5", "pending", 5);
        seedDoc(KG6, KB2, "第二库的 ksdoc", "ksdoc-sixth.txt", "txt", 10, "a6", "completed", 0);
        seedDoc(KG7, KB7, "clone 源一", "clone-src-one.txt", "txt", 10, "a7", "completed", 45);
        seedDoc(KG8, KB7, "clone 源二", "clone-src-two.txt", "txt", 10, "a8", "completed", 44);
        // url 类型行（source=file_type='url'，file_types=url 走 type 分支）
        jdbc.update("INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, "
                + "source, parse_status, summary_status, enable_status, file_name, file_type, "
                + "file_size, file_hash, file_path, custom_metadata, created_at, updated_at) VALUES "
                + "(?, ?, ?, 'url', 'ksdoc url 页', 'url', 'completed', 'none', 'enabled', "
                + "'https://example.com/ksdoc-page', 'url', 0, ?, '', '{}', ?, ?)",
                KG9, TENANT, KB1, "000000000000000000000000000000a9",
                OffsetDateTime.now().minusMinutes(10), OffsetDateTime.now().minusMinutes(10));

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

    private void seedKb(String id, String name, String type, String strategy, String embeddingModel) {
        jdbc.update("INSERT INTO knowledge_bases (id, name, tenant_id, type, description, "
                + "creator_id, summary_model_id, embedding_model_id, indexing_strategy, "
                + "storage_backend_id, storage_provider_config) "
                + "VALUES (?, ?, ?, ?, 'ks golden 专用', ?, '', ?, ?, ?, '{\"provider\":\"local\"}')",
                id, name, TENANT, type, OWNER, embeddingModel, strategy, BACKEND);
    }

    private void seedDoc(String id, String kbId, String title, String fileName, String fileType,
                         int size, String hashSuffix, String parseStatus, int minutesAgo) {
        OffsetDateTime t = minutesAgo == 0
                ? OffsetDateTime.now()
                : OffsetDateTime.now().minusMinutes(minutesAgo);
        jdbc.update("INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, "
                + "source, parse_status, summary_status, enable_status, file_name, file_type, "
                + "file_size, file_hash, file_path, custom_metadata, created_at, updated_at) VALUES "
                + "(?, ?, ?, 'document', ?, 'manual', ?, 'none', 'enabled', ?, ?, ?, "
                + "?, '', '{}', ?, ?)",
                id, TENANT, kbId, title, parseStatus, fileName, fileType, size,
                "000000000000000000000000000000" + hashSuffix, t, t);
    }

    // ════════════════ 1) GET /knowledge/search ════════════════

    @Test
    void searchMatchesGo() throws Exception {
        assertGet(owner, "/api/v1/knowledge/search", "ks-search-missing.json");
        // MockMvc 的 queryParam 会做 UTF-8 编码：空白/中文关键词用它传（避免手写 %XX 被二次编码）
        assertGetQ(owner, "/api/v1/knowledge/search", new String[][] {{"keyword", "  "}},
                "ks-search-blank.json");
        assertGetQ(owner, "/api/v1/knowledge/search",
                new String[][] {{"keyword", ""}, {"recent", "false"}}, "ks-search-recent-false.json");
        assertGet(owner, "/api/v1/knowledge/search?query=ksdoc&limit=100", "ks-search-query-alias.json");
        assertGet(owner, "/api/v1/knowledge/search?keyword=ksdoc", "ks-search-hits.json");
        assertGet(owner, "/api/v1/knowledge/search?keyword=KSDOC", "ks-search-case.json");
        assertGetQ(owner, "/api/v1/knowledge/search", new String[][] {{"keyword", "指南"}},
                "ks-search-title.json");
        assertGet(owner, "/api/v1/knowledge/search?keyword=zzzznope", "ks-search-none.json");
        assertGet(owner, "/api/v1/knowledge/search?recent=true&file_types=url", "ks-search-recent-url.json");
        assertGet(owner, "/api/v1/knowledge/search?keyword=ksdoc&file_types=pdf", "ks-search-ft-pdf.json");
        assertGet(owner, "/api/v1/knowledge/search?keyword=ksdoc&file_types=xls", "ks-search-ft-xls-alias.json");
        assertGet(owner, "/api/v1/knowledge/search?keyword=ksdoc&file_types=pdf,txt", "ks-search-ft-multi.json");
        assertGet(owner, "/api/v1/knowledge/search?keyword=ksdoc&offset=1&limit=2", "ks-search-page.json");
        assertGet(owner, "/api/v1/knowledge/search?keyword=ksdoc&offset=99&limit=2", "ks-search-offset-beyond.json");
        assertGet(owner, "/api/v1/knowledge/search?keyword=a&offset=-1", "ks-search-offset-neg.json");
        assertGet(owner, "/api/v1/knowledge/search?keyword=a&offset=abc", "ks-search-offset-nan.json");
        assertGet(owner, "/api/v1/knowledge/search?keyword=a&limit=0", "ks-search-limit-zero.json");
        assertGet(owner, "/api/v1/knowledge/search?keyword=a&limit=101", "ks-search-limit-over.json");
        assertGet(owner, "/api/v1/knowledge/search?keyword=a&limit=abc", "ks-search-limit-nan.json");
        assertGet(viewer, "/api/v1/knowledge/search?keyword=ksdoc&limit=1", "ks-search-viewer.json");
        assertGet(owner, "/api/v1/knowledge/search?keyword=zzz&recent=notabool", "ks-search-badbool.json");
    }

    // ════════════════ 2) hybrid-search（确定性分支） ════════════════

    @Test
    void hybridSearchMatchesGo() throws Exception {
        assertPostJson("/api/v1/knowledge-bases/" + UNKNOWN + "/hybrid-search",
                "{\"query_text\":\"x\"}", owner, "ks-hybrid-404.json");
        assertPostJson("/api/v1/knowledge-bases/" + CROSS_KB + "/hybrid-search",
                "{\"query_text\":\"x\"}", owner, "ks-hybrid-cross.json");
        assertPostJson("/api/v1/knowledge-bases/" + KB3 + "/hybrid-search",
                null, owner, "ks-hybrid-nobody.json");
        assertPostJson("/api/v1/knowledge-bases/" + KB3 + "/hybrid-search",
                "not-json", owner, "ks-hybrid-badjson.json");
        assertPostJson("/api/v1/knowledge-bases/" + KB3 + "/hybrid-search",
                "{}", owner, "ks-hybrid-missing.json");
        assertPostJson("/api/v1/knowledge-bases/" + KB3 + "/hybrid-search",
                "{\"query_text\":\"   \"}", owner, "ks-hybrid-blank.json");
        assertPostJson("/api/v1/knowledge-bases/" + KB3 + "/hybrid-search",
                "{\"query_text\":\"probe\"}", owner, "ks-hybrid-empty.json");
        assertPostJson("/api/v1/knowledge-bases/" + KB3 + "/hybrid-search",
                "{\"query_text\":\"probe\",\"match_count\":5}", owner, "ks-hybrid-matchcount.json");
        assertPostJson("/api/v1/knowledge-bases/" + KB3 + "/hybrid-search",
                "{\"query_text\":\"probe\",\"disable_vector_match\":true}", owner, "ks-hybrid-novec.json");
        assertPostJson("/api/v1/knowledge-bases/" + KB3 + "/hybrid-search",
                "{\"query_embedding\":[0.1,0.2],\"disable_keywords_match\":true}", owner,
                "ks-hybrid-precomputed.json");
        assertPostJson("/api/v1/knowledge-bases/" + KB3 + "/hybrid-search?resource_urls=bogus",
                "{\"query_text\":\"probe\"}", owner, "ks-hybrid-badmode.json");
        assertPostJson("/api/v1/knowledge-bases/" + KB3 + "/hybrid-search",
                "{\"query_text\":\"x\",\"knowledge_base_ids\":[\"" + UNKNOWN + "\"]}", owner,
                "ks-hybrid-unknown-multi.json");
        // GET 变体（同 handler；body 语义一致）
        MvcResult r = mockMvc.perform(get("/api/v1/knowledge-bases/" + KB3 + "/hybrid-search")
                        .header("Authorization", owner)
                        .header("Content-Type", "application/json")
                        .content("{\"query_text\":\"probe\"}")).andReturn();
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("ks-hybrid-get.json")), mask(raw(r)));
        assertGet(owner, "/api/v1/knowledge-bases/" + KB3 + "/hybrid-search", "ks-hybrid-get-nobody.json");
    }

    // ════════════════ 3) move + progress ════════════════

    @Test
    void moveMatchesGo() throws Exception {
        // 错误族（无状态变更）
        assertPostJson("/api/v1/knowledge/move", moveBody(KG1, KB1, KB1, "reparse"), owner,
                "ks-move-same.json");
        assertPostJson("/api/v1/knowledge/move", moveBody(KG1, UNKNOWN, KB2, "reparse"), owner,
                "ks-move-missing-source.json");
        assertPostJson("/api/v1/knowledge/move", moveBody(KG1, KB1, UNKNOWN, "reparse"), owner,
                "ks-move-missing-target.json");
        assertPostJson("/api/v1/knowledge/move", moveBody(UNKNOWN, KB1, KB2, "reparse"), owner,
                "ks-move-unknown-item.json");
        assertPostJson("/api/v1/knowledge/move", moveBody(KG6, KB1, KB2, "reparse"), owner,
                "ks-move-wrong-kb.json");
        assertPostJson("/api/v1/knowledge/move", moveBody(KG5, KB1, KB2, "reparse"), owner,
                "ks-move-pending.json");
        assertPostJson("/api/v1/knowledge/move", moveBody(KG1, KB1, KB2, "bogus"), owner,
                "ks-move-bad-mode.json");
        assertPostJson("/api/v1/knowledge/move", null, owner, "ks-move-no-body.json");
        assertPostJson("/api/v1/knowledge/move", "{}", owner, "ks-move-empty-body.json");
        assertPostJson("/api/v1/knowledge/move",
                "{\"knowledge_ids\":[\"" + KG1 + "\"],\"source_kb_id\":\"" + KB1
                        + "\",\"target_kb_id\":\"" + KB2 + "\"}", owner, "ks-move-missing-mode.json");
        assertPostJson("/api/v1/knowledge/move",
                "{\"knowledge_ids\":[],\"source_kb_id\":\"" + KB1 + "\",\"target_kb_id\":\"" + KB2
                        + "\",\"mode\":\"reparse\"}", owner, "ks-move-empty-ids.json");
        assertPostJson("/api/v1/knowledge/move",
                "{\"knowledge_ids\":[\" \"],\"source_kb_id\":\"" + KB1 + "\",\"target_kb_id\":\"" + KB2
                        + "\",\"mode\":\"reparse\"}", owner, "ks-move-blank-id.json");
        assertPostJson("/api/v1/knowledge/move", moveBody(KG1, KB1, KB4, "reparse"), owner,
                "ks-move-type-mismatch.json");
        assertPostJson("/api/v1/knowledge/move", moveBody(KG1, KB1, KB5, "reparse"), owner,
                "ks-move-emb-mismatch.json");
        assertPostJson("/api/v1/knowledge/move", moveBody(KG1, KB1, KB6, "reuse_vectors"), owner,
                "ks-move-store-mismatch.json");
        assertPostJson("/api/v1/knowledge/move", moveBody(KG1, CROSS_KB, KB2, "reparse"), owner,
                "ks-move-cross-source.json");
        assertPostJson("/api/v1/knowledge/move", moveBody(KG1, KB1, CROSS_KB, "reparse"), owner,
                "ks-move-cross-target.json");
        // happy path：真实搬 KG2（KB1 → KB2, reuse_vectors）
        MvcResult mv = postForOk("/api/v1/knowledge/move", moveBody(KG2, KB1, KB2, "reuse_vectors"),
                owner, "ks-move-ok.json");
        String taskId = extractTaskId(raw(mv));
        // 等 worker 完成（Java 进程内虚拟线程），比对终态进度
        String progress = awaitMoveProgress(taskId);
        assertEquals(mask(golden("ks-move-progress.json")), mask(progress),
                "move progress 终态不一致");
    }

    @Test
    void moveProgressGuardsMatchGo() throws Exception {
        assertGet(owner, "/api/v1/knowledge/move/progress/kg_move_10002_1704628851692_a1b2c3d4_probe",
                "ks-move-progress-unknown.json");
        assertGet(owner, "/api/v1/knowledge/move/progress/bogus", "ks-move-progress-invalid.json");
        assertGet(owner, "/api/v1/knowledge/move/progress/kg_move_10000_1704628851692_a1b2c3d4_probe",
                "ks-move-progress-cross.json");
    }

    // ════════════════ 4) copy + progress ════════════════

    @Test
    void copyMatchesGo() throws Exception {
        // 复刻录制序前置：copy 之前 KG2 已被 move 搬进 KS2（进度 total=4 依赖这个状态：
        // remove = KS2 里的 KG2+KG6 两行）。本用例只驱动状态，golden 断言在 moveMatchesGo。
        MvcResult mv = postForOk("/api/v1/knowledge/move", moveBody(KG2, KB1, KB2, "reuse_vectors"),
                owner, null);
        awaitMoveProgress(extractTaskId(raw(mv)));
        // create 目标（KS3 空库，total=0）
        postForOk("/api/v1/knowledge-bases/copy", "{\"source_id\":\"" + KB3 + "\"}", owner,
                "ks-copy-create.json");
        // copy 到已有目标（KS7 → KS2）：add=2 remove=2 → total=4；录到的 progress 是这条任务的
        MvcResult cp = postForOk("/api/v1/knowledge-bases/copy",
                "{\"source_id\":\"" + KB7 + "\",\"target_id\":\"" + KB2 + "\"}", owner,
                "ks-copy-to-existing.json");
        String taskId = extractTaskId(raw(cp));
        assertEquals(mask(golden("ks-copy-progress.json")), mask(awaitCloneProgress(taskId)),
                "copy progress 终态不一致");
        // 错误族
        assertPostJson("/api/v1/knowledge-bases/copy", "{\"source_id\":\"" + UNKNOWN + "\"}", owner,
                "ks-copy-missing-source.json");
        assertPostJson("/api/v1/knowledge-bases/copy", "{\"source_id\":\"" + CROSS_KB + "\"}", owner,
                "ks-copy-cross-source.json");
        assertPostJson("/api/v1/knowledge-bases/copy", null, owner, "ks-copy-no-body.json");
        assertPostJson("/api/v1/knowledge-bases/copy", "{}", owner, "ks-copy-missing-field.json");
        assertPostJson("/api/v1/knowledge-bases/copy",
                "{\"source_id\":\"" + KB3 + "\",\"task_id\":\"bogus\"}", owner,
                "ks-copy-bad-task-id.json");
        assertPostJson("/api/v1/knowledge-bases/copy",
                "{\"source_id\":\"" + KB3 + "\",\"task_id\":\"kb_clone_10000_1704628851692_a1b2c3d4_probe\"}",
                owner, "ks-copy-cross-task-id.json");
        assertPostJson("/api/v1/knowledge-bases/copy",
                "{\"source_id\":\"" + KB3 + "\",\"target_id\":\"" + KB2 + "\"}", contrib,
                "ks-copy-contrib-replace.json");
        assertPostJson("/api/v1/knowledge-bases/copy",
                "{\"source_id\":\"" + KB7 + "\",\"target_id\":\"" + KB4 + "\"}", owner,
                "ks-copy-type-mismatch.json");
        assertPostJson("/api/v1/knowledge-bases/copy",
                "{\"source_id\":\"" + KB7 + "\",\"target_id\":\"" + KB5 + "\"}", owner,
                "ks-copy-emb-mismatch.json");
    }

    @Test
    void copyProgressGuardsMatchGo() throws Exception {
        assertGet(owner, "/api/v1/knowledge-bases/copy/progress/kb_clone_10002_1704628851692_a1b2c3d4_probe",
                "ks-copy-progress-unknown.json");
        assertGet(owner, "/api/v1/knowledge-bases/copy/progress/bogus", "ks-copy-progress-invalid.json");
        assertGet(owner, "/api/v1/knowledge-bases/copy/progress/kb_clone_10000_1704628851692_a1b2c3d4_probe",
                "ks-copy-progress-cross.json");
    }

    // ════════════════ 5) duplicate ════════════════

    @Test
    void duplicateMatchesGo() throws Exception {
        assertPostJson("/api/v1/knowledge-bases/" + KB1 + "/duplicate", null, owner, "ks-duplicate.json");
        assertPostJson("/api/v1/knowledge-bases/" + KB1 + "/duplicate", null, owner,
                "ks-duplicate-again.json");
        assertPostJson("/api/v1/knowledge-bases/" + UNKNOWN + "/duplicate", null, owner,
                "ks-duplicate-404.json");
    }

    // ════════════════ 工具 ════════════════

    private static String moveBody(String kg, String source, String target, String mode) {
        return "{\"knowledge_ids\":[\"" + kg + "\"],\"source_kb_id\":\"" + source
                + "\",\"target_kb_id\":\"" + target + "\",\"mode\":\"" + mode + "\"}";
    }

    private MvcResult postForOk(String path, String body, String auth, String goldenName)
            throws Exception {
        MvcResult r = mockMvc.perform(post(path)
                        .header("Authorization", auth)
                        .header("Content-Type", "application/json")
                        .content(body == null ? "" : body)).andReturn();
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        if (goldenName != null) {
            assertEquals(mask(golden(goldenName)), mask(raw(r)));
        }
        return r;
    }

    private void assertPostJson(String path, String body, String auth, String goldenName)
            throws Exception {
        MvcResult r = mockMvc.perform(post(path)
                        .header("Authorization", auth)
                        .header("Content-Type", "application/json")
                        .content(body == null ? "" : body)).andReturn();
        assertEquals(mask(golden(goldenName)), mask(raw(r)),
                goldenName + " status=" + r.getResponse().getStatus());
    }

    private void assertGet(String auth, String path, String goldenName) throws Exception {
        MvcResult r = mockMvc.perform(get(path).header("Authorization", auth)).andReturn();
        assertEquals(mask(golden(goldenName)), mask(raw(r)),
                goldenName + " status=" + r.getResponse().getStatus());
    }

    /** queryParam 形态（空白/中文等需要容器编码的值）。 */
    private void assertGetQ(String auth, String path, String[][] params, String goldenName)
            throws Exception {
        var builder = get(path).header("Authorization", auth);
        for (String[] p : params) {
            builder = builder.queryParam(p[0], p[1]);
        }
        MvcResult r = mockMvc.perform(builder).andReturn();
        assertEquals(mask(golden(goldenName)), mask(raw(r)),
                goldenName + " status=" + r.getResponse().getStatus());
    }

    private String awaitMoveProgress(String taskId) throws Exception {
        return awaitProgress("/api/v1/knowledge/move/progress/" + taskId, "completed");
    }

    private String awaitCloneProgress(String taskId) throws Exception {
        return awaitProgress("/api/v1/knowledge-bases/copy/progress/" + taskId, "completed");
    }

    /** worker 是异步虚拟线程：轮询到终态再比（对齐录制脚本 sleep 4 的确定性语义）。 */
    private String awaitProgress(String path, String expectedStatus) throws Exception {
        for (int i = 0; i < 100; i++) {
            MvcResult r = mockMvc.perform(get(path).header("Authorization", owner)).andReturn();
            String body = raw(r);
            if (body.contains("\"status\":\"" + expectedStatus + "\"")) {
                return body;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("progress 未在时限内到达 " + expectedStatus + ": " + path);
    }

    private static String extractTaskId(String body) {
        Matcher m = TASK_ID.matcher(body);
        assertThat(m.find()).as("响应应含 task_id: " + body).isTrue();
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

    /** 两侧同掩码：UUID / 文案内嵌 UUID / ISO 时间戳 / task_id / 10 位 Unix 秒。 */
    private static String mask(String s) {
        String out = TASK_ID.matcher(s).replaceAll("\"task_id\":\"<task>\"");
        out = UUID_VALUE.matcher(out).replaceAll("\"$1\":\"<uuid>\"");
        out = UUID_BARE.matcher(out).replaceAll("<uuid>");
        out = TS_VALUE.matcher(out).replaceAll("\"$1\":\"<ts>\"");
        out = EPOCH_TS.matcher(out).replaceAll("\"$1\":\"<epoch>\"");
        return out;
    }

    private String login(String email) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/auth/login")
                .contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"Passw0rd!\"}")).andReturn();
        Matcher m = TOKEN.matcher(raw(r));
        assertThat(m.find()).as("login 响应应含 token: " + raw(r)).isTrue();
        return m.group(1);
    }
}
