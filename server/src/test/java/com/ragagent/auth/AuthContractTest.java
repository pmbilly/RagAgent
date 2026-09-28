package com.ragagent.auth;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.regex.Pattern;

import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.TenantMember;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.domain.UserPreferences;
import com.ragagent.auth.mapper.TenantMapper;
import com.ragagent.auth.mapper.TenantMemberMapper;
import com.ragagent.TestSchema;
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
 * 阶段 1 契约测试：auth 登录 + AuthFilter 全链，对照 golden 逐字节比对。
 *
 * golden 来源：Go dev server（localhost:8080，2026-09-17 录制，见 scripts/record-golden.sh）。
 * 动态字段（token / refresh_token / 时间戳）两侧做同一种掩码后再比对；
 * 静态 golden（4xx/401/409/400）直接逐字节断言。
 *
 * H2 种子数据镜像 dev DB 的 Phase 1 测试用户：
 *   A 11111111-...-501 phase1test        空间 10002 owner
 *   B 11111111-...-502 phase1tenantless  无空间
 *   C 11111111-...-503 phase1inactive    已停用
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthContractTest {

    private static final String BCRYPT = "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK"; // Passw0rd!
    private static final OffsetDateTime TS = OffsetDateTime.of(2026, 9, 17, 10, 0, 0, 123456000, ZoneOffset.ofHours(8));

    private static final Pattern TS_PATTERN = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})");

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

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);

        Tenant tenant = new Tenant();
        tenant.setId(10002L);
        tenant.setName("phase1-test-tenant");
        tenant.setDescription("");
        tenant.setStatus("active");
        tenant.setBusiness("");
        tenant.setStorageQuota(10737418240L);
        tenant.setStorageUsed(0L);
        tenant.setCreatedAt(TS);
        tenant.setUpdatedAt(TS);
        tenantMapper.insert(tenant);

        insertUser("11111111-2222-3333-4444-555555555501", "phase1test", "java-phase1@weknora.test", 10002L, true);
        insertUser("11111111-2222-3333-4444-555555555502", "phase1tenantless", "java-phase1-tenantless@weknora.test", null, true);
        insertUser("11111111-2222-3333-4444-555555555503", "phase1inactive", "java-phase1-inactive@weknora.test", null, false);

        TenantMember member = new TenantMember();
        member.setUserId("11111111-2222-3333-4444-555555555501");
        member.setTenantId(10002L);
        member.setRole("owner");
        member.setStatus("active");
        member.setJoinedAt(TS);
        member.setCreatedAt(TS);
        member.setUpdatedAt(TS);
        memberMapper.insert(member);
    }

    private void insertUser(String id, String username, String email, Long tenantId, boolean active) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(BCRYPT);
        user.setTenantId(tenantId);
        user.setIsActive(active);
        user.setPreferences(new UserPreferences());
        user.setCreatedAt(TS);
        user.setUpdatedAt(TS);
        userMapper.insert(user);
    }

    // ── 静态 golden（逐字节） ─────────────────────────────────────────────

    @Test
    void loginValidationErrors() throws Exception {
        String golden = golden("login-validation-errors.json");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"x\",\"password\":\"y\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(golden, true));
    }

    @Test
    void loginWrongPassword() throws Exception {
        String golden = golden("login-wrong-password.json");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"java-phase1@weknora.test\",\"password\":\"WrongPass1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json(golden, true));
    }

    @Test
    void loginUnknownUser() throws Exception {
        String golden = golden("login-unknown-user.json");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"nobody@nowhere.test\",\"password\":\"whatever\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json(golden, true));
    }

    @Test
    void loginInactive() throws Exception {
        String golden = golden("login-inactive.json");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"java-phase1-inactive@weknora.test\",\"password\":\"Passw0rd!\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json(golden, true));
    }

    @Test
    void loginEmptyBody() throws Exception {
        String golden = golden("login-empty-body.json");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(golden, true));
    }

    @Test
    void tenantRequired() throws Exception {
        String token = login("java-phase1-tenantless@weknora.test");
        String golden = golden("tenant-required-409.json");
        mockMvc.perform(get("/api/v1/knowledgebases")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(content().json(golden, true));
    }

    @Test
    void xTenantIdInvalid() throws Exception {
        String token = login("java-phase1@weknora.test");
        String golden = golden("x-tenant-id-invalid-400.json");
        mockMvc.perform(get("/api/v1/knowledgebases")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(golden, true));
    }

    @Test
    void xTenantIdForbidden() throws Exception {
        String token = login("java-phase1@weknora.test");
        String golden = golden("x-tenant-id-forbidden-403.json");
        mockMvc.perform(get("/api/v1/knowledgebases")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", "99999999"))
                .andExpect(status().isForbidden())
                .andExpect(content().json(golden, true));
    }

    // ── 动态 golden（掩码后逐字节） ───────────────────────────────────────

    @Test
    void loginSuccess() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"java-phase1@weknora.test\",\"password\":\"Passw0rd!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String actual = result.getResponse().getContentAsString();
        String expected = mask(golden("login-success.json"));
        assertTrue(actual.contains(systemOffsetSuffix()),
                "时间戳应输出 JVM 本地时区偏移（对照 Go time.Time 本地时区行为），实际: " + actual);
        // PR4：掩码正则依赖原字节布局 → actual 侧保持原文，仅掩码比对
        org.junit.jupiter.api.Assertions.assertEquals(expected, mask(actual),
                "掩码后应与 golden 一致");
    }

    /** 对照 Go：refresh token 当 Bearer 用 → 校验失败 → 401 "invalid or expired token" */
    @Test
    void refreshTokenRejectedAsBearer() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"java-phase1@weknora.test\",\"password\":\"Passw0rd!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String body = login.getResponse().getContentAsString();
        java.util.regex.Matcher m =
                Pattern.compile("\"refresh_token\":\"([^\"]+)\"").matcher(body);
        assertTrue(m.find(), "login 响应应含 refresh_token: " + body);
        mockMvc.perform(get("/api/v1/knowledgebases")
                        .header("Authorization", "Bearer " + m.group(1)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("{\"error\":\"Unauthorized: invalid or expired token\"}"));
    }

    // ── 工具 ──────────────────────────────────────────────────────────────

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

    /** 与 golden 比对前对动态字段做同一种掩码 */
    private static String mask(String s) {
        // PR4 语义比较入口：键序/转义归一后再掩码
        s = com.ragagent.support.ContractJson.semantic(s);
        String out = s.replaceAll("\"token\":\"[^\"]*\"", "\"token\":\"<masked>\"");
        out = out.replaceAll("\"refresh_token\":\"[^\"]*\"", "\"refresh_token\":\"<masked>\"");
        out = TS_PATTERN.matcher(out).replaceAll("\"<ts>\"");
        return out;
    }

    private static String systemOffsetSuffix() {
        return java.time.ZoneId.systemDefault().getRules()
                .getOffset(java.time.Instant.now()).toString();
    }
}
