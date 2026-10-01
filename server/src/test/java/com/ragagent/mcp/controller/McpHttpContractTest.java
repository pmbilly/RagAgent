package com.ragagent.mcp.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
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
import com.ragagent.common.security.SsrfGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * MCP 服务 HTTP 层的契约测试（对照 Go internal/handler/mcp_service.go、
 * mcp_credentials.go、mcp_metadata.go、mcp_usage_instructions.go）。
 *
 * <p>用 MockMvc 走完整过滤链（含登录签发的 JWT），因此角色可见性
 * （Admin vs Viewer 的集成细节剥离）是端到端验证的，而不是只测 DTO。</p>
 *
 * <p>RBAC 拦截规则由 WebConfig 注册（本模块不动该文件），故这里不测 403 角色拒绝；
 * 需要角色的分支（如 metadata refresh 的 Admin 门禁）由控制器内部判定，
 * 用 viewer 身份即可覆盖。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class McpHttpContractTest {

    private static final String BCRYPT =
            "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK"; // Passw0rd!
    private static final long TENANT = 10002L;

    private static final Pattern ID_UUID = Pattern.compile(
            "\"id\":\"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\"");

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
    private SsrfGuard ssrfGuard;

    @BeforeEach
    void seed() {
        // 只放行 example.com：SSRF 用例仍会命中 127.0.0.1 的拒绝
        ssrfGuard.reloadWhitelist("example.com");
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);

        Tenant tenant = new Tenant();
        tenant.setId(TENANT);
        tenant.setName("mcp-http-test-tenant");
        tenant.setStatus("active");
        tenantMapper.insert(tenant);

        insertUser("11111111-2222-3333-4444-555555555501", "mcpowner", "mcp-owner@weknora.test");
        insertUser("11111111-2222-3333-4444-555555555504", "mcpviewer", "mcp-viewer@weknora.test");
        insertMember("11111111-2222-3333-4444-555555555501", "owner");
        insertMember("11111111-2222-3333-4444-555555555504", "viewer");
    }

    private void insertUser(String id, String username, String email) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(BCRYPT);
        user.setTenantId(TENANT);
        user.setIsActive(true);
        user.setPreferences(new UserPreferences());
        userMapper.insert(user);
    }

    private void insertMember(String userId, String role) {
        TenantMember member = new TenantMember();
        member.setUserId(userId);
        member.setTenantId(TENANT);
        member.setRole(role);
        member.setStatus("active");
        memberMapper.insert(member);
    }

    private String loginOwner() throws Exception {
        return login("mcp-owner@weknora.test");
    }

    private String loginViewer() throws Exception {
        return login("mcp-viewer@weknora.test");
    }

    private String login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"Passw0rd!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(body);
        assertTrue(m.find(), "login 响应应含 token: " + body);
        return m.group(1);
    }

    private MvcResult perform(org.springframework.test.web.servlet.RequestBuilder rb) throws Exception {
        return mockMvc.perform(rb).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String createService(String token, String json) throws Exception {
        MvcResult r = perform(post("/api/v1/mcp-services")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(json));
        assertEquals(201, r.getResponse().getStatus(), "创建应成功（§1.15 → 201）：" + body(r));
        Matcher m = ID_UUID.matcher(body(r));
        assertTrue(m.find(), "创建响应应含服务 UUID：" + body(r));
        return m.group(1);
    }

    private static final String FULL_BODY = "{\"name\":\"golden-mcp\",\"description\":\"d0\","
            + "\"transportType\":\"sse\",\"url\":\"https://example.com/mcp\","
            + "\"headers\":{\"X-Tenant\":\"acme\"},"
            + "\"envVars\":{\"TOKEN\":\"env-secret\"},"
            + "\"authConfig\":{\"apiKey\":\"sk-real-do-not-leak\",\"token\":\"tok-real-do-not-leak\","
            + "\"customHeaders\":{\"X-Trace\":\"abc\"}}}";

    // ── 创建：密钥剥离 + GORM 默认值 ─────────────────────────────────────

    @Test
    void createOmitsSecretsAndDelegatesDeliveryToCredentialsMap() throws Exception {
        String token = loginOwner();

        MvcResult r = perform(post("/api/v1/mcp-services")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(FULL_BODY));
        assertEquals(201, r.getResponse().getStatus(), body(r));
        String s = body(r);

        assertFalse(s.contains("sk-real-do-not-leak"), "创建响应不得回显原始 api_key：" + s);
        assertFalse(s.contains("tok-real-do-not-leak"), "创建响应不得回显原始 token：" + s);
        assertTrue(s.contains("\"apiKey\":{\"configured\":true}"), s);
        assertTrue(s.contains("\"token\":{\"configured\":true}"), s);
        assertTrue(s.contains("\"customHeaders\""), s);
        // §2.1：裸对象，不再有 {data,success} 信封
        assertFalse(s.contains("\"success\""), "响应不该带 success 键：" + s);
        assertFalse(s.startsWith("{\"data\":"), "响应不该被 data 包起来：" + s);
        // GORM 的 default:true 语义：未传 enabled 时落库与响应都是 true
        assertTrue(s.contains("\"enabled\":true"), "enabled 必须命中 DB 默认值 true：" + s);
        // 服务层补的默认高级配置，键名必须是蛇形（Go json tag）
        assertTrue(s.contains("\"retryCount\":3") && s.contains("\"retryDelay\":1"),
                "advanced_config 必须用 Go 的蛇形键名：" + s);
    }

    @Test
    void getReturnsDetailForAdminAndStripsForViewer() throws Exception {
        String owner = loginOwner();
        String viewer = loginViewer();
        String id = createService(owner, FULL_BODY);

        String adminView = body(perform(get("/api/v1/mcp-services/" + id)
                .header("Authorization", "Bearer " + owner)));
        assertTrue(adminView.contains("https://example.com/mcp"), "Admin 可见 URL：" + adminView);
        assertTrue(adminView.contains("\"X-Tenant\":\"acme\""), "Admin 可见 headers：" + adminView);
        assertFalse(adminView.contains("sk-real-do-not-leak"), adminView);

        String viewerView = body(perform(get("/api/v1/mcp-services/" + id)
                .header("Authorization", "Bearer " + viewer)));
        assertFalse(viewerView.contains("https://example.com/mcp"),
                "Viewer 必须被剥离 URL：" + viewerView);
        assertFalse(viewerView.contains("env-secret"), "Viewer 必须被剥离 env_vars：" + viewerView);
        assertFalse(viewerView.contains("X-Trace"), "Viewer 必须被剥离 custom_headers：" + viewerView);
        assertFalse(viewerView.contains("sk-real-do-not-leak"), viewerView);
        // 非秘密的鉴权策略仍需回显，前端才能渲染当前策略
        assertTrue(viewerView.contains("\"authConfig\""), viewerView);
    }

    @Test
    void listReturnsBareArray() throws Exception {
        String token = loginOwner();
        createService(token, FULL_BODY);

        String s = body(perform(get("/api/v1/mcp-services").header("Authorization", "Bearer " + token)));
        // §2.1：列表裸数组
        assertTrue(s.startsWith("["), "列表应是裸数组：" + s);
        assertFalse(s.contains("\"success\""), s);
        assertFalse(s.contains("sk-real-do-not-leak"), s);
    }

    @Test
    void getUnknownServiceReturns404() throws Exception {
        String token = loginOwner();
        MvcResult r = perform(get("/api/v1/mcp-services/nope").header("Authorization", "Bearer " + token));

        assertEquals(404, r.getResponse().getStatus());
        assertTrue(body(r).contains("\"message\":\"MCP service not found\""), body(r));
    }

    // ── 更新：存在性语义 + 凭据保护 ──────────────────────────────────────

    @Test
    void updateRespectsFieldPresenceAndNeverTouchesSecrets() throws Exception {
        String token = loginOwner();
        // 故意不带凭据创建，便于断言"主 PUT 带 api_key 也不会写入"
        String id = createService(token, "{\"name\":\"no-cred\",\"transportType\":\"sse\","
                + "\"url\":\"https://example.com/mcp\"}");

        MvcResult r = perform(put("/api/v1/mcp-services/" + id)
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"description\":\"after\","
                        + "\"authConfig\":{\"apiKey\":\"hostile-key\",\"token\":\"hostile-token\"}}"));
        assertEquals(200, r.getResponse().getStatus(), body(r));
        String s = body(r);

        assertEquals("after", field(s, "description"));
        assertEquals("no-cred", field(s, "name"), "未提供的标量字段必须保持原值");
        assertFalse(s.contains("hostile-key"), "secret 永不经主 PUT 写入：" + s);
        assertFalse(s.contains("hostile-token"), s);
        assertTrue(s.contains("\"apiKey\":{\"configured\":false}"),
                "主 PUT 里的 api_key 必须被忽略（仍为未配置）：" + s);
    }

    @Test
    void updateRejectsInvalidUsageInstructions() throws Exception {
        String token = loginOwner();
        String id = createService(token, "{\"name\":\"n1\",\"transportType\":\"sse\"}");

        MvcResult empty = perform(put("/api/v1/mcp-services/" + id)
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"usageInstructions\":\"   \"}"));
        assertEquals(400, empty.getResponse().getStatus());
        assertTrue(body(empty).contains(
                "Usage instructions must contain between 1 and 16000 characters"), body(empty));

        String tooLong = "a".repeat(16001);
        MvcResult longBody = perform(put("/api/v1/mcp-services/" + id)
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"usageInstructions\":\"" + tooLong + "\"}"));
        assertEquals(400, longBody.getResponse().getStatus());
    }

    @Test
    void updateValidUsageInstructionsIsTrimmed() throws Exception {
        String token = loginOwner();
        String id = createService(token, "{\"name\":\"n2\",\"transportType\":\"sse\"}");

        String s = body(perform(put("/api/v1/mcp-services/" + id)
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"usageInstructions\":\"  use it for search  \"}")));

        assertEquals("use it for search", field(s, "usageInstructions"));
    }

    @Test
    void updateRejectsSsrfUrl() throws Exception {
        String token = loginOwner();
        String id = createService(token, "{\"name\":\"n3\",\"transportType\":\"sse\"}");

        MvcResult r = perform(put("/api/v1/mcp-services/" + id)
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"url\":\"http://127.0.0.1:8080/mcp\"}"));

        assertEquals(400, r.getResponse().getStatus(), body(r));
        String msg = body(r);
        assertTrue(msg.contains("MCP service URL 未通过安全校验"), msg);
        assertTrue(msg.contains("127.0.0.1 is restricted"), msg);
    }

    @Test
    void createRejectsSsrfUrl() throws Exception {
        String token = loginOwner();
        MvcResult r = perform(post("/api/v1/mcp-services")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"name\":\"ssrf\",\"transportType\":\"sse\","
                        + "\"url\":\"http://127.0.0.1:8080/mcp\"}"));

        assertEquals(400, r.getResponse().getStatus(), body(r));
        assertTrue(body(r).contains("127.0.0.1 is restricted"), body(r));
    }

    @Test
    void createRejectsStdioTransport() throws Exception {
        String token = loginOwner();
        MvcResult r = perform(post("/api/v1/mcp-services")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"name\":\"stdio-svc\",\"transportType\":\"stdio\"}"));

        assertEquals(500, r.getResponse().getStatus(), body(r));
        assertTrue(body(r).contains("stdio transport is disabled for security reasons"), body(r));
    }

    @Test
    void deleteReturnsNoContent() throws Exception {
        String token = loginOwner();
        String id = createService(token, "{\"name\":\"n4\",\"transportType\":\"sse\"}");

        MvcResult r = perform(delete("/api/v1/mcp-services/" + id)
                .header("Authorization", "Bearer " + token));

        // §1.13：同步完成的删除 → 204 且无响应体
        assertEquals(204, r.getResponse().getStatus());
        assertEquals("", body(r), "删除不该有响应体");
    }

    // ── 凭据子资源 ───────────────────────────────────────────────────────

    @Test
    void credentialsSubresourceLifecycle() throws Exception {
        String token = loginOwner();
        String id = createService(token, "{\"name\":\"cred\",\"transportType\":\"sse\"}");

        // 空 body 的 PUT 退化为"当前状态查询"，不是 400
        String current = body(perform(put("/api/v1/mcp-services/" + id + "/credentials")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{}")));
        assertEquals("{\"fields\":{\"apiKey\":{\"configured\":false},"
                + "\"token\":{\"configured\":false}}}", current);

        String saved = body(perform(put("/api/v1/mcp-services/" + id + "/credentials")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"apiKey\":\"fresh-key\"}")));
        assertTrue(saved.contains("\"apiKey\":{\"configured\":true}"), saved);
        assertTrue(saved.contains("\"token\":{\"configured\":false}"), saved);
        assertFalse(saved.contains("fresh-key"), "响应只回布尔值，绝不回显凭据：" + saved);

        // DELETE 幂等，返回 204 无正文
        MvcResult removed = perform(delete("/api/v1/mcp-services/" + id + "/credentials/apiKey")
                .header("Authorization", "Bearer " + token));
        assertEquals(204, removed.getResponse().getStatus());
        assertEquals("", body(removed));

        String after = body(perform(get("/api/v1/mcp-services/" + id)
                .header("Authorization", "Bearer " + token)));
        assertTrue(after.contains("\"apiKey\":{\"configured\":false}"), after);
    }

    @Test
    void credentialsRejectsUnknownField() throws Exception {
        String token = loginOwner();
        String id = createService(token, "{\"name\":\"cred2\",\"transportType\":\"sse\"}");

        MvcResult r = perform(delete("/api/v1/mcp-services/" + id + "/credentials/bogus")
                .header("Authorization", "Bearer " + token));

        assertEquals(400, r.getResponse().getStatus(), body(r));
        assertTrue(body(r).contains("unknown credential field: bogus"), body(r));
    }

    // ── 工具审批策略 ─────────────────────────────────────────────────────

    @Test
    void toolApprovalPolicyLifecycle() throws Exception {
        String token = loginOwner();
        String id = createService(token, "{\"name\":\"appr\",\"transportType\":\"sse\"}");

        // §2.1：审批行列表是裸数组
        assertEquals("[]", body(perform(
                get("/api/v1/mcp-services/" + id + "/tool-approvals")
                        .header("Authorization", "Bearer " + token))));

        MvcResult set = perform(put("/api/v1/mcp-services/" + id + "/tool-approvals/search")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"requireApproval\":true}"));
        // 策略写入 → 204（§14.9n M4：不再回 {"success":true}）
        assertEquals(204, set.getResponse().getStatus(), body(set));
        assertEquals("", body(set));

        String rows = body(perform(get("/api/v1/mcp-services/" + id + "/tool-approvals")
                .header("Authorization", "Bearer " + token)));
        assertTrue(rows.contains("\"toolName\":\"search\""), rows);
        assertTrue(rows.contains("\"requireApproval\":true"), rows);
        assertTrue(rows.contains("\"enabled\":true"), "缺行默认 enabled=true：" + rows);
    }

    @Test
    void toolApprovalRequiresAtLeastOneField() throws Exception {
        String token = loginOwner();
        String id = createService(token, "{\"name\":\"appr2\",\"transportType\":\"sse\"}");

        MvcResult r = perform(put("/api/v1/mcp-services/" + id + "/tool-approvals/search")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{}"));

        assertEquals(400, r.getResponse().getStatus(), body(r));
        assertTrue(body(r).contains("require_approval or enabled is required"), body(r));
    }

    @Test
    void toolApprovalOnUnknownServiceReturns404() throws Exception {
        String token = loginOwner();

        MvcResult r = perform(put("/api/v1/mcp-services/nope/tool-approvals/search")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"enabled\":false}"));

        assertEquals(404, r.getResponse().getStatus(), body(r));
        assertTrue(body(r).contains("not found"), body(r));
    }

    // ── 目录快照 ─────────────────────────────────────────────────────────

    @Test
    void metadataIsNullWhenNeverSynced() throws Exception {
        String token = loginOwner();
        String id = createService(token, "{\"name\":\"meta\",\"transportType\":\"sse\"}");

        MvcResult r = perform(get("/api/v1/mcp-services/" + id + "/metadata")
                .header("Authorization", "Bearer " + token));

        assertEquals(200, r.getResponse().getStatus());
        // §2.1：裸资源——"从未同步"就是 JSON null（不再是 {"data":null,"success":true}）
        assertEquals("null", body(r));
    }

    @Test
    void metadataRefreshOnUnknownServiceReturns404() throws Exception {
        String token = loginOwner();

        MvcResult r = perform(post("/api/v1/mcp-services/nope/metadata/refresh")
                .header("Authorization", "Bearer " + token));

        assertEquals(404, r.getResponse().getStatus(), body(r));
        assertTrue(body(r).contains("MCP service not found"), body(r));
    }

    /** 静态鉴权目录写的是租户共享快照 → Viewer 必须被拦在 403（对照 mayWriteSharedMCPMetadata） */
    @Test
    void metadataRefreshForStaticAuthRequiresAdmin() throws Exception {
        String owner = loginOwner();
        String viewer = loginViewer();
        String id = createService(owner, "{\"name\":\"meta2\",\"transportType\":\"sse\","
                + "\"url\":\"https://example.com/mcp\"}");

        MvcResult r = perform(post("/api/v1/mcp-services/" + id + "/metadata/refresh")
                .header("Authorization", "Bearer " + viewer));

        assertEquals(403, r.getResponse().getStatus(), body(r));
        assertTrue(body(r).contains("Refreshing a shared MCP directory requires an administrator"),
                body(r));
    }

    // ── 使用说明生成 ─────────────────────────────────────────────────────

    @Test
    void generateUsageInstructionsRequiresSyncedCatalog() throws Exception {
        String token = loginOwner();
        String id = createService(token, "{\"name\":\"usage\",\"transportType\":\"sse\"}");

        MvcResult r = perform(post("/api/v1/mcp-services/" + id + "/usage-instructions/generate")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"language\":\"en-US\"}"));

        assertEquals(400, r.getResponse().getStatus(), body(r));
        assertTrue(body(r).contains("Sync the MCP tools before generating usage instructions"),
                body(r));
    }

    @Test
    void generateUsageInstructionsOnUnknownServiceReturns404() throws Exception {
        String token = loginOwner();

        MvcResult r = perform(post("/api/v1/mcp-services/nope/usage-instructions/generate")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{}"));

        assertEquals(404, r.getResponse().getStatus(), body(r));
    }

    // ── 工具 / 资源（上游不可达时走 500 错误路径） ────────────────────────

    @Test
    void toolsOnUnknownServiceReturns500WithGoMessage() throws Exception {
        String token = loginOwner();
        MvcResult r = perform(get("/api/v1/mcp-services/nope/tools")
                .header("Authorization", "Bearer " + token));

        assertEquals(500, r.getResponse().getStatus(), body(r));
        assertTrue(body(r).contains("Failed to get MCP service tools: MCP service not found"),
                body(r));
    }

    @Test
    void testConnectionOnUnknownServiceReturnsBusinessFailure() throws Exception {
        String token = loginOwner();
        MvcResult r = perform(post("/api/v1/mcp-services/nope/test")
                .header("Authorization", "Bearer " + token));

        // 与其它端点不同：连接失败也返回 200（裸 McpTestResult，success=false）
        assertEquals(200, r.getResponse().getStatus(), body(r));
        String s = body(r);
        assertTrue(s.contains("\"success\":false"), s);
        assertTrue(s.contains("Test failed: MCP service not found"), s);
    }

    // ── 解析工具 ─────────────────────────────────────────────────────────

    /** 从响应体里取一个字符串字段（值只有简单字母数字时够用） */
    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(json);
        assertTrue(m.find(), "响应里应含字段 " + name + "：" + json);
        return m.group(1);
    }
}
