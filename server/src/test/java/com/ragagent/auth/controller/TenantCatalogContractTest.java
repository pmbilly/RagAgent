package com.ragagent.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 跨空间租户目录 + KV 配置分发器契约测试（波 2 扫尾批 3，5 条路由）。
 * golden：record-ct-golden.sh（67 条 ct-*，全部 Go 实录；本类覆盖 flag-on 的
 * H2 自足部分，list/search 成功路径留给 A/B——依赖 dev DB 租户清单；
 * prompt-templates GET 已于走查补翻落地，双端逐字节一致，见下「已知不录」）。
 *
 * <p>种子严格复刻录制脚本：租户 10002 + 四人（ct-super 跨空间超管 /
 * ct-viewer / ct-self / javasysadmin 系统管理员），密码全部 Passw0rd!
 * （bcrypt 常量），成员行 joined_at 固定。</p>
 *
 * <p>录制顺序影响状态：创建族 / 每个 KV key 族各自在单个 @Test 内按录制
 * 顺序串完（@BeforeEach 重播种）。KV 族先由 ct-self 自助创建 alpha 租户
 * （响应不比 golden，只取 id），X-Tenant-ID 切过去再打 KV。</p>
 *
 * <p>掩码（两侧同掩码）：租户/设置行的数字 id、UUID（default_storage_backend_id、
 * knowledge_base_id、last_modified_by、embedding_model_id）、时间戳、
 * api_key 明文、parser SSRF 错误里的解析 IP（fake-ip 段每次解析可能不同）。</p>
 *
 * <p><b>已知不录</b>：parser 三条 PUT 全是 SSRF 失败路径（无成功路径 golden，
 * 见 docs §9）。GET prompt-templates 的推迟已随走查补翻清零——Java 侧
 * PromptTemplateCatalog 装载 vendored yaml + LocalizeTemplates 本地化，
 * 本类直接对 ct-kv-get-prompt-templates.json（Go 实录）断言 200 逐字节一致。</p>
 */
@SpringBootTest(properties = "weknora.tenant.enable-cross-tenant-access=true")
@AutoConfigureMockMvc
class TenantCatalogContractTest {

    private static final String BCRYPT =
            "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK";
    private static final long TENANT = 10002L;
    private static final String SYS_ID = "11111111-2222-3333-4444-555555555701";
    private static final String SUPER_ID = "11111111-2222-3333-4444-555555555702";
    private static final String VIEWER_ID = "11111111-2222-3333-4444-555555555703";
    private static final String SELF_ID = "11111111-2222-3333-4444-555555555704";
    private static final String SYS_EMAIL = "java-sys-admin@weknora.test";
    private static final String SUPER_EMAIL = "ct-super@weknora.test";
    private static final String VIEWER_EMAIL = "ct-viewer@weknora.test";
    private static final String SELF_EMAIL = "ct-self@weknora.test";
    /** 录制脚本用的 dev DB 真实 embedding 模型 id（字面量复刻进请求体） */
    private static final String EMBEDDING_MODEL = "9aa07763-6b4f-4152-9f2b-3ad0e2d2f69f";

    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern DATA_ID = Pattern.compile("\"id\":(\\d+)");
    private static final Pattern UUID_VALUE = Pattern.compile(
            "\"([a-zA-Z_]+)\":\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"");
    private static final Pattern TS_VALUE = Pattern.compile(
            "\"([a-zA-Z_]+)\":\"[2-9]\\d{3}-\\d{2}-\\d{2}T[0-9:.+\\-Z]+\"");
    private static final Pattern API_KEY = Pattern.compile("\"api_key\":\"[^\"]*\"");
    // DNS 是否解析随环境而变（录制机 fake-IP DNS 返回 198.18/15；离线环境解析失败），
    // 两种都是 SSRF 拒绝，统一归一化到 <ssrf-host> 避免环境依赖。
    private static final Pattern SSRF_DNS_PREFIX =
            Pattern.compile("DNS resolution failed for hostname [a-z0-9.-]+example\\.com[^\"]*");
    private static final Pattern SSRF_HOSTNAME_TAIL =
            Pattern.compile("hostname [a-z0-9.-]+example\\.com[^\"]*");

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

    private String superTok;
    private String viewerTok;
    private String selfTok;
    private String sysTok;

    @BeforeEach
    void seed() throws Exception {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);

        Tenant tenant = new Tenant();
        tenant.setId(TENANT);
        tenant.setName("phase1-test-tenant");
        tenant.setStatus("active");
        tenantMapper.insert(tenant);

        seedUser(SYS_ID, "javasysadmin", SYS_EMAIL, false, true);
        seedUser(SUPER_ID, "ct-super", SUPER_EMAIL, true, false);
        seedUser(VIEWER_ID, "ct-viewer", VIEWER_EMAIL, false, false);
        seedUser(SELF_ID, "ct-self", SELF_EMAIL, false, false);

        seedMember(SYS_ID, TENANT, "owner");
        seedMember(SUPER_ID, TENANT, "owner");
        seedMember(VIEWER_ID, TENANT, "viewer");
        seedMember(SELF_ID, TENANT, "viewer");

        superTok = "Bearer " + login(SUPER_EMAIL);
        viewerTok = "Bearer " + login(VIEWER_EMAIL);
        selfTok = "Bearer " + login(SELF_EMAIL);
        sysTok = "Bearer " + login(SYS_EMAIL);
    }

    private void seedUser(String id, String username, String email,
                          boolean crossTenant, boolean sysAdmin) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(BCRYPT);
        user.setTenantId(TENANT);
        user.setIsActive(true);
        user.setCanAccessAllTenants(crossTenant);
        user.setIsSystemAdmin(sysAdmin);
        user.setPreferences(new UserPreferences());
        userMapper.insert(user);
    }

    private void seedMember(String userId, long tenantId, String role) {
        TenantMember member = new TenantMember();
        member.setUserId(userId);
        member.setTenantId(tenantId);
        member.setRole(role);
        member.setStatus("active");
        member.setJoinedAt(OffsetDateTime.parse("2026-09-01T10:03:00Z"));
        memberMapper.insert(member);
    }

    // ════════════════ 1) 创建族（录制脚本 §1-5，严格按序） ════════════════

    @Test
    void createFamilyMatchesGo() throws Exception {
        // §1 自助创建成功（alpha）+ binding 家族 + 空名 500
        MvcResult self = mockMvc.perform(jsonBody(post("/api/v1/tenants"), selfTok,
                "{\"name\":\"ct-alpha\",\"description\":\"alpha workspace\"}")).andReturn();
        assertEquals(201, self.getResponse().getStatus(), raw(self));
        assertEquals(mask(golden("ct-create-self.json")), mask(raw(self)));
        long alphaId = extractId(raw(self));

        assertGolden(jsonBody(post("/api/v1/tenants"), selfTok, "{}"),
                400, "ct-create-binding-empty.json");
        assertGolden(jsonBody(post("/api/v1/tenants"), selfTok,
                "{\"name\":\"" + "n".repeat(129) + "\"}"),
                400, "ct-create-binding-longname.json");
        assertGolden(jsonBody(post("/api/v1/tenants"), selfTok,
                "{\"name\":\"ok-name\",\"description\":\"" + "d".repeat(513) + "\"}"),
                400, "ct-create-binding-longdesc.json");
        // 空 body：contentType 带着但无内容（对照录制脚本的 -H 不带 -d）
        assertGolden(post("/api/v1/tenants").header("Authorization", selfTok)
                        .contentType("application/json"),
                400, "ct-create-empty-body.json");
        assertGolden(jsonBody(post("/api/v1/tenants"), selfTok,
                "{\"name\":\"  \",\"description\":\"\"}"),
                500, "ct-create-wsname-500.json");

        // §2 超管全字段路径（status 被 service 恒写 active；storageQuota 透传 12345）
        assertMasked(jsonBody(post("/api/v1/tenants"), superTok,
                "{\"name\":\"ct-beta\",\"description\":\"beta workspace\","
                        + "\"storageQuota\":12345,\"status\":\"suspended\"}"),
                201, "ct-create-superuser.json");
        assertGolden(jsonBody(post("/api/v1/tenants"), superTok,
                "{\"name\":\"\",\"description\":\"x\"}"),
                500, "ct-create-super-noname.json");

        // §3 配额 429（cap=1，ct-self 已 owns alpha）→ DELETE 还原
        assertMasked(jsonBody(put("/api/v1/system/admin/settings/tenant.max_owned_per_user"),
                sysTok, "{\"value\":\"1\"}"), 200, "ct-set-quota.json");
        assertGolden(jsonBody(post("/api/v1/tenants"), selfTok,
                "{\"name\":\"ct-over-quota\"}"), 429, "ct-create-quota-429.json");
        assertGolden(delete("/api/v1/system/admin/settings/tenant.max_owned_per_user")
                        .header("Authorization", sysTok), 204, "ct-unset-quota.json");

        // §4 self-service 关停 403（code 2005）→ 还原
        assertMasked(jsonBody(put("/api/v1/system/admin/settings/tenant.self_service_creation_enabled"),
                sysTok, "{\"value\":false}"), 200, "ct-set-noss.json");
        assertGolden(jsonBody(post("/api/v1/tenants"), selfTok,
                "{\"name\":\"ct-blocked\"}"), 403, "ct-create-disabled.json");
        assertGolden(delete("/api/v1/system/admin/settings/tenant.self_service_creation_enabled")
                        .header("Authorization", sysTok), 204, "ct-unset-noss.json");

        // §5 auto_create_api_key 兼容路径（data.api_key 明文，掩码）→ 还原
        assertMasked(jsonBody(put("/api/v1/system/admin/settings/tenant.auto_create_api_key"),
                sysTok, "{\"value\":true}"), 200, "ct-set-autokey.json");
        assertMasked(jsonBody(post("/api/v1/tenants"), selfTok,
                "{\"name\":\"ct-gamma\",\"description\":\"gamma workspace\"}"),
                201, "ct-create-apikey.json");
        assertGolden(delete("/api/v1/system/admin/settings/tenant.auto_create_api_key")
                        .header("Authorization", sysTok), 204, "ct-unset-autokey.json");
    }


    // ════════════════ 3) KV 分发器：unsupported / prompt-templates / 权限门 ═

    @Test
    void kvDispatchAndAccessMatchGo() throws Exception {
        long alpha = createAlpha();
        seedMember(VIEWER_ID, alpha, "viewer"); // 录制脚本 SQL 直种，避开成员 API

        assertGolden(get("/api/v1/tenants/kv/bogus-key")
                        .header("Authorization", selfTok).header("X-Tenant-ID", alpha),
                400, "ct-kv-unsupported.json");
        // PUT prompt-templates：Go 分发器本来就没有它 → 两侧都 400
        assertGolden(jsonBody(put("/api/v1/tenants/kv/prompt-templates"), selfTok, "{}")
                        .header("X-Tenant-ID", alpha),
                400, "ct-kv-put-prompt-templates.json");
        // GET prompt-templates：走查补翻后双端 200 逐字节一致（zh-CN/en-US/ja-JP/空头
        // 四组 A/B 全 MATCH，见 docs §9「波 2 扫尾批 3」更新）。golden 是 Go 实录。
        assertGolden(get("/api/v1/tenants/kv/prompt-templates")
                        .header("Authorization", selfTok).header("X-Tenant-ID", alpha),
                200, "ct-kv-get-prompt-templates.json");

        // viewer 打敏感 key → 403；retrieval-config 非敏感 → viewer 可读（零值）
        assertGolden(get("/api/v1/tenants/kv/web-search-config")
                        .header("Authorization", viewerTok).header("X-Tenant-ID", alpha),
                403, "ct-kv-secret-viewer.json");
        assertGolden(get("/api/v1/tenants/kv/retrieval-config")
                        .header("Authorization", viewerTok).header("X-Tenant-ID", alpha),
                200, "ct-kv-ret-get-viewer.json");
    }

    // ════════════════ 4) KV web-search-config ════════════════

    @Test
    void kvWebSearchMatchesGo() throws Exception {
        long alpha = createAlpha();
        assertGolden(kvGet("web-search-config", alpha), 200, "ct-kv-ws-get-default.json");
        assertGolden(kvPut("web-search-config", alpha,
                "{\"max_results\":5,\"include_date\":true,\"compression_method\":\"summary\","
                        + "\"blacklist\":[\"bad.com\"],\"api_key\":\"ak-secret-123\","
                        + "\"proxy_url\":\"http://proxy.local:8080\"}"),
                200, "ct-kv-ws-put.json");
        assertGolden(kvGet("web-search-config", alpha), 200, "ct-kv-ws-get-after.json");
        assertGolden(kvPut("web-search-config", alpha,
                "{\"max_results\":7,\"compression_method\":\"none\","
                        + "\"api_key\":\"***\",\"proxy_url\":\"***\"}"),
                200, "ct-kv-ws-put-preserve.json");
        assertGolden(kvPut("web-search-config", alpha, "{\"max_results\":51}"),
                400, "ct-kv-ws-put-bad51.json");
    }

    // ════════════════ 5) KV parser-engine-config（三条 PUT 全 SSRF 失败） ══

    @Test
    void kvParserMatchesGo() throws Exception {
        long alpha = createAlpha();
        assertGolden(kvGet("parser-engine-config", alpha), 200, "ct-kv-parser-get-default.json");
        // example.com 走 DNS：fake-ip 段每次解析的末位可能不同 → IP 掩码
        assertMasked(kvPut("parser-engine-config", alpha,
                "{\"mineru_endpoint\":\"http://mineru.example.com\",\"mineru_api_key\":\"mk-secret\","
                        + "\"mineru_model\":\"pipeline\"}"),
                400, "ct-kv-parser-put.json");
        assertGolden(kvGet("parser-engine-config", alpha), 200, "ct-kv-parser-get-after.json");
        assertMasked(kvPut("parser-engine-config", alpha,
                "{\"mineru_endpoint\":\"http://mineru2.example.com\",\"mineru_api_key\":\"***\"}"),
                400, "ct-kv-parser-put-preserve.json");
        // 字面 IP 不解析 DNS → 字节稳定
        assertGolden(kvPut("parser-engine-config", alpha,
                "{\"mineru_endpoint\":\"http://127.0.0.1:9000/x\"}"),
                400, "ct-kv-parser-put-ssrf.json");
    }

    // ════════════════ 6) KV storage-engine-config ════════════════

    @Test
    void kvStorageMatchesGo() throws Exception {
        long alpha = createAlpha();
        assertGolden(kvGet("storage-engine-config", alpha), 200, "ct-kv-storage-get-default.json");
        assertGolden(kvPut("storage-engine-config", alpha,
                "{\"default_provider\":\"minio\",\"minio\":{\"mode\":\"remote\","
                        + "\"endpoint\":\"http://minio.example.com\",\"access_key_id\":\"AK\","
                        + "\"secret_access_key\":\"SK\",\"bucket_name\":\"b\",\"use_ssl\":false,"
                        + "\"path_prefix\":\"p\"}}"),
                200, "ct-kv-storage-put.json");
        assertGolden(kvGet("storage-engine-config", alpha), 200, "ct-kv-storage-get-after.json");
        assertGolden(kvPut("storage-engine-config", alpha,
                "{\"default_provider\":\"minio\",\"minio\":{\"mode\":\"remote\","
                        + "\"endpoint\":\"http://minio2.example.com\",\"access_key_id\":\"***\","
                        + "\"secret_access_key\":\"***\",\"bucket_name\":\"b2\",\"use_ssl\":true,"
                        + "\"path_prefix\":\"p2\"}}"),
                200, "ct-kv-storage-put-preserve.json");
        assertGolden(kvPut("storage-engine-config", alpha, "{\"default_provider\":\" \"}"),
                200, "ct-kv-storage-put-empty-provider.json");
    }

    // ════════════════ 7) KV chat-history-config（enable 自动建隐藏 KB） ════

    @Test
    void kvChatHistoryMatchesGo() throws Exception {
        long alpha = createAlpha();
        assertGolden(kvGet("chat-history-config", alpha), 200, "ct-kv-chat-get-default.json");
        assertGolden(kvPut("chat-history-config", alpha,
                "{\"enabled\":false,\"embedding_model_id\":\"\"}"),
                200, "ct-kv-chat-put-off.json");
        // enable：自动建隐藏 KB，knowledge_base_id 是随机 uuid（掩码）；
        // Java KnowledgeBaseService 对无后端租户容忍（backend null 直接返回）
        assertMasked(kvPut("chat-history-config", alpha,
                "{\"enabled\":true,\"embedding_model_id\":\"" + EMBEDDING_MODEL + "\"}"),
                200, "ct-kv-chat-put-enable.json");
        assertMasked(kvGet("chat-history-config", alpha), 200, "ct-kv-chat-get-after.json");
        // 再次 enable：模型未变 → 沿用存量 KB（uuid 与上一条相同，掩码后对齐）
        assertMasked(kvPut("chat-history-config", alpha,
                "{\"enabled\":true,\"embedding_model_id\":\"" + EMBEDDING_MODEL + "\"}"),
                200, "ct-kv-chat-put-again.json");
    }

    // ════════════════ 8) KV retrieval-config ════════════════

    @Test
    void kvRetrievalMatchesGo() throws Exception {
        long alpha = createAlpha();
        assertGolden(kvGet("retrieval-config", alpha), 200, "ct-kv-ret-get-default.json");
        assertGolden(kvPut("retrieval-config", alpha,
                "{\"embedding_top_k\":20,\"vector_threshold\":0.5,\"keyword_threshold\":0.4,"
                        + "\"rerank_top_k\":5,\"rerank_threshold\":0.1,\"rerank_model_id\":\"rm-1\","
                        + "\"rrf_k\":60,\"rrf_vector_weight\":0.7,\"rrf_keyword_weight\":0.3}"),
                200, "ct-kv-ret-put.json");
        assertGolden(kvGet("retrieval-config", alpha), 200, "ct-kv-ret-get-after.json");
        assertGolden(kvPut("retrieval-config", alpha, "{\"vector_threshold\":1.5}"),
                400, "ct-kv-ret-put-bad-vector.json");
        assertGolden(kvPut("retrieval-config", alpha, "{\"embedding_top_k\":201}"),
                400, "ct-kv-ret-put-bad-topk.json");
    }

    // ════════════════ 9) KV memory-config ════════════════

    @Test
    void kvMemoryMatchesGo() throws Exception {
        long alpha = createAlpha();
        assertGolden(kvGet("memory-config", alpha), 200, "ct-kv-mem-get-default.json");
        assertGolden(kvPut("memory-config", alpha,
                "{\"enabled\":true,\"writeMode\":\"auto\",\"maxItems\":500,"
                        + "\"extractDelaySeconds\":30,\"extractMinIntervalSeconds\":60,"
                        + "\"extractInstructions\":\"  记笔记  \",\"interestThreshold\":5,"
                        + "\"embeddingModelId\":\"\",\"vectorRecall\":true,"
                        + "\"retrievalConditioning\":false}"),
                200, "ct-kv-mem-put.json");
        assertGolden(kvGet("memory-config", alpha), 200, "ct-kv-mem-get-after.json");
        assertGolden(kvPut("memory-config", alpha, "{\"writeMode\":\"bogus\"}"),
                400, "ct-kv-mem-put-bad-mode.json");
        assertGolden(kvPut("memory-config", alpha, "{\"maxItems\":2001}"),
                400, "ct-kv-mem-put-bad-max.json");
        assertGolden(kvPut("memory-config", alpha, "{\"interestThreshold\":-1}"),
                400, "ct-kv-mem-put-bad-interest.json");
        assertGolden(kvPut("memory-config", alpha, "{\"extractDelaySeconds\":3601}"),
                400, "ct-kv-mem-put-bad-delay.json");
    }

    // ════════════════ 辅助 ════════════════

    /** ct-self 自助创建 alpha（复刻录制脚本 §1 第一枪），返回租户 id。 */
    private long createAlpha() throws Exception {
        MvcResult r = mockMvc.perform(jsonBody(post("/api/v1/tenants"), selfTok,
                "{\"name\":\"ct-alpha\",\"description\":\"alpha workspace\"}")).andReturn();
        assertEquals(201, r.getResponse().getStatus(), raw(r));
        return extractId(raw(r));
    }

    private MockHttpServletRequestBuilder kvGet(String key, long alpha) {
        return get("/api/v1/tenants/kv/" + key)
                .header("Authorization", selfTok).header("X-Tenant-ID", alpha);
    }

    private MockHttpServletRequestBuilder kvPut(String key, long alpha, String body) {
        return jsonBody(put("/api/v1/tenants/kv/" + key), selfTok, body)
                .header("X-Tenant-ID", alpha);
    }

    private static long extractId(String body) {
        Matcher m = DATA_ID.matcher(body);
        assertThat(m.find()).as("创建响应应含 data.id: " + body).isTrue();
        return Long.parseLong(m.group(1));
    }

    /** 静态 golden：状态码 + 响应体逐字节。 */
    private void assertGolden(MockHttpServletRequestBuilder req, int status, String goldenName)
            throws Exception {
        MvcResult r = mockMvc.perform(req).andReturn();
        assertEquals(status, r.getResponse().getStatus(), goldenName + " 状态码不符: " + raw(r));
        assertEquals(golden(goldenName), raw(r), goldenName);
    }

    /** 掩码 golden：两侧同掩码后逐字节。 */
    private void assertMasked(MockHttpServletRequestBuilder req, int status, String goldenName)
            throws Exception {
        MvcResult r = mockMvc.perform(req).andReturn();
        assertEquals(status, r.getResponse().getStatus(), goldenName + " 状态码不符: " + raw(r));
        assertEquals(mask(golden(goldenName)), mask(raw(r)), goldenName);
    }

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

    /**
     * 两侧同掩码：api_key 明文 → UUID → 时间戳 → 数字 id → SSRF 解析 IP。
     * api_key 先于 UUID（token 字母数字连字符形态不会误中 UUID 模式，顺序双保险）。
     */
    private static String mask(String s) {
        String out = API_KEY.matcher(s).replaceAll("\"api_key\":\"<api_key>\"");
        out = UUID_VALUE.matcher(out).replaceAll("\"$1\":\"<uuid>\"");
        out = TS_VALUE.matcher(out).replaceAll("\"$1\":\"<ts>\"");
        out = DATA_ID.matcher(out).replaceAll("\"id\":\"<id>\"");
        out = SSRF_DNS_PREFIX.matcher(out).replaceAll("<ssrf-host>");
        out = SSRF_HOSTNAME_TAIL.matcher(out).replaceAll("<ssrf-host>");
        return out;
    }
    // ── 补测：GET /tenants/search 与 /tenants/all（2026-10-02 补齐漏翻译路由）──

    /** search：裸分页形态 {items,total,page,pageSize}；keyword 命中与不命中两种。 */
    @org.junit.jupiter.api.Test
    void tenantSearchBarePageShape() throws Exception {
        // keyword 命中种子空间名
        var hit = mockMvc.perform(get("/api/v1/tenants/search")
                        .param("keyword", "phase1-test-tenant")
                        .header("Authorization", superTok))
                .andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(200, hit.getResponse().getStatus());
        var node = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(hit.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertTrue(node.has("items") && node.has("total")
                && node.has("page") && node.has("pageSize"), "裸分页四键: " + node);
        org.junit.jupiter.api.Assertions.assertTrue(node.get("total").asInt() >= 1);
        // 条目是裸 Tenant 行（camelCase 键），无 success/data 信封
        org.junit.jupiter.api.Assertions.assertTrue(node.get("items").isArray());
        org.junit.jupiter.api.Assertions.assertTrue(
                node.get("items").get(0).has("name"),
                "条目应含 name: " + node.get("items").get(0));
        org.junit.jupiter.api.Assertions.assertFalse(
                node.has("success"), "不得有 success 键");

        // keyword 不命中 → items 空、total 0
        var miss = mockMvc.perform(get("/api/v1/tenants/search")
                        .param("keyword", "no-such-tenant-keyword")
                        .header("Authorization", superTok))
                .andReturn();
        var missNode = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(miss.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertEquals(0, missNode.get("total").asInt());
        org.junit.jupiter.api.Assertions.assertTrue(missNode.get("items").isEmpty());
    }

    /** all：裸数组（跨空间访问权由守卫组承担——这里只钉形状）。 */
    @org.junit.jupiter.api.Test
    void tenantAllBareArrayShape() throws Exception {
        var res = mockMvc.perform(get("/api/v1/tenants/all")
                        .header("Authorization", superTok))
                .andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(200, res.getResponse().getStatus());
        var node = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(res.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertTrue(node.isArray(), "应为裸数组: " + node);
        org.junit.jupiter.api.Assertions.assertTrue(node.size() >= 1);
    }

}
