package com.ragagent.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.ragagent.auth.domain.TenantMember;
import com.ragagent.auth.domain.Tenant;
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
 * 模型配置模块契约测试（CRUD + providers + credentials 子资源）。
 *
 * <p>fixture 锚定本仓行为：动态字段（模型 UUID / 时间戳）两侧同掩码后语义比对；
 * 静态 fixture 直接字节断言。请求体与响应键名均为 camelCase（字段名即 JSON 键名）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ModelContractTest {

    private static final String BCRYPT = "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK"; // Passw0rd!
    private static final OffsetDateTime TS = OffsetDateTime.of(2026, 9, 17, 10, 0, 0, 123456000, ZoneOffset.ofHours(8));

    private static final Pattern TS_PATTERN = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})");
    private static final Pattern MODEL_ID_PATTERN = Pattern.compile(
            "\"id\":\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"");

    private static final String CREATE_BODY = "{\"name\":\"golden-lifecycle\",\"displayName\":\"Golden Lifecycle\","
            + "\"type\":\"KnowledgeQA\",\"source\":\"remote\",\"description\":\"phase2 golden\","
            + "\"parameters\":{\"baseUrl\":\"https://api.deepseek.com/v1\",\"apiKey\":\"sk-golden-secret\","
            + "\"provider\":\"openai\",\"supportsVision\":true,\"contextWindow\":128000,\"maxOutputTokens\":4096}}";
    private static final String UPDATE_BODY = "{\"name\":\"golden-lifecycle-v2\",\"displayName\":\"Golden V2\","
            + "\"description\":\"updated\","
            + "\"parameters\":{\"baseUrl\":\"https://api.deepseek.com/v1\",\"provider\":\"openai\","
            + "\"supportsVision\":false,\"contextWindow\":64000}}";

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
        // 对照 Go dev server 录制时的 SSRF_WHITELIST_EXTRA（含 api.deepseek.com，豁免 DNS 检查）
        ssrfGuard.reloadWhitelist("api.deepseek.com");
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);

        Tenant tenant = new Tenant();
        tenant.setId(10002L);
        tenant.setName("phase1-test-tenant");
        tenant.setStatus("active");
        tenantMapper.insert(tenant);

        insertUser("11111111-2222-3333-4444-555555555501", "phase1test", "java-phase1@weknora.test");
        insertUser("11111111-2222-3333-4444-555555555504", "phase1viewer", "java-phase1-viewer@weknora.test");
        insertMember("11111111-2222-3333-4444-555555555501", "owner");
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

    // ── 静态 golden ───────────────────────────────────────────────────────

    @Test
    void listModelsEmpty() throws Exception {
        MvcResult gb1 = mockMvc.perform(get("/api/v1/models").header("Authorization", "Bearer " + loginOwner()))
                .andExpect(status().isOk())
            .andReturn();
        assertGolden(gb1, "models-list-empty.json");
    }

    @Test
    void providers() throws Exception {
        MvcResult gb2 = mockMvc.perform(get("/api/v1/models/providers").header("Authorization", "Bearer " + loginOwner()))
                .andExpect(status().isOk())
            .andReturn();
        assertGolden(gb2, "model-providers.json");
    }

    @Test
    void providersFilteredByChat() throws Exception {
        MvcResult gb3 = mockMvc.perform(get("/api/v1/models/providers?modelType=chat")
                        .header("Authorization", "Bearer " + loginOwner()))
                .andExpect(status().isOk())
            .andReturn();
        assertGolden(gb3, "model-providers-chat.json");
    }

    @Test
    void getModelNotFound() throws Exception {
        MvcResult gb4 = mockMvc.perform(get("/api/v1/models/no-such-model-id")
                        .header("Authorization", "Bearer " + loginOwner()))
                .andExpect(status().isNotFound())
            .andReturn();
        assertGolden(gb4, "model-not-found.json");
    }

    @Test
    void weknoracloudStatus() throws Exception {
        MvcResult gb5 = mockMvc.perform(get("/api/v1/models/weknoracloud/status")
                        .header("Authorization", "Bearer " + loginOwner()))
                .andExpect(status().isOk())
            .andReturn();
        assertGolden(gb5, "weknoracloud-status.json");
    }

    /** 凭证保存的校验路径（外呼校验路径需真实 WeKnoraCloud，不做集成）。 */
    @Test
    void weknoracloudCredentialsValidation() throws Exception {
        MvcResult gb6 = mockMvc.perform(post("/api/v1/weknoracloud/credentials")
                        .header("Authorization", "Bearer " + loginOwner())
                        .contentType("application/json")
                        .content("{\"appId\":\"app-x\"}"))
                .andExpect(status().isBadRequest())
            .andReturn();
        assertGolden(gb6, "weknoracloud-cred-validation.json");
    }

    @Test
    void createValidationError() throws Exception {
        MvcResult gb7 = mockMvc.perform(post("/api/v1/models")
                        .header("Authorization", "Bearer " + loginOwner())
                        .contentType("application/json")
                        .content("{\"type\":\"KnowledgeQA\",\"source\":\"openai\","
                                + "\"parameters\":{\"baseUrl\":\"https://api.openai.com/v1\"}}"))
                .andExpect(status().isBadRequest())
            .andReturn();
        assertGolden(gb7, "model-create-validation.json");
    }

    @Test
    void createSsrfBlocked() throws Exception {
        MvcResult gb8 = mockMvc.perform(post("/api/v1/models")
                        .header("Authorization", "Bearer " + loginOwner())
                        .contentType("application/json")
                        .content("{\"name\":\"ssrf-test\",\"type\":\"KnowledgeQA\",\"source\":\"openai\","
                                + "\"parameters\":{\"baseUrl\":\"http://127.0.0.1:8080/v1\",\"apiKey\":\"sk-test\"}}"))
                .andExpect(status().isBadRequest())
            .andReturn();
        assertGolden(gb8, "model-create-ssrf.json");
    }

    @Test
    void createForbiddenForViewer() throws Exception {
        MvcResult gb9 = mockMvc.perform(post("/api/v1/models")
                        .header("Authorization", "Bearer " + loginViewer())
                        .contentType("application/json")
                        .content("{\"name\":\"v\",\"type\":\"KnowledgeQA\",\"source\":\"openai\","
                                + "\"parameters\":{\"baseUrl\":\"https://api.openai.com/v1\"}}"))
                .andExpect(status().isForbidden())
            .andReturn();
        assertGolden(gb9, "model-create-forbidden-viewer.json");
    }

    // ── 生命周期（掩码后逐字节） ──────────────────────────────────────────

    @Test
    void modelLifecycle() throws Exception {
        String token = loginOwner();

        // create → 201
        MvcResult created = mockMvc.perform(post("/api/v1/models")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andReturn();
        assertEquals(mask(golden("model-create.json")), mask(created.getResponse().getContentAsString(StandardCharsets.UTF_8)),
                "create 响应应与 golden 一致（掩码后）");
        String modelId = extractModelId(created.getResponse().getContentAsString(StandardCharsets.UTF_8));

        // get → 200
        MvcResult got = mockMvc.perform(get("/api/v1/models/" + modelId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(mask(golden("model-get.json")), mask(got.getResponse().getContentAsString(StandardCharsets.UTF_8)),
                "get 响应应与 golden 一致（掩码后）");

        // update → 200（golden 锁定空 type/source 覆盖语义 + 内存旧时间戳行为）
        MvcResult updated = mockMvc.perform(put("/api/v1/models/" + modelId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(UPDATE_BODY))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(mask(golden("model-update.json")), mask(updated.getResponse().getContentAsString(StandardCharsets.UTF_8)),
                "update 响应应与 golden 一致（掩码后）");

        // credentials PUT → 200（静态）
        MvcResult gb10 = mockMvc.perform(put("/api/v1/models/" + modelId + "/credentials")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"apiKey\":\"sk-rotated-key\"}"))
                .andExpect(status().isOk())
            .andReturn();
        assertGolden(gb10, "model-cred-put.json");

        // credentials PUT（空 body = 查询已配置状态）→ 200（静态）
        MvcResult gb11 = mockMvc.perform(put("/api/v1/models/" + modelId + "/credentials")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
            .andReturn();
        assertGolden(gb11, "model-cred-get.json");

        // credentials DELETE → 204
        mockMvc.perform(delete("/api/v1/models/" + modelId + "/credentials/api_key")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        // delete → 204 无响应体
        mockMvc.perform(delete("/api/v1/models/" + modelId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    // ── 工具 ──────────────────────────────────────────────────────────────

    private String loginOwner() throws Exception {
        return login("java-phase1@weknora.test");
    }

    private String loginViewer() throws Exception {
        return login("java-phase1-viewer@weknora.test");
    }

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


    private static String extractModelId(String body) {
        java.util.regex.Matcher m = Pattern.compile(
                "\"id\":\"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\"").matcher(body);
        assertTrue(m.find(), "响应应含模型 UUID: " + body);
        return m.group(1);
    }

    /** 与 golden 比对前对动态字段做同一种掩码（UUID + 时间戳） */
    // ── 金片对比（B2 统一基建：语义归一 + strip + -Dcontract.refresh 重录） ──

    private static void assertGolden(org.springframework.test.web.servlet.MvcResult r,
            String name) throws Exception {
        com.ragagent.support.GoldenContract.assertEquals("src/test/resources/contracts",
                name, ModelContractTest::mask,
                r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String mask(String s) {
        // PR4 语义比较入口：键序/转义归一后再掩码
        s = com.ragagent.support.ContractJson.semantic(s);
        String out = MODEL_ID_PATTERN.matcher(s).replaceAll("\"id\":\"<id>\"");
        out = TS_PATTERN.matcher(out).replaceAll("\"<ts>\"");
        return out;
    }
}
