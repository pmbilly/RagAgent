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
    /**
     * 掩码按键名匹配：**键改名必须同步这个正则**，否则夹具里会混进随机 UUID
     * （S3 踩过：att-get 的 sessionId）。这里覆盖 id 与 serviceId（M1/M4 后的拼写）。
     */
    private static final Pattern UUID_KEY_PATTERN = Pattern.compile(
            "\"(id|service_id|serviceId)\":\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"");

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
                                + "\"enabled\":true,\"transportType\":\"http-streamable\","
                                + "\"url\":\"" + MCP_URL + "\",\"headers\":{\"X-Custom\":\"v1\"}}"))
                .andExpect(status().isCreated())   // §1.15：创建 → 201
                .andReturn();
        String createdBody = created.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertGoldenBody("mcp-create.json", createdBody, "mcp-create 应与 golden 一致（掩码后）");
        String id = extractUuid(createdBody);

        // 2. list → 掩码比对
        MvcResult list = mockMvc.perform(get("/api/v1/mcp-services")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertGoldenBody("mcp-list.json", list.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "mcp-list 应与 golden 一致（掩码后）");

        // 3. get → 掩码比对
        MvcResult got = mockMvc.perform(get("/api/v1/mcp-services/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertGoldenBody("mcp-get.json", got.getResponse().getContentAsString(StandardCharsets.UTF_8),
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
        assertGoldenBody("mcp-update.json", updated.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "mcp-update 应与 golden 一致（掩码后）");

        // 6. tool-approvals 空 → 裸 []（§2.1）
        mockMvc.perform(get("/api/v1/mcp-services/" + id + "/tool-approvals")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));

        // 7. 设置审批策略 → 204 无响应体（§14.9n M4：不再回 {"success":true}）
        mockMvc.perform(put("/api/v1/mcp-services/" + id + "/tool-approvals/golden_tool")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"requireApproval\":true}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        // 8. tool-approvals 有 1 项 → 掩码比对（含生成的 id 与时间戳）
        MvcResult approvals = mockMvc.perform(get("/api/v1/mcp-services/" + id + "/tool-approvals")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertGoldenBody("mcp-tool-approvals.json",
                approvals.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "mcp-tool-approvals 应与 golden 一致（掩码后）");

        // 9. credentials put → 静态 golden（只暴露 configured 布尔）
        MvcResult credPut = mockMvc.perform(put("/api/v1/mcp-services/" + id + "/credentials")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"apiKey\":\"sk-golden-123\",\"token\":\"tok-golden-456\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertGoldenBody("mcp-credentials-put.json",
                credPut.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "凭据 PUT 应与 golden 一致（掩码后）");

        // 10. credentials delete → 204（golden 是空响应体）
        mockMvc.perform(delete("/api/v1/mcp-services/" + id + "/credentials/apiKey")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // 11. 非法 field → 静态 golden（"unknown credential field: bogus"）
        mockMvc.perform(delete("/api/v1/mcp-services/" + id + "/credentials/bogus")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(content().bytes(goldenBytes("mcp-credentials-bad-field.json")));

        // 12. metadata（无快照）→ 静态 golden（{"data":null,"success":true}）
        MvcResult meta = mockMvc.perform(get("/api/v1/mcp-services/" + id + "/metadata")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertGoldenBody("mcp-metadata.json",
                meta.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "从未同步应为 null");

        // 13. test：结构化断言（消息含网络错误文案，两侧必然不同）
        MvcResult tested = mockMvc.perform(post("/api/v1/mcp-services/" + id + "/test")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String testBody = tested.getResponse().getContentAsString(StandardCharsets.UTF_8);
        // §2.1：裸 McpTestResult（不再有外层信封），success=false 是**业务结论**
        assertTrue(testBody.startsWith("{\"success\":false"), "应为裸 McpTestResult: " + testBody);
        assertTrue(testBody.contains("\"message\":"), testBody);

        // 14. tools：服务已被 update 停用 → 结构化断言错误码
        MvcResult tools = mockMvc.perform(get("/api/v1/mcp-services/" + id + "/tools")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isInternalServerError())
                .andReturn();
        String toolsBody = tools.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(toolsBody.contains("\"code\":1007") && toolsBody.contains("is not enabled"),
                "停用服务取工具应报 1007: " + toolsBody);

        // 15. delete → 204（§1.13，无响应体）
        mockMvc.perform(delete("/api/v1/mcp-services/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        // 16. 删除后列表为空
        MvcResult empty = mockMvc.perform(get("/api/v1/mcp-services")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals("[]", empty.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "§2.1：空列表就是裸 []");
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
                                + "\"enabled\":true,\"transportType\":\"http-streamable\","
                                + "\"url\":\"" + MCP_URL + "\","
                                + "\"authConfig\":{\"authType\":\"api_key\","
                                + "\"apiKey\":\"sk-golden-secret\",\"apiKeyHeader\":\"X-Tenant-Key\"}}"))
                .andExpect(status().isCreated())   // §1.15：创建 → 201
                .andReturn();
        String actual = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertGoldenBody("mcp-create-auth.json", actual,
                "create(auth_config) 应与 golden 一致（掩码后）");
        assertFalse(actual.contains("sk-golden-secret"), "明文密钥不得出现在响应里: " + actual);
        assertTrue(actual.contains("\"apiKeyHeader\":\"X-Tenant-Key\""),
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
                        .content("{\"name\":\"ssrf-probe\",\"transportType\":\"http-streamable\","
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
                        .content("{\"name\":\"v-mcp\",\"transportType\":\"http-streamable\",\"url\":\"" + MCP_URL + "\"}"))
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


    /**
     * 夹具重录开关（同 EmbedContractTest）：{@code -Dcontract.refresh=true} 时把掩码后的实际响应
     * 写回夹具，用于换锚批（本次 M1 改键名/去信封/改状态码，一次影响 5 个 golden）。
     */
    private static final boolean REFRESH_FIXTURES = Boolean.getBoolean("contract.refresh");

    private void assertGoldenBody(String name, String actual, String label) throws Exception {
        if (REFRESH_FIXTURES) {
            java.nio.file.Path path = java.nio.file.Paths.get("src/test/resources/contracts", name);
            java.nio.file.Files.writeString(path, mask(actual));
            System.out.println("REFRESH " + name);
            return;
        }
        assertEquals(mask(golden(name)), mask(actual), label);
    }

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
