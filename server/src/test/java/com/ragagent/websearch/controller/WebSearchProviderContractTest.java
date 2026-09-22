package com.ragagent.websearch.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import com.ragagent.TestSchema;
import com.ragagent.retrieval.domain.WebSearchResult;
import com.ragagent.websearch.provider.WebSearchProviderRegistry;
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
 * web-search-providers 11 条 + 旧版 /web-search/providers 的契约测试（波 2 第五批）。
 * golden：scripts/record-infra-config-golden.sh（43 个 wsp-* 文件，Go 实录）。
 *
 * <p>种子：仅跨租户 wsp 行（tenant 10000，固定 hex id）；其余行经 API 按录制顺序创建
 * （随机 id 掩码归一）。录制顺序影响状态：def1→def2 的默认抢占、DDG 行的凭据写入/清除、
 * PUT 的 created_at 清零——每个 @Test 从同一播种出发按录制序串完自己段落的前置变更。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebSearchProviderContractTest {

    private static final String BCRYPT =
            "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK";
    private static final long TENANT = 10002L;
    private static final String OWNER = "11111111-2222-3333-4444-555555555501";
    private static final String VIEWER = "11111111-2222-3333-4444-555555555504";
    private static final String OWNER_EMAIL = "wsp-contract-owner@weknora.test";
    private static final String VIEWER_EMAIL = "wsp-contract-viewer@weknora.test";
    private static final String UNKNOWN = "11111111-2222-3333-4444-999999999999";
    private static final String CROSS_WSP = "be000003-0000-0000-0000-000000000001";

    private static final String API = "/api/v1";

    /** uuid 值（带键名）→ "<uuid>"；错误文案里的裸 uuid 也掩码 */
    private static final Pattern UUID_VALUE = Pattern.compile(
            "\"([A-Za-z_]+)\":\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"");
    private static final Pattern UUID_BARE = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    /** year-1（Go 零值字面量）不匹配本正则 → 在 golden 里保持原样（契约值） */
    private static final Pattern TS_VALUE = Pattern.compile(
            "\"[2-9]\\d{3}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})\"");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private WebSearchProviderRegistry registry;
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

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);

        Tenant tenant = new Tenant();
        tenant.setId(TENANT);
        tenant.setName("wsp-contract-tenant");
        tenant.setStatus("active");
        tenantMapper.insert(tenant);
        Tenant cross = new Tenant();
        cross.setId(10000L);
        cross.setName("billy-workspace");
        cross.setStatus("active");
        tenantMapper.insert(cross);

        seedUser(OWNER, "wspowner", OWNER_EMAIL, "owner");
        seedUser(VIEWER, "wspviewer", VIEWER_EMAIL, "viewer");

        jdbc.update("INSERT INTO web_search_providers (id, tenant_id, name, provider, description, "
                + "parameters, is_default) VALUES (?, 10000, 'wsp-foreign', 'duckduckgo', '', '{}', FALSE)",
                CROSS_WSP);

        owner = "Bearer " + login(OWNER_EMAIL);
        viewer = "Bearer " + login(VIEWER_EMAIL);
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

    private String login(String email) {
        try {
            MvcResult result = mockMvc.perform(post(API + "/auth/login")
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

    private MvcResult perform(String method, String path, String auth, String body) throws Exception {
        var builder = switch (method) {
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

    private void compare(String golden, String actual) throws Exception {
        Path file = Path.of("src/test/resources/contracts", golden);
        if (!Files.exists(file)) {
            file = Path.of("server/src/test/resources/contracts", golden);
        }
        String expected = mask(Files.readString(file, java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(expected, mask(actual),
                () -> "golden mismatch: " + golden + "\nexpected: " + expected + "\nactual:   " + mask(actual));
    }

    private static String mask(String s) {
        s = UUID_VALUE.matcher(s).replaceAll("\"$1\":\"<uuid>\"");
        s = UUID_BARE.matcher(s).replaceAll("<uuid>");
        s = TS_VALUE.matcher(s).replaceAll("\"<ts>\"");
        return s;
    }

    private String createProvider(String body) throws Exception {
        MvcResult result = perform("POST", API + "/web-search-providers", owner, body);
        assertEquals(201, result.getResponse().getStatus(),
                () -> "seed create failed: " + result.getResponse().getErrorMessage());
        com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString());
        return node.get("data").get("id").asText();
    }

    // ── 1) 静态元数据 ──────────────────────────────────────────────────

    @Test
    void section1_types() throws Exception {
        compareAndStatus("wsp-types.json", 200, "GET", API + "/web-search-providers/types", owner, null);
        compareAndStatus("wsp-legacy-providers.json", 200, "GET", API + "/web-search/providers", owner, null);
    }

    // ── 2) 创建：binding / service 校验（500）/ 角色门 ──────────────────

    @Test
    void section2_createFailures() throws Exception {
        String base = API + "/web-search-providers";
        compareAndStatus("wsp-create-empty.json", 400, "POST", base, owner, "");
        compareAndStatus("wsp-create-missing-all.json", 400, "POST", base, owner, "{}");
        compareAndStatus("wsp-create-bad-type.json", 500, "POST", base, owner,
                "{\"name\":\"x\",\"provider\":\"bogus\"}");
        compareAndStatus("wsp-create-brave-nokey.json", 500, "POST", base, owner,
                "{\"name\":\"x\",\"provider\":\"brave\"}");
        compareAndStatus("wsp-create-zhipu-badengine.json", 500, "POST", base, owner,
                "{\"name\":\"x\",\"provider\":\"zhipu\",\"parameters\":{\"extra_config\":{\"search_engine\":\"bogus\"}}}");
        compareAndStatus("wsp-create-searxng-nourl.json", 500, "POST", base, owner,
                "{\"name\":\"x\",\"provider\":\"searxng\"}");
        compareAndStatus("wsp-create-searxng-ssrf.json", 500, "POST", base, owner,
                "{\"name\":\"x\",\"provider\":\"searxng\",\"parameters\":{\"base_url\":\"http://127.0.0.1:18999\"}}");
        compareAndStatus("wsp-create-badproxy.json", 500, "POST", base, owner,
                "{\"name\":\"x\",\"provider\":\"duckduckgo\",\"parameters\":{\"proxy_url\":\"http://127.0.0.1:1080\"}}");
        compareAndStatus("wsp-viewer-create.json", 403, "POST", base, viewer,
                "{\"name\":\"x\",\"provider\":\"duckduckgo\"}");
    }

    // ── 3) 创建成功 + 默认抢占 + 列表/详情/跨租户/未认证 ─────────────────

    @Test
    void section3_createOkAndList() throws Exception {
        String base = API + "/web-search-providers";
        createProvider(
                "{\"name\":\"wsp-golden-ddg\",\"provider\":\"duckduckgo\",\"description\":\"d\","
                        + "\"parameters\":{\"base_url\":\"x\",\"extra_config\":{\"k\":\"v\"}},\"is_default\":false}");
        createProvider("{\"name\":\"wsp-def-one\",\"provider\":\"duckduckgo\",\"is_default\":true}");
        createProvider("{\"name\":\"wsp-def-two\",\"provider\":\"duckduckgo\",\"is_default\":true}");
        compareAndStatus("wsp-list-defaults.json", 200, "GET", base, owner, null);
        compareAndStatus("wsp-get.json", 200, "GET",
                base + "/" + latestId("wsp-golden-ddg"), owner, null);
        compareAndStatus("wsp-get-404.json", 404, "GET", base + "/" + UNKNOWN, owner, null);
        compareAndStatus("wsp-cross-tenant.json", 404, "GET", base + "/" + CROSS_WSP, owner, null);
        compareAndStatus("wsp-list-noauth.json", 401, "GET", base, null, null);
    }

    private String latestId(String name) {
        return jdbc.queryForObject(
                "SELECT id FROM web_search_providers WHERE name = ? AND deleted_at IS NULL", String.class, name);
    }

    // ── 4) 更新：404 / created_at 清零 / 参数合并 ──────────────────────

    @Test
    void section4_update() throws Exception {
        String base = API + "/web-search-providers";
        String ddg = createProvider(
                "{\"name\":\"wsp-golden-ddg\",\"provider\":\"duckduckgo\",\"description\":\"d\","
                        + "\"parameters\":{\"base_url\":\"x\",\"extra_config\":{\"k\":\"v\"}},\"is_default\":false}");
        String def1 = createProvider("{\"name\":\"wsp-def-one\",\"provider\":\"duckduckgo\",\"is_default\":true}");

        compareAndStatus("wsp-update-404.json", 404, "PUT", base + "/" + UNKNOWN, owner,
                "{\"name\":\"x\"}");
        compareAndStatus("wsp-update-preserve.json", 200, "PUT", base + "/" + def1, owner,
                "{\"name\":\"\",\"description\":\"\",\"parameters\":{},\"is_default\":false}");
        compareAndStatus("wsp-get-after-update.json", 200, "GET", base + "/" + def1, owner, null);
        // api_key 忽略 + engine_id 设置 + extra_config 保留 + base_url 丢弃
        compareAndStatus("wsp-update-params-merge.json", 200, "PUT", base + "/" + ddg, owner,
                "{\"parameters\":{\"engine_id\":\"e2\",\"api_key\":\"stale-key\"}}");
        compareAndStatus("wsp-get-after-merge.json", 200, "GET", base + "/" + ddg, owner, null);
    }

    // ── 5) 凭据子资源 ──────────────────────────────────────────────────

    @Test
    void section5_credentials() throws Exception {
        String base = API + "/web-search-providers";
        // 录制序：DDG 行先经 create(带 description/params) → PUT(engine_id 合并、created_at 清零)
        // → 才进入凭据流；本段必须复刻这些前置变更
        String ddg = createProvider(
                "{\"name\":\"wsp-golden-ddg\",\"provider\":\"duckduckgo\",\"description\":\"d\","
                        + "\"parameters\":{\"base_url\":\"x\",\"extra_config\":{\"k\":\"v\"}},\"is_default\":false}");
        perform("PUT", base + "/" + ddg, owner,
                "{\"parameters\":{\"engine_id\":\"e2\",\"api_key\":\"stale-key\"}}");

        compareAndStatus("wsp-cred-put-status.json", 200, "PUT", base + "/" + ddg + "/credentials", owner, "{}");
        compareAndStatus("wsp-cred-put.json", 200, "PUT", base + "/" + ddg + "/credentials", owner,
                "{\"api_key\":\"sk-golden-123\"}");
        compareAndStatus("wsp-get-after-cred.json", 200, "GET", base + "/" + ddg, owner, null);
        compareAndStatus("wsp-cred-put-badjson.json", 400, "PUT", base + "/" + ddg + "/credentials", owner, "");
        compareAndStatus("wsp-cred-put-404.json", 404, "PUT", base + "/" + UNKNOWN + "/credentials", owner, "{}");
        compareAndStatus("wsp-cred-delete-badfield.json", 400, "DELETE",
                base + "/" + ddg + "/credentials/notkey", owner, null);
        compareAndStatus("wsp-cred-delete.json", 204, "DELETE",
                base + "/" + ddg + "/credentials/api_key", owner, null);
        compareAndStatus("wsp-get-after-clear.json", 200, "GET", base + "/" + ddg, owner, null);
        compareAndStatus("wsp-cred-delete-404.json", 500, "DELETE",
                base + "/" + UNKNOWN + "/credentials/api_key", owner, null);
    }

    // ── 6) test 端点（确定性分支）+ 删除 ───────────────────────────────

    @Test
    void section6_testAndDelete() throws Exception {
        String base = API + "/web-search-providers";
        String ddg = createProvider("{\"name\":\"wsp-golden-ddg\",\"provider\":\"duckduckgo\"}");

        compareAndStatus("wsp-test-raw-unknown.json", 200, "POST", base + "/test", owner,
                "{\"provider\":\"nosuch\",\"parameters\":{}}");
        compareAndStatus("wsp-test-raw-searxng-empty.json", 200, "POST", base + "/test", owner,
                "{\"provider\":\"searxng\",\"parameters\":{\"base_url\":\"\"}}");
        compareAndStatus("wsp-test-raw-searxng-ssrf.json", 200, "POST", base + "/test", owner,
                "{\"provider\":\"searxng\",\"parameters\":{\"base_url\":\"http://127.0.0.1:18999\"}}");
        compareAndStatus("wsp-test-raw-zhipu-nokey.json", 200, "POST", base + "/test", owner,
                "{\"provider\":\"zhipu\",\"parameters\":{}}");
        compareAndStatus("wsp-test-raw-badjson.json", 400, "POST", base + "/test", owner, "");
        compareAndStatus("wsp-test-viewer-denied.json", 403, "POST", base + "/test", viewer,
                "{\"provider\":\"zhipu\"}");
        compareAndStatus("wsp-test-byid-404.json", 404, "POST", base + "/" + UNKNOWN + "/test", owner, null);
        compareAndStatus("wsp-delete.json", 200, "DELETE", base + "/" + ddg, owner, null);
        compareAndStatus("wsp-delete-404.json", 404, "DELETE", base + "/" + UNKNOWN, owner, null);
        compareAndStatus("wsp-get-after-delete.json", 404, "GET", base + "/" + ddg, owner, null);
    }

    // ── 7) test 端点真实执行面（非 golden：Go 实录仅覆盖构造失败分支）──────

    /**
     * 2026-09-23 占位扫清回归（doTestSearch 真实执行编排）：构造成功 → 真实 search
     * 的三种出口——有结果 {"success":true}；空结果 → EmptyTestResults 文案；
     * search 抛错 → 原文透传（均 200 纯字符串）。以 registry.register 注入内存
     * stub（无需外网；Go 侧无此路径实录，故为内联断言而非 golden）。
     */
    @Test
    void section7_testRealExecution() throws Exception {
        registry.register("stub-ok", params -> new StubProvider("stub-ok", new WebSearchResult()));
        registry.register("stub-empty", params -> new StubProvider("stub-empty", null));
        registry.register("stub-fail", params -> new StubProvider("stub-fail", "explode"));

        String base = API + "/web-search-providers";
        MvcResult ok = perform("POST", base + "/test", owner, "{\"provider\":\"stub-ok\"}");
        assertEquals(200, ok.getResponse().getStatus());
        assertTrue(ok.getResponse().getContentAsString().contains("\"success\":true"),
                ok.getResponse().getContentAsString());

        // 空结果 → default 文案（stub-empty 不在 searxng/ddg/keenable/exa 分支）
        MvcResult empty = perform("POST", base + "/test", owner, "{\"provider\":\"stub-empty\"}");
        assertEquals(200, empty.getResponse().getStatus());
        assertTrue(empty.getResponse().getContentAsString()
                        .contains("search returned 0 results, please verify your API key and configuration"),
                empty.getResponse().getContentAsString());

        MvcResult fail = perform("POST", base + "/test", owner, "{\"provider\":\"stub-fail\"}");
        assertEquals(200, fail.getResponse().getStatus());
        assertTrue(fail.getResponse().getContentAsString().contains("stub search exploded"),
                fail.getResponse().getContentAsString());
    }

    /** registry 注入用 stub；mode = 结果条目 / null（空结果）/ "explode"（抛错）。 */
    private static final class StubProvider
            implements com.ragagent.websearch.provider.WebSearchProvider {
        private final String name;
        private final Object mode;

        StubProvider(String name, Object mode) {
            this.name = name;
            this.mode = mode;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<WebSearchResult> search(String query, int maxResults, boolean includeDate) {
            // 对照 Go doTestSearch 的调用参数：searchProvider.Search(ctx, "test", 1, false)
            assertEquals("test", query);
            assertEquals(1, maxResults);
            assertFalse(includeDate);
            if ("explode".equals(mode)) {
                throw new IllegalStateException("stub search exploded");
            }
            return mode == null ? List.of() : List.of((WebSearchResult) mode);
        }
    }
}
