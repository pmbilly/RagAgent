package com.ragagent.knowledge.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
 * FAQ 模块契约测试（12+1 条路由）。golden：
 * scripts/record-faq-golden.sh（98 个 faq-* 文件）。
 *
 * <p>种子与录制脚本一致（KB 均为固定 hex id 的 SQL 直插——录制侧 KB 经 API 建出
 * 随机 id，掩码归一）：FKB1（faq + indexMode=question_only）/FKB2（faq 空库）/
 * FKB3（faq 无容器导入结果）/FKB4（document 类型）/FKB5（导入专用）；FK1 容器带
 * last_faq_import_result、FT1/FT2 标签（seq 965001/965002）、FE1..FE4（seq 970001..970004）。</p>
 *
 * <p><b>录制顺序影响状态</b>（UpdateEntry / similar-questions / fields / tags 先后改写
 * FE1..FE3 的 metadata）：每个 @Test 从同一播种出发，按录制顺序串完自己段落的前置变更
 * 再比对（与 KnowledgeOperationsContractTest 同款纪律）。{@code faq-upsert-running}
 * 不比：Java 实现直接落 failed 终态并释放 running key，而录制侧的中间态 running
 * 会持续数分钟（约定 §9 已记录的中间态差异），该文件仅作存档。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class FaqContractTest {

    private static final String BCRYPT =
            "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK";
    private static final long TENANT = 10002L;
    private static final String OWNER = "11111111-2222-3333-4444-555555555501";
    private static final String VIEWER = "11111111-2222-3333-4444-555555555504";
    private static final String CONTRIBUTOR = "11111111-2222-3333-4444-555555555505";
    private static final String OWNER_EMAIL = "faq-contract-owner@weknora.test";
    private static final String VIEWER_EMAIL = "faq-contract-viewer@weknora.test";
    private static final String CONTRA_EMAIL = "faq-contract-contrib@weknora.test";
    private static final String UNKNOWN = "11111111-2222-3333-4444-999999999999";

    private static final String FKB1 = "fa900001-0000-0000-0000-000000000001";
    private static final String FKB2 = "fa900001-0000-0000-0000-000000000002";
    private static final String FKB3 = "fa900001-0000-0000-0000-000000000003";
    private static final String FKB4 = "fa900001-0000-0000-0000-000000000004";
    private static final String FKB5 = "fa900001-0000-0000-0000-000000000005";
    private static final String CROSS_KB = "fa900001-0000-0000-0000-00000000c001";
    private static final String FK1 = "faa00001-0000-0000-0000-000000000001";
    private static final String FK3 = "faa00001-0000-0000-0000-000000000003";
    private static final String FT1 = "fab00001-0000-0000-0000-000000000001";
    private static final String FT2 = "fab00001-0000-0000-0000-000000000002";
    private static final String FE1 = "fbc00001-0000-0000-0000-000000000001";
    private static final String FE2 = "fbc00001-0000-0000-0000-000000000002";
    private static final String FE3 = "fbc00001-0000-0000-0000-000000000003";
    private static final String FE4 = "fbc00001-0000-0000-0000-000000000004";

    private static final String META1 = "{\"standardQuestion\":\"怎么 绑定 手机？\","
            + "\"similarQuestions\":[\"如何绑定手机\",\"How to bind phone\"],"
            + "\"negativeQuestions\":[\"怎么解绑手机\"],"
            + "\"answers\":[\"进入设置，选择设备，点击绑定。\"],\"answerStrategy\":\"all\","
            + "\"version\":1,\"source\":\"faq\"}";
    private static final String META2 = "{\"standardQuestion\":\"退货政策是什么\","
            + "\"answers\":[\"7天无理由退货\",\"质量问题15天内退\"],\"answerStrategy\":\"random\","
            + "\"version\":1,\"source\":\"faq\"}";
    private static final String META3 = "{\"standardQuestion\":\"如何退款\","
            + "\"similarQuestions\":[\"退款流程\"],\"answers\":[\"请参见帮助中心。\"],"
            + "\"answerStrategy\":\"all\",\"version\":1,\"source\":\"faq\"}";
    private static final String RESULT1 = "{\"totalEntries\":2,\"successCount\":1,"
            + "\"failedCount\":1,\"partialFailedCount\":0,\"skippedCount\":0,"
            + "\"mergedCount\":0,\"addedCount\":1,\"importMode\":\"append\","
            + "\"importedAt\":\"2026-09-01T08:00:00+08:00\",\"taskId\":\"faqgolden-seed\","
            + "\"displayStatus\":\"open\",\"processingTime\":5}";

    /** uuid 值（带键名）→ "<uuid>"；错误文案里的裸 uuid 也掩码。 */
    private static final Pattern UUID_VALUE = Pattern.compile(
            "\"([A-Za-z_]+)\":\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"");
    private static final Pattern UUID_BARE = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern TS_VALUE = Pattern.compile(
            "\"[2-9]\\d{3}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})\"");
    private static final Pattern TASK_ID = Pattern.compile("\"taskId\":\"[^\"]*\"");
    /** 进度对象的 epoch 秒（10 位）与 CSV URL（内嵌任务 id + 纳秒）。 */
    private static final Pattern EPOCH = Pattern.compile("\"([A-Za-z_]+)\":(1\\d{9})");
    private static final Pattern FAILED_URL = Pattern.compile("\"failedEntriesUrl\":\"[^\"]*\"");

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
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);

        Tenant tenant = new Tenant();
        tenant.setId(TENANT);
        tenant.setName("faq-contract-tenant");
        tenant.setStatus("active");
        tenantMapper.insert(tenant);
        Tenant crossTenant = new Tenant();
        crossTenant.setId(10000L);
        crossTenant.setName("billy-workspace");
        crossTenant.setStatus("active");
        tenantMapper.insert(crossTenant);

        seedUser(OWNER, "faqowner", OWNER_EMAIL, "owner");
        seedUser(VIEWER, "faqviewer", VIEWER_EMAIL, "viewer");
        seedUser(CONTRIBUTOR, "faqcontrib", CONTRA_EMAIL, "contributor");

        String strategy = "{\"vectorEnabled\":false,\"keywordEnabled\":false,"
                + "\"wikiEnabled\":false,\"graphEnabled\":false}";
        seedKb(FKB1, "faq-golden-kb", "faq",
                "{\"indexMode\":\"question_only\",\"questionIndexMode\":\"combined\"}", strategy);
        seedKb(FKB2, "faq-empty-kb", "faq", null, strategy);
        seedKb(FKB3, "faq-noresult-kb", "faq", null, strategy);
        seedKb(FKB4, "faq-doc-kb", "document", null, strategy);
        seedKb(FKB5, "faq-import-kb", "faq", null, strategy);
        jdbc.update("INSERT INTO knowledge_bases (id, name, tenant_id, type, creator_id, "
                + "summary_model_id, embedding_model_id) VALUES (?, 'faq-cross-tenant', 10000, 'faq', "
                + "'99999999-9999-9999-9999-999999999999', '', '')", CROSS_KB);

        // 容器（FK1 带导入结果；FK3 无）+ 标签 + FAQ chunk（created_at/updated_at 固定递减）
        jdbc.update("INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, "
                + "description, source, parse_status, enable_status, last_faq_import_result) VALUES "
                + "(?, ?, ?, 'faq', 'faq-golden-kb', 'FAQ 条目容器', 'faq', 'completed', 'enabled', ?)",
                FK1, TENANT, FKB1, RESULT1);
        jdbc.update("INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, "
                + "description, source, parse_status, enable_status) VALUES "
                + "(?, ?, ?, 'faq', 'faq-noresult-kb', 'FAQ 条目容器', 'faq', 'completed', 'enabled')",
                FK3, TENANT, FKB3);
        jdbc.update("INSERT INTO knowledge_tags (id, seq_id, tenant_id, knowledge_base_id, "
                + "name, color, sort_order) VALUES (?, 965001, ?, ?, '热门问题', '', 0)",
                FT1, TENANT, FKB1);
        jdbc.update("INSERT INTO knowledge_tags (id, seq_id, tenant_id, knowledge_base_id, "
                + "name, color, sort_order) VALUES (?, 965002, ?, ?, '售后', '', 0)",
                FT2, TENANT, FKB1);
        // dev PG 残留位：录制时 tag seq 960002 是 kg 波的 T2（别的 KB）——
        // fields/upsert 的"外来 tag"用例据此 403；H2 播一个同位标签
        jdbc.update("INSERT INTO knowledge_tags (id, seq_id, tenant_id, knowledge_base_id, "
                + "name, color, sort_order) VALUES ('bfb00001-0000-0000-0000-00000000e002', 960002, ?, "
                + "?, '外来库标签', '', 0)", TENANT, FKB4);
        jdbc.update("INSERT INTO chunks (id, seq_id, tenant_id, knowledge_id, knowledge_base_id, "
                + "content, chunk_type, tag_id, is_enabled, flags, status, chunk_index, start_at, "
                + "end_at, metadata, created_at, updated_at) VALUES (?, 970001, ?, ?, ?, "
                + "'Q: 怎么 绑定 手机？', 'faq', ?, TRUE, 1, 2, 0, 0, 0, ?, "
                + "'2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00')",
                FE1, TENANT, FK1, FKB1, FT1, META1);
        jdbc.update("INSERT INTO chunks (id, seq_id, tenant_id, knowledge_id, knowledge_base_id, "
                + "content, chunk_type, tag_id, is_enabled, flags, status, chunk_index, start_at, "
                + "end_at, metadata, created_at, updated_at) VALUES (?, 970002, ?, ?, ?, "
                + "'Q: 退货政策是什么', 'faq', NULL, TRUE, 0, 2, 0, 0, 0, ?, "
                + "'2026-09-01 07:00:00+00', '2026-09-01 07:00:00+00')",
                FE2, TENANT, FK1, FKB1, META2);
        jdbc.update("INSERT INTO chunks (id, seq_id, tenant_id, knowledge_id, knowledge_base_id, "
                + "content, chunk_type, tag_id, is_enabled, flags, status, chunk_index, start_at, "
                + "end_at, metadata, created_at, updated_at) VALUES (?, 970003, ?, ?, ?, "
                + "'Q: 如何退款', 'faq', ?, FALSE, 1, 2, 0, 0, 0, ?, "
                + "'2026-09-01 06:00:00+00', '2026-09-01 06:00:00+00')",
                FE3, TENANT, FK1, FKB1, FT2, META3);
        jdbc.update("INSERT INTO chunks (id, seq_id, tenant_id, knowledge_id, knowledge_base_id, "
                + "content, chunk_type, tag_id, is_enabled, flags, status, chunk_index, start_at, "
                + "end_at, metadata, created_at, updated_at) VALUES (?, 970004, ?, ?, ?, "
                + "'裸内容条目', 'faq', NULL, TRUE, 1, 2, 0, 0, 0, NULL, "
                + "'2026-09-01 05:00:00+00', '2026-09-01 05:00:00+00')",
                FE4, TENANT, FK1, FKB1);

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

    private void seedKb(String id, String name, String type, String faqConfig, String strategy) {
        jdbc.update("INSERT INTO knowledge_bases (id, name, tenant_id, type, description, "
                + "creator_id, summary_model_id, embedding_model_id, indexing_strategy, faq_config) "
                + "VALUES (?, ?, ?, ?, 'faq golden 专用', ?, '', '', ?, ?)",
                id, name, TENANT, type, OWNER, strategy, faqConfig);
    }

    private String login(String email) {
        try {
            MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType("application/json")
                            .content("{\"email\":\"" + email + "\",\"password\":\"Passw0rd!\"}"))
                    .andReturn();
            com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(result.getResponse().getContentAsString());
            return node.get("token").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── 请求与断言辅助 ──────────────────────────────────────────────────

    private String call(String method, String path, String auth, String body) throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder =
                switch (method) {
                    case "GET" -> get(path);
                    case "POST" -> post(path);
                    case "PUT" -> put(path);
                    case "DELETE" -> delete(path);
                    default -> throw new IllegalArgumentException(method);
                };
        if (auth != null) {
            builder.header("Authorization", auth);
        }
        if (body != null) {
            builder.contentType("application/json").content(body);
        }
        return mockMvc.perform(builder).andReturn().getResponse().getContentAsString(
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private int status(String method, String path, String auth, String body) throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder =
                switch (method) {
                    case "GET" -> get(path);
                    case "POST" -> post(path);
                    case "PUT" -> put(path);
                    case "DELETE" -> delete(path);
                    default -> throw new IllegalArgumentException(method);
                };
        if (auth != null) {
            builder.header("Authorization", auth);
        }
        if (body != null) {
            builder.contentType("application/json").content(body);
        }
        return mockMvc.perform(builder).andReturn().getResponse().getStatus();
    }

    private void compare(String golden, String actual) throws Exception {
        com.ragagent.support.GoldenContract.assertEquals("src/test/resources/contracts",
                golden, FaqContractTest::mask, actual);
    }

    private void compareAndStatus(String golden, int expectedStatus, String method, String path,
                                  String auth, String body) throws Exception {
        MvcResult result = perform(method, path, auth, body);
        assertEquals(expectedStatus, result.getResponse().getStatus(),
                () -> {
                    try {
                        return golden + " status, body=" + mask(result.getResponse()
                                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
                    } catch (Exception e) {
                        return golden + " status";
                    }
                });
        compare(golden, result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private MvcResult perform(String method, String path, String auth, String body) throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder =
                switch (method) {
                    case "GET" -> get(path);
                    case "POST" -> post(path);
                    case "PUT" -> put(path);
                    case "DELETE" -> delete(path);
                    default -> throw new IllegalArgumentException(method);
                };
        if (auth != null) {
            builder.header("Authorization", auth);
        }
        if (body != null) {
            builder.contentType("application/json").content(body);
        }
        return mockMvc.perform(builder).andReturn();
    }

    private static String mask(String s) {
        s = UUID_VALUE.matcher(s).replaceAll("\"$1\":\"<uuid>\"");
        s = TASK_ID.matcher(s).replaceAll("\"taskId\":\"<task>\"");
        s = FAILED_URL.matcher(s).replaceAll("\"failedEntriesUrl\":\"<url>\"");
        s = UUID_BARE.matcher(s).replaceAll("<uuid>");
        s = TS_VALUE.matcher(s).replaceAll("\"<ts>\"");
        s = EPOCH.matcher(s).replaceAll("\"$1\":<epoch>");
        return s;
    }

    private static final String API = "/api/v1";
    private static final String B1 = API + "/knowledge-bases/" + FKB1;
    private static final String B2 = API + "/knowledge-bases/" + FKB2;
    private static final String B3 = API + "/knowledge-bases/" + FKB3;
    private static final String B4 = API + "/knowledge-bases/" + FKB4;

    // ── 1) 列表与守卫 ──────────────────────────────────────────────────

    @Test
    void section1_listAndGuards() throws Exception {
        compareAndStatus("faq-list-empty.json", 200, "GET", B2 + "/faq/entries", owner, null);
        compareAndStatus("faq-list-badpage.json", 400, "GET", B1 + "/faq/entries?page=abc", owner, null);
        compareAndStatus("faq-list-negpage.json", 400, "GET", B1 + "/faq/entries?page=-1", owner, null);
        compareAndStatus("faq-list-bigsize.json", 400, "GET", B1 + "/faq/entries?pageSize=1001", owner, null);
        compareAndStatus("faq-list-page0.json", 200, "GET", B1 + "/faq/entries?page=0&pageSize=2", owner, null);
        compareAndStatus("faq-list-paged.json", 200, "GET", B1 + "/faq/entries?page=2&pageSize=2", owner, null);
        compareAndStatus("faq-list-badtag.json", 400, "GET", B1 + "/faq/entries?tagId=abc", owner, null);
        compareAndStatus("faq-list-badenabled.json", 400, "GET", B1 + "/faq/entries?isEnabled=xyz", owner, null);
        compareAndStatus("faq-list-enabled-false.json", 200, "GET", B1 + "/faq/entries?isEnabled=false", owner, null);
        compareAndStatus("faq-list-tag.json", 200, "GET", B1 + "/faq/entries?tagId=965001", owner, null);
        compareAndStatus("faq-list-taguuid.json", 200, "GET", B1 + "/faq/entries?tagIds=" + FT2, owner, null);
        compareAndStatus("faq-list-untagged.json", 200, "GET",
                B1 + "/faq/entries?tagIds=__untagged__," + FT2, owner, null);
        compareAndStatus("faq-list-sortasc.json", 200, "GET", B1 + "/faq/entries?sortOrder=asc", owner, null);
        compareAndStatus("faq-list-notkb.json", 404, "GET", API + "/knowledge-bases/" + UNKNOWN + "/faq/entries", owner, null);
        compareAndStatus("faq-list-cross.json", 403, "GET", API + "/knowledge-bases/" + CROSS_KB + "/faq/entries", owner, null);
        compareAndStatus("faq-list-noauth.json", 401, "GET", B1 + "/faq/entries", null, null);
        compareAndStatus("faq-list-viewer.json", 200, "GET", B1 + "/faq/entries", viewer, null);
        compareAndStatus("faq-create-contrib.json", 403, "POST", B1 + "/faq/entry", contrib,
                "{\"standardQuestion\":\"贡献者创建\",\"answers\":[\"答案\"]}");
        compareAndStatus("faq-fields-viewer.json", 403, "PUT", B1 + "/faq/entries/fields", viewer,
                "{\"byId\":{\"970001\":{\"enabled\":false}}}");
    }

    // ── 2) 详情 ────────────────────────────────────────────────────────

    @Test
    void section2_getEntry() throws Exception {
        compareAndStatus("faq-get.json", 200, "GET", B1 + "/faq/entries/970001", owner, null);
        compareAndStatus("faq-get-noentry.json", 404, "GET", B1 + "/faq/entries/999999", owner, null);
        compareAndStatus("faq-get-badid.json", 400, "GET", B1 + "/faq/entries/abc", owner, null);
        compareAndStatus("faq-get-wrongkb.json", 404, "GET", B2 + "/faq/entries/970001", owner, null);
    }

    // ── 3) 创建 ────────────────────────────────────────────────────────

    @Test
    void section3_create() throws Exception {
        compareAndStatus("faq-create-empty.json", 400, "POST", B1 + "/faq/entry", owner, "");
        compareAndStatus("faq-create-nullbody.json", 400, "POST", B1 + "/faq/entry", owner, "null");
        compareAndStatus("faq-create-noquestion.json", 400, "POST", B1 + "/faq/entry", owner,
                "{\"standardQuestion\":\"\",\"answers\":[\"答案\"]}");
        compareAndStatus("faq-create-noanswer.json", 400, "POST", B1 + "/faq/entry", owner,
                "{\"standardQuestion\":\"问题\"}");
        compareAndStatus("faq-create-badstrategy.json", 400, "POST", B1 + "/faq/entry", owner,
                "{\"standardQuestion\":\"问题\",\"answers\":[\"答案\"],\"answerStrategy\":\"bogus\"}");
        compareAndStatus("faq-create-simileq.json", 400, "POST", B1 + "/faq/entry", owner,
                "{\"standardQuestion\":\"问题A\",\"similarQuestions\":[\"问题A\"],\"answers\":[\"答案\"]}");
        compareAndStatus("faq-create-simidup.json", 400, "POST", B1 + "/faq/entry", owner,
                "{\"standardQuestion\":\"问题B\",\"similarQuestions\":[\"重复问\",\"重复问\"],\"answers\":[\"答案\"]}");
        compareAndStatus("faq-create-badtag.json", 500, "POST", B1 + "/faq/entry", owner,
                "{\"standardQuestion\":\"问题C\",\"answers\":[\"答案\"],\"tagId\":960999}");
        compareAndStatus("faq-create-ghosttag.json", 500, "POST", B1 + "/faq/entry", owner,
                "{\"standardQuestion\":\"问题D\",\"answers\":[\"答案\"],\"tagName\":\"没人建过这分类\"}");
        compareAndStatus("faq-create-dupstd.json", 400, "POST", B1 + "/faq/entry", owner,
                "{\"standardQuestion\":\"退货政策是什么\",\"answers\":[\"答案\"]}");
        compareAndStatus("faq-create-dupsim.json", 400, "POST", B1 + "/faq/entry", owner,
                "{\"standardQuestion\":\"全新问题\",\"similarQuestions\":[\"如何绑定手机\"],\"answers\":[\"答案\"]}");
        compareAndStatus("faq-create-500.json", 500, "POST", B1 + "/faq/entry", owner,
                "{\"standardQuestion\":\"怎么绑定手机？\",\"answers\":[\"见正文\"]}");
        compareAndStatus("faq-create-wrongkb-type.json", 400, "POST", B4 + "/faq/entry", owner,
                "{\"standardQuestion\":\"问题\",\"answers\":[\"答案\"]}");
    }

    // ── 4) 更新（先落库后 500） ─────────────────────────────────────────

    @Test
    void section4_update() throws Exception {
        compareAndStatus("faq-update-badid.json", 400, "PUT", B1 + "/faq/entries/abc", owner,
                "{\"standardQuestion\":\"问题\",\"answers\":[\"答案\"]}");
        compareAndStatus("faq-update-404.json", 404, "PUT", B1 + "/faq/entries/999999", owner,
                "{\"standardQuestion\":\"问题\",\"answers\":[\"答案\"]}");
        compareAndStatus("faq-update-noanswer.json", 400, "PUT", B1 + "/faq/entries/970004", owner,
                "{\"standardQuestion\":\"换一个问题\"}");
        compareAndStatus("faq-update-500.json", 500, "PUT", B1 + "/faq/entries/970002", owner,
                "{\"standardQuestion\":\"退货政策是什么流程\",\"answers\":[\"7天无理由退货\","
                        + "\"质量问题15天内退\",\"运费险说明\"],\"recommended\":true}");
        compareAndStatus("faq-get-after-update.json", 200, "GET", B1 + "/faq/entries/970002", owner, null);
    }

    // ── 5) 相似问（先落库后 500） ───────────────────────────────────────

    @Test
    void section5_addSimilar() throws Exception {
        compareAndStatus("faq-similar-empty.json", 400, "POST",
                B1 + "/faq/entries/970001/similar-questions", owner, "{\"similarQuestions\":[]}");
        compareAndStatus("faq-similar-nometa.json", 400, "POST",
                B1 + "/faq/entries/970004/similar-questions", owner, "{\"similarQuestions\":[\"新问题\"]}");
        compareAndStatus("faq-similar-404.json", 404, "POST",
                B1 + "/faq/entries/999999/similar-questions", owner, "{\"similarQuestions\":[\"新问题\"]}");
        compareAndStatus("faq-similar-500.json", 500, "POST",
                B1 + "/faq/entries/970001/similar-questions", owner,
                "{\"similarQuestions\":[\"在线绑定入口在哪里\"]}");
        compareAndStatus("faq-get-after-similar.json", 200, "GET", B1 + "/faq/entries/970001", owner, null);
    }

    // ── 6) 批量字段 ────────────────────────────────────────────────────

    @Test
    void section6_fieldsBatch() throws Exception {
        compareAndStatus("faq-fields-empty.json", 204, "PUT", B1 + "/faq/entries/fields", owner, "{}");
        compareAndStatus("faq-fields-negid.json", 400, "PUT", B1 + "/faq/entries/fields", owner,
                "{\"byId\":{\"-5\":{\"enabled\":false}}}");
        compareAndStatus("faq-fields-missing.json", 404, "PUT", B1 + "/faq/entries/fields", owner,
                "{\"byId\":{\"999999\":{\"enabled\":false}}}");
        compareAndStatus("faq-fields-badtag.json", 404, "PUT", B1 + "/faq/entries/fields", owner,
                "{\"byId\":{\"970003\":{\"tagId\":960999}}}");
        compareAndStatus("faq-fields-foreigntag.json", 403, "PUT", B1 + "/faq/entries/fields", owner,
                "{\"byId\":{\"970003\":{\"tagId\":960002}}}");
        compareAndStatus("faq-fields-ok.json", 204, "PUT", B1 + "/faq/entries/fields", owner,
                "{\"byId\":{\"970003\":{\"enabled\":true,\"recommended\":false,\"tagId\":965001}},"
                        + "\"byTag\":{\"965002\":{\"enabled\":true,\"tagId\":965001}},"
                        + "\"excludeIds\":[970001]}");
        compareAndStatus("faq-get-after-fields.json", 200, "GET", B1 + "/faq/entries/970003", owner, null);
    }

    // ── 7) 批量标签 ────────────────────────────────────────────────────

    @Test
    void section7_tagBatch() throws Exception {
        compareAndStatus("faq-tags-empty.json", 400, "PUT", B1 + "/faq/entries/tags", owner,
                "{\"updates\":{}}");
        compareAndStatus("faq-tags-nullbody.json", 400, "PUT", B1 + "/faq/entries/tags", owner, "null");
        compareAndStatus("faq-tags-missing.json", 404, "PUT", B1 + "/faq/entries/tags", owner,
                "{\"updates\":{\"999999\":965001}}");
        compareAndStatus("faq-tags-ok.json", 204, "PUT", B1 + "/faq/entries/tags", owner,
                "{\"updates\":{\"970002\":965001,\"970003\":null}}");
    }

    // ── 8) 导入（binding / tag 校验 / dry_run / 进度） ──────────────────

    @Test
    void section8_import() throws Exception {
        compareAndStatus("faq-upsert-noentries.json", 400, "POST", B1 + "/faq/entries", owner,
                "{\"entries\":[]}");
        compareAndStatus("faq-upsert-badmode.json", 400, "POST", B1 + "/faq/entries", owner,
                "{\"entries\":[{\"standardQuestion\":\"问题\",\"answers\":[\"答案\"]}],\"mode\":\"bogus\"}");
        compareAndStatus("faq-upsert-badtask.json", 400, "POST", B1 + "/faq/entries", owner,
                "{\"entries\":[{\"standardQuestion\":\"问题\",\"answers\":[\"答案\"]}],\"taskId\":\"bad/id\"}");
        compareAndStatus("faq-upsert-nomode.json", 400, "POST", B1 + "/faq/entries", owner,
                "{\"entries\":[{\"standardQuestion\":\"问题\",\"answers\":[\"答案\"]}]}");
        compareAndStatus("faq-upsert-foreigntag.json", 403, "POST", B2 + "/faq/entries", owner,
                "{\"entries\":[{\"standardQuestion\":\"问题\",\"answers\":[\"答案\"],\"tagId\":960002}],"
                        + "\"mode\":\"append\"}");
        compareAndStatus("faq-upsert-ghosttag.json", 404, "POST", B1 + "/faq/entries", owner,
                "{\"entries\":[{\"standardQuestion\":\"问题\",\"answers\":[\"答案\"],\"tagId\":960999}],"
                        + "\"mode\":\"append\"}");

        String dryRunBody = "{\"entries\":[{\"standardQuestion\":\"怎么 绑定 手机？\",\"answers\":[\"重复的标准问\"]},"
                + "{\"standardQuestion\":\"\",\"answers\":[]},"
                + "{\"standardQuestion\":\"全新问题\",\"similarQuestions\":[\"如何绑定手机\","
                + "\"如何绑定手机\"],\"answers\":[\"新答案\"],\"answerStrategy\":\"random\"}],"
                + "\"mode\":\"append\",\"dryRun\":true}";
        MvcResult dryRunResult = perform("POST", B1 + "/faq/entries", owner, dryRunBody);
        assertEquals(202, dryRunResult.getResponse().getStatus(), "faq-upsert-dryrun.json status");
        compare("faq-upsert-dryrun.json",
                dryRunResult.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        String dryTask = com.jayway.jsonpath.JsonPath.parse(
                dryRunResult.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .read("$.taskId");

        // dry_run 异步（进程内虚拟线程）：轮询到 completed（对照录制脚本的 sleep 3）再比对终态；
        // completed 才清 running key——后续 customtask 才能不被锁
        String progress;
        for (int i = 0; ; i++) {
            progress = call("GET", API + "/faq/import/progress/" + dryTask, owner, null);
            if (progress.contains("\"status\":\"completed\"") || progress.contains("\"status\":\"failed\"")
                    || i >= 100) {
                break;
            }
            Thread.sleep(100);
        }
        compare("faq-progress-dryrun.json", progress);

        // 自定义 task_id：通过 ValidateTaskID、非 dry_run —— 进度查询恒 400（id 无租户段）。
        // 该任务在 Go 卡在 asynq 重试窗口（running key 被占数分钟）；Java 直落 failed 并释放
        // ——faq-upsert-running 的 running-key 窗口是 Go 重试语义的存档，Java 不比。
        compareAndStatus("faq-upsert-customtask.json", 202, "POST", B1 + "/faq/entries", owner,
                "{\"entries\":[{\"standardQuestion\":\"自定义任务ID问题\",\"answers\":[\"答案\"]}],"
                        + "\"mode\":\"append\",\"taskId\":\"faqgolden_custom_1\"}");

        compareAndStatus("faq-progress-badid.json", 400, "GET",
                API + "/faq/import/progress/not_a_task", owner, null);
        compareAndStatus("faq-progress-crosstenant.json", 404, "GET",
                API + "/faq/import/progress/faq_import_1_1700000000000_abc12345", owner, null);
        compareAndStatus("faq-progress-customtask.json", 400, "GET",
                API + "/faq/import/progress/faqgolden_custom_1", owner, null);
    }

    // ── 9) 导入结果显示状态 ────────────────────────────────────────────

    @Test
    void section9_displayStatus() throws Exception {
        compareAndStatus("faq-display-bad.json", 400, "PUT",
                B1 + "/faq/import/last-result/display", owner, "{\"displayStatus\":\"bogus\"}");
        compareAndStatus("faq-display-nokb.json", 404, "PUT",
                B2 + "/faq/import/last-result/display", owner, "{\"displayStatus\":\"close\"}");
        compareAndStatus("faq-display-noresult.json", 404, "PUT",
                B3 + "/faq/import/last-result/display", owner, "{\"displayStatus\":\"close\"}");
        compareAndStatus("faq-display-close.json", 204, "PUT",
                B1 + "/faq/import/last-result/display", owner, "{\"displayStatus\":\"close\"}");
        compareAndStatus("faq-display-open.json", 204, "PUT",
                B1 + "/faq/import/last-result/display", owner, "{\"displayStatus\":\"open\"}");
    }

    // ── 10) 导出（依赖 4-7 的变更，先重放） ─────────────────────────────

    @Test
    void section10_export() throws Exception {
        replayMutations();
        compareAndStatus("faq-export-csv.body", 200, "GET", B1 + "/faq/entries/export", owner, null);
        compareAndStatus("faq-export-json.body", 200, "GET", B1 + "/faq/entries/export?format=json", owner, null);
        compareAndStatus("faq-export-empty-csv.body", 200, "GET", B2 + "/faq/entries/export", owner, null);
        compareAndStatus("faq-export-empty-json.body", 200, "GET", B2 + "/faq/entries/export?format=json", owner, null);
        assertExportHeaders(B1 + "/faq/entries/export", "text/csv; charset=utf-8",
                "attachment; filename=faq_export.csv");
        assertExportHeaders(B1 + "/faq/entries/export?format=json", "application/json; charset=utf-8",
                "attachment; filename=faq_export.json");
    }

    private void assertExportHeaders(String path, String contentType, String disposition) throws Exception {
        var response = perform("GET", path, owner, null).getResponse();
        assertEquals(contentType, response.getHeader("Content-Type"));
        assertEquals(disposition, response.getHeader("Content-Disposition"));
    }

    // ── 11) 搜索 ───────────────────────────────────────────────────────

    @Test
    void section11_search() throws Exception {
        compareAndStatus("faq-search-empty-query.json", 400, "POST", B1 + "/faq/search", owner,
                "{\"queryText\":\"\"}");
        compareAndStatus("faq-search-noquery.json", 400, "POST", B1 + "/faq/search", owner, "{}");
        compareAndStatus("faq-search-embed-missing.json", 200, "POST", B1 + "/faq/search", owner,
                "{\"queryText\":\"怎么绑定手机\",\"matchCount\":5}");
        compareAndStatus("faq-search-kw-std.json", 200, "POST", B1 + "/faq/search", owner,
                "{\"queryText\":\"退货\",\"matchCount\":5}");
        compareAndStatus("faq-search-notkb.json", 400, "POST", B4 + "/faq/search", owner,
                "{\"queryText\":\"退货\"}");
    }

    // ── 12) 删除（先删后 500） ──────────────────────────────────────────

    @Test
    void section12_delete() throws Exception {
        replayMutations();
        compareAndStatus("faq-delete-empty.json", 400, "DELETE", B1 + "/faq/entries", owner,
                "{\"ids\":[]}");
        compareAndStatus("faq-delete-missing.json", 404, "DELETE", B1 + "/faq/entries", owner,
                "{\"ids\":[999999]}");
        compareAndStatus("faq-delete-500.json", 500, "DELETE", B1 + "/faq/entries", owner,
                "{\"ids\":[970004]}");
        compareAndStatus("faq-get-after-delete.json", 404, "GET", B1 + "/faq/entries/970004", owner, null);
        compareAndStatus("faq-list-final.json", 200, "GET", B1 + "/faq/entries", owner, null);
    }

    // ── 13) 非 dry_run 导入（只比立即响应；worker 终态差异已记录） ────────

    @Test
    void section13_importRun() throws Exception {
        compareAndStatus("faq-upsert-import.json", 202, "POST", API + "/knowledge-bases/" + FKB5 + "/faq/entries",
                owner, "{\"entries\":[{\"standardQuestion\":\"导入一条\",\"answers\":[\"答案\"]}],\"mode\":\"append\"}");
    }

    /** 对照录制顺序重放 4-7 段的变更（响应不比，各自的 @Test 已钉住）。 */
    private void replayMutations() throws Exception {
        call("PUT", B1 + "/faq/entries/970002", owner,
                "{\"standardQuestion\":\"退货政策是什么流程\",\"answers\":[\"7天无理由退货\","
                        + "\"质量问题15天内退\",\"运费险说明\"],\"recommended\":true}");
        call("POST", B1 + "/faq/entries/970001/similar-questions", owner,
                "{\"similarQuestions\":[\"在线绑定入口在哪里\"]}");
        call("PUT", B1 + "/faq/entries/fields", owner,
                "{\"byId\":{\"970003\":{\"enabled\":true,\"recommended\":false,\"tagId\":965001}},"
                        + "\"byTag\":{\"965002\":{\"enabled\":true,\"tagId\":965001}},"
                        + "\"excludeIds\":[970001]}");
        call("PUT", B1 + "/faq/entries/tags", owner,
                "{\"updates\":{\"970002\":965001,\"970003\":null}}");
    }
}
