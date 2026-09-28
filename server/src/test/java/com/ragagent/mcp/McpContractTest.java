package com.ragagent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 阶段 4.1 MCP 契约测试：MCP 服务 CRUD + 工具审批 + 凭据子资源，对照 golden 比对。
 *
 * golden 录制序（Go dev server + SSRF_WHITELIST_EXTRA=mcp.example.com）：
 * create（SSRF 拒绝 / 成功）→ list → get → 404 → update → tool-approvals（空/设置/有值）
 * → credentials（put/delete/非法 field）→ metadata → test → tools → viewer 403 → delete。
 *
 * 掩码：UUID（id / service_id）、时间戳。
 * `test` 与 `tools` 用结构化断言——前者的消息含 JDK/Go 各异的网络错误文案，
 * 后者依赖服务启用状态而非纯契约。
 */
@SpringBootTest
@AutoConfigureMockMvc
class McpContractTest {

    private static final String BCRYPT = "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK"; // Passw0rd!
    private static final OffsetDateTime TS = OffsetDateTime.of(2026, 9, 17, 10, 0, 0, 123456000, ZoneOffset.ofHours(8));
    private static final String MCP_URL = "https://mcp.example.com/mcp";

    private static final Pattern TS_PATTERN = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})");
    private static final Pattern UUID_KEY_PATTERN = Pattern.compile(
            "\"(id|service_id)\":\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"");

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
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        // golden 是用白名单里的 mcp.example.com 录的——测试侧也要放行，否则 create 走 SSRF 拒绝分支
        ssrfGuard.reloadWhitelist("mcp.example.com");

        Tenant tenant = new Tenant();
        tenant.setId(10002L);
        tenant.setName("phase1-test-tenant");
        tenant.setStatus("active");
        tenantMapper.insert(tenant);

        insertUser("11111111-2222-3333-4444-555555555501", "phase1test", "java-phase1@weknora.test");
        insertUser("11111111-2222-3333-4444-555555555504", "phase1viewer", "java-phase1-viewer@weknora.test");
        insertMember("11111111-2222-3333-4444-555555555501", "admin"); // golden 录制者对该租户是 admin
        insertMember("11111111-2222-3333-4444-555555555504", "viewer");
    }

    private void insertUser(String id, String username, String email) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(BCRYPT);
        user.setTenantId(10002L);
        user.setIsActive(true);
        user.setPreferences(new UserPreferences());
        user.setCreatedAt(TS);
        user.setUpdatedAt(TS);
        userMapper.insert(user);
    }

    private void insertMember(String userId, String role) {
        TenantMember member = new TenantMember();
        member.setUserId(userId);
        member.setTenantId(10002L);
        member.setRole(role);
        member.setStatus("active");
        member.setJoinedAt(TS);
        memberMapper.insert(member);
    }

    @Test
    void mcpServiceLifecycle() throws Exception {
        String token = login("java-phase1@weknora.test");

        // 1. create 成功 → 掩码比对
        MvcResult created = mockMvc.perform(post("/api/v1/mcp-services")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"golden-mcp\",\"description\":\"phase4 golden mcp service\","
                                + "\"enabled\":true,\"transport_type\":\"http-streamable\","
                                + "\"url\":\"" + MCP_URL + "\",\"headers\":{\"X-Custom\":\"v1\"}}"))
                .andExpect(status().isOk())
                .andReturn();
        String createdBody = created.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(mask(golden("mcp-create.json")), mask(createdBody),
                "mcp-create 应与 golden 一致（掩码后）");
        String id = extractUuid(createdBody);

        // 2. list → 掩码比对
        MvcResult list = mockMvc.perform(get("/api/v1/mcp-services")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(mask(golden("mcp-list.json")),
                mask(list.getResponse().getContentAsString(StandardCharsets.UTF_8)),
                "mcp-list 应与 golden 一致（掩码后）");

        // 3. get → 掩码比对
        MvcResult got = mockMvc.perform(get("/api/v1/mcp-services/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(mask(golden("mcp-get.json")),
                mask(got.getResponse().getContentAsString(StandardCharsets.UTF_8)),
                "mcp-get 应与 golden 一致（掩码后）");

        // 4. 404 → 静态 golden
        mockMvc.perform(get("/api/v1/mcp-services/00000000-0000-0000-0000-000000000000")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(content().bytes(goldenBytes("mcp-not-found.json")));

        // 5. update → 掩码比对
        MvcResult updated = mockMvc.perform(put("/api/v1/mcp-services/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"golden-mcp-renamed\",\"description\":\"updated desc\",\"enabled\":false}"))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(mask(golden("mcp-update.json")),
                mask(updated.getResponse().getContentAsString(StandardCharsets.UTF_8)),
                "mcp-update 应与 golden 一致（掩码后）");

        // 6. tool-approvals 空 → 静态 golden
        mockMvc.perform(get("/api/v1/mcp-services/" + id + "/tool-approvals")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().bytes(goldenBytes("mcp-tool-approvals-empty.json")));

        // 7. 设置审批策略 → 静态 golden（{"success":true}）
        mockMvc.perform(put("/api/v1/mcp-services/" + id + "/tool-approvals/golden_tool")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"require_approval\":true}"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(goldenBytes("mcp-tool-approval-set.json")));

        // 8. tool-approvals 有 1 项 → 掩码比对（含生成的 id 与时间戳）
        MvcResult approvals = mockMvc.perform(get("/api/v1/mcp-services/" + id + "/tool-approvals")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(mask(golden("mcp-tool-approvals.json")),
                mask(approvals.getResponse().getContentAsString(StandardCharsets.UTF_8)),
                "mcp-tool-approvals 应与 golden 一致（掩码后）");

        // 9. credentials put → 静态 golden（只暴露 configured 布尔）
        mockMvc.perform(put("/api/v1/mcp-services/" + id + "/credentials")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"api_key\":\"sk-golden-123\",\"token\":\"tok-golden-456\"}"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(goldenBytes("mcp-credentials-put.json")));

        // 10. credentials delete → 204（golden 是空响应体）
        mockMvc.perform(delete("/api/v1/mcp-services/" + id + "/credentials/api_key")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // 11. 非法 field → 静态 golden（"unknown credential field: bogus"）
        mockMvc.perform(delete("/api/v1/mcp-services/" + id + "/credentials/bogus")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(content().bytes(goldenBytes("mcp-credentials-bad-field.json")));

        // 12. metadata（无快照）→ 静态 golden（{"data":null,"success":true}）
        mockMvc.perform(get("/api/v1/mcp-services/" + id + "/metadata")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().bytes(goldenBytes("mcp-metadata.json")));

        // 13. test：结构化断言（消息含网络错误文案，两侧必然不同）
        MvcResult tested = mockMvc.perform(post("/api/v1/mcp-services/" + id + "/test")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String testBody = tested.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(testBody.contains("\"success\":true"), "test 信封应为成功: " + testBody);
        assertTrue(testBody.contains("\"success\":false"), "内部连接结果应为失败: " + testBody);

        // 14. tools：服务已被 update 停用 → 结构化断言错误码
        MvcResult tools = mockMvc.perform(get("/api/v1/mcp-services/" + id + "/tools")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isInternalServerError())
                .andReturn();
        String toolsBody = tools.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(toolsBody.contains("\"code\":1007") && toolsBody.contains("is not enabled"),
                "停用服务取工具应报 1007: " + toolsBody);

        // 15. delete → 静态 golden
        mockMvc.perform(delete("/api/v1/mcp-services/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().bytes(goldenBytes("mcp-delete.json")));

        // 16. 删除后列表为空
        MvcResult empty = mockMvc.perform(get("/api/v1/mcp-services")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals("{\"data\":[],\"success\":true}",
                empty.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * 带 auth_config 的 create：响应只回显**非秘密**字段（auth_type / api_key_header），
     * 密钥本身既不出现在响应里，也不明文落库（落库加密由 McpAuthConfigTypeHandler 负责，
     * 由 e2e 在真 PG 上验证；这里钉住响应侧的剥离契约）。
     */
    @Test
    void createWithAuthConfigEchoesOnlyNonSecretFields() throws Exception {
        String token = login("java-phase1@weknora.test");
        MvcResult r = mockMvc.perform(post("/api/v1/mcp-services")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"golden-mcp-auth\",\"description\":\"auth variant\","
                                + "\"enabled\":true,\"transport_type\":\"http-streamable\","
                                + "\"url\":\"" + MCP_URL + "\","
                                + "\"auth_config\":{\"auth_type\":\"api_key\","
                                + "\"api_key\":\"sk-golden-secret\",\"api_key_header\":\"X-Tenant-Key\"}}"))
                .andExpect(status().isOk())
                .andReturn();
        String actual = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(mask(golden("mcp-create-auth.json")), mask(actual),
                "create(auth_config) 应与 golden 一致（掩码后）");
        assertFalse(actual.contains("sk-golden-secret"), "明文密钥不得出现在响应里: " + actual);
        assertTrue(actual.contains("\"api_key_header\":\"X-Tenant-Key\""),
                "非秘密的结构配置应回显: " + actual);
    }

    /**
     * SSRF 拒绝：create 带直连内网 IP 的 URL → 400 + 完整中文错误文案。
     *
     * 用直连 IP 而非域名，是为了让拒绝理由**不依赖白名单状态**（本类 @BeforeEach 放行了
     * mcp.example.com 以便测成功路径），从而可以逐字节比对 golden。
     */
    @Test
    void createRejectsSsrfUnsafeUrl() throws Exception {
        String token = login("java-phase1@weknora.test");
        mockMvc.perform(post("/api/v1/mcp-services")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"ssrf-probe\",\"transport_type\":\"http-streamable\","
                                + "\"url\":\"http://10.0.0.1/mcp\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().bytes(goldenBytes("mcp-create-ssrf-rejected.json")));
    }

    /** Viewer 无权创建 MCP 服务（对照 Go g.Admin()）。 */
    @Test
    void createForbiddenForViewer() throws Exception {
        String token = login("java-phase1-viewer@weknora.test");
        mockMvc.perform(post("/api/v1/mcp-services")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"v-mcp\",\"transport_type\":\"http-streamable\",\"url\":\"" + MCP_URL + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().bytes(goldenBytes("mcp-create-forbidden-viewer.json")));
    }

    // ── 工具 ─────────────────────────────────────────────────────────────

    private String login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"Passw0rd!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        java.util.regex.Matcher m = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(body);
        assertTrue(m.find(), "login 响应应含 token: " + body);
        return m.group(1);
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

    private static byte[] goldenBytes(String name) throws Exception {
        return new ClassPathResource("contracts/" + name).getInputStream().readAllBytes();
    }

    private static String extractUuid(String body) {
        java.util.regex.Matcher m = Pattern.compile(
                "\"id\":\"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\"").matcher(body);
        assertTrue(m.find(), "响应应含 id: " + body);
        return m.group(1);
    }

    /** 与 golden 比对前的统一掩码：UUID + 时间戳 */
    private static String mask(String s) {
        // PR4 语义比较入口：键序/转义归一后再掩码
        s = com.ragagent.support.ContractJson.semantic(s);
        String out = UUID_KEY_PATTERN.matcher(s).replaceAll("\"$1\":\"<id>\"");
        return TS_PATTERN.matcher(out).replaceAll("\"<ts>\"");
    }
}
