package com.ragagent.sandbox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 波 3 子批 1 controller 冒烟测试（MockMvc + H2）：只钉确定性形态——401/404/binding/
 * workspace-policy 成功体/RBAC 拒绝。**不写 golden 契约测试**（主会话稍后录 Go 实录
 * golden 后再补逐字节比对，任务书明确）。
 *
 * <p>种子模式复用 {@code AuthRegisterContractTest}：租户 10002 + owner/admin/viewer 三角色。
 * 中文断言按 UTF-8 原始字节（MockMvc 缺 charset 时按 ISO-8859-1 解码会 mojibake，
 * §9 波 2 终扫批的坑）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SandboxConfigControllerSmokeTest {

    private static final String BCRYPT = "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK";
    private static final long TENANT = 10002L;
    private static final String OWNER = "11111111-2222-3333-4444-555555555501";
    private static final String VIEWER = "11111111-2222-3333-4444-555555555502";
    private static final String OWNER_EMAIL = "java-phase1@weknora.test";
    private static final String VIEWER_EMAIL = "java-viewer@weknora.test";

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
    void seed() throws Exception {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);

        Tenant tenant = new Tenant();
        tenant.setId(TENANT);
        tenant.setName("phase1-test-tenant");
        tenant.setStatus("active");
        tenantMapper.insert(tenant);

        seedUser(OWNER, "phase1test", OWNER_EMAIL, "owner");
        seedUser(VIEWER, "javaviewer", VIEWER_EMAIL, "viewer");
    }

    private void seedUser(String id, String username, String email, String role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
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
        member.setJoinedAt(OffsetDateTime.of(2026, 9, 1, 10, 0, 0, 0, java.time.ZoneOffset.UTC));
        memberMapper.insert(member);
    }

    // ── 辅助 ────────────────────────────────────────────────────────────

    private String login(String email) throws Exception {
        MvcResult result = mockMvc.perform(json(post("/api/v1/auth/login"),
                "{\"email\":\"" + email + "\",\"password\":\"Passw0rd!\"}")).andReturn();
        assertEquals(200, result.getResponse().getStatus(), raw(result));
        Matcher m = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(raw(result));
        assertTrue(m.find(), "login 响应应含 token: " + raw(result));
        return m.group(1);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, String body) {
        return req.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String raw(MvcResult r) throws Exception {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    // ── 401：无凭据（auth 中间件直写的纯字符串形态） ─────────────────────

    @Test
    void listWithoutCredentialsIsUnauthorizedPlainText() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/sandbox-configs")).andReturn();
        assertEquals(401, r.getResponse().getStatus());
        assertEquals("{\"error\":\"Unauthorized: missing authentication\"}", raw(r));
    }

    // ── 404：未知配置（AppError 信封） ──────────────────────────────────

    @Test
    void getUnknownConfigIsNotFoundEnvelope() throws Exception {
        String token = login(OWNER_EMAIL);
        MvcResult r = mockMvc.perform(get("/api/v1/sandbox-configs/no-such-id")
                        .header("Authorization", "Bearer " + token)).andReturn();
        assertEquals(404, r.getResponse().getStatus());
        assertEquals("{\"error\":{\"code\":1003,\"details\":null,"
                        + "\"message\":\"sandbox config not found\"},\"success\":false}",
                raw(r));
    }

    // ── binding：EOF 与 validator 原文 ──────────────────────────────────

    @Test
    void createWithEmptyBodyIsEofBadRequest() throws Exception {
        String token = login(OWNER_EMAIL);
        MvcResult r = mockMvc.perform(post("/api/v1/sandbox-configs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)).andReturn();
        assertEquals(400, r.getResponse().getStatus());
        assertEquals("{\"error\":{\"code\":1000,\"details\":null,\"message\":\"EOF\"},\"success\":false}",
                raw(r));
    }

    @Test
    void createWithoutNameIsValidatorRequiredMessage() throws Exception {
        String token = login(OWNER_EMAIL);
        MvcResult r = mockMvc.perform(json(post("/api/v1/sandbox-configs"), "{\"config\":null}")
                        .header("Authorization", "Bearer " + token)).andReturn();
        assertEquals(400, r.getResponse().getStatus());
        assertEquals("{\"error\":{\"code\":1000,\"details\":null,\"message\":"
                        + "\"Key: 'sandboxConfigRequest.Name' Error:Field validation for 'Name' "
                        + "failed on the 'required' tag\"},\"success\":false}",
                raw(r));
    }

    // ── workspace-policy：成功体字母序 + Viewer 403 + 策略行影响 List ─────

    @Test
    void workspacePolicyToggleRoundTrip() throws Exception {
        String owner = login(OWNER_EMAIL);
        MvcResult on = mockMvc.perform(json(put("/api/v1/sandbox-configs/workspace-policy"),
                        "{\"scripts_disabled\":true}")
                        .header("Authorization", "Bearer " + owner)).andReturn();
        assertEquals(200, on.getResponse().getStatus());
        assertEquals("{\"success\":true,\"workspace_scripts_disabled\":true}", raw(on));

        // List 现在带 true（空列表 data 是 [] 非 null）
        MvcResult list = mockMvc.perform(get("/api/v1/sandbox-configs")
                        .header("Authorization", "Bearer " + owner)).andReturn();
        assertEquals(200, list.getResponse().getStatus());
        assertEquals("{\"data\":[],\"success\":true,\"workspace_scripts_disabled\":true}", raw(list));

        // 关掉
        MvcResult off = mockMvc.perform(json(put("/api/v1/sandbox-configs/workspace-policy"),
                        "{\"scripts_disabled\":false}")
                        .header("Authorization", "Bearer " + owner)).andReturn();
        assertEquals(200, off.getResponse().getStatus());
        assertEquals("{\"success\":true,\"workspace_scripts_disabled\":false}", raw(off));
        MvcResult list2 = mockMvc.perform(get("/api/v1/sandbox-configs")
                        .header("Authorization", "Bearer " + owner)).andReturn();
        assertEquals("{\"data\":[],\"success\":true,\"workspace_scripts_disabled\":false}", raw(list2));

        // body null 字面量按零值绑定（Go 语义）
        MvcResult nullBody = mockMvc.perform(json(put("/api/v1/sandbox-configs/workspace-policy"), "null")
                        .header("Authorization", "Bearer " + owner)).andReturn();
        assertEquals(200, nullBody.getResponse().getStatus());
        assertEquals("{\"success\":true,\"workspace_scripts_disabled\":false}", raw(nullBody));
    }

    @Test
    void workspacePolicyRequiresAdmin() throws Exception {
        String viewer = login(VIEWER_EMAIL);
        MvcResult r = mockMvc.perform(json(put("/api/v1/sandbox-configs/workspace-policy"),
                        "{\"scripts_disabled\":true}")
                        .header("Authorization", "Bearer " + viewer)).andReturn();
        assertEquals(403, r.getResponse().getStatus());
        assertEquals("{\"error\":\"Forbidden: insufficient workspace role\"}", raw(r));
    }

    // ── Delete 未知名：404 信封（c.Error 直通） ──────────────────────────

    @Test
    void deleteUnknownConfigIsNotFoundEnvelope() throws Exception {
        String owner = login(OWNER_EMAIL);
        MvcResult r = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/sandbox-configs/no-such-id")
                        .header("Authorization", "Bearer " + owner)).andReturn();
        assertEquals(404, r.getResponse().getStatus());
        assertEquals("{\"error\":{\"code\":1003,\"details\":null,"
                + "\"message\":\"sandbox config not found\"},\"success\":false}", raw(r));
    }

    // ── Create/Get 往返：201 信封 + 投影键序 + 掩码 + jsonb 回读 ─────────

    @Test
    void createThenGetRoundTripShape() throws Exception {
        String owner = login(OWNER_EMAIL);
        // 无密钥载荷（api_key 省略）——掩码/加密已由域/service 层测试覆盖，
        // 这里钉 HTTP 面的形状：201、信封字母序、struct 声明序、description omitempty
        MvcResult created = mockMvc.perform(json(post("/api/v1/sandbox-configs"),
                        "{\"name\":\"smoke\",\"description\":\"d\",\"config\":{\"sandbox_type\":\"cube\","
                                + "\"allow_private_endpoints\":true,"
                                + "\"cube\":{\"api_url\":\"http://127.0.0.1:33000\","
                                + "\"proxy_url\":\"http://127.0.0.1:80\","
                                + "\"sandbox_domain\":\"cube.app\",\"template_id\":\"tpl-1\"}}}")
                        .header("Authorization", "Bearer " + owner)).andReturn();
        assertEquals(201, created.getResponse().getStatus(), raw(created));
        String body = raw(created);
        assertTrue(body.startsWith("{\"data\":{\"id\":\""), "data 在 success 之前（字母序）: " + body);
        assertTrue(body.contains("\"name\":\"smoke\""));
        assertTrue(body.contains("\"description\":\"d\""));
        assertTrue(body.contains("\"sandbox_type\":\"cube\""));
        // config 内嵌形状（omitempty：api_key 空缺席）
        assertTrue(body.contains("\"cube\":{\"api_url\":\"http://127.0.0.1:33000\","
                + "\"proxy_url\":\"http://127.0.0.1:80\",\"sandbox_domain\":\"cube.app\","
                + "\"template_id\":\"tpl-1\"}}"), body);
        assertTrue(body.endsWith(",\"success\":true}"), body);
        // created_at/updated_at 恒输出（RFC3339 偏移形态，值动态）
        assertTrue(body.contains("\"created_at\":\"20") && body.contains("\"updated_at\":\"20"), body);

        java.util.regex.Matcher idMatcher =
                java.util.regex.Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(body);
        assertTrue(idMatcher.find(), body);
        String id = idMatcher.group(1);

        // Get：jsonb 回读 + 掩码投影（maskSecrets=true；空密钥保持空 → 键缺席）。
        // 时间戳不与 Create 逐字节比（H2 亚微秒截断）；形状一致即可
        MvcResult got = mockMvc.perform(get("/api/v1/sandbox-configs/" + id)
                        .header("Authorization", "Bearer " + owner)).andReturn();
        assertEquals(200, got.getResponse().getStatus(), raw(got));
        String gotBody = raw(got);
        assertTrue(gotBody.contains("\"id\":\"" + id + "\""));
        assertTrue(gotBody.contains("\"cube\":{\"api_url\":\"http://127.0.0.1:33000\","
                + "\"proxy_url\":\"http://127.0.0.1:80\",\"sandbox_domain\":\"cube.app\","
                + "\"template_id\":\"tpl-1\"}}"), gotBody);
        assertTrue(gotBody.contains("\"name\":\"smoke\""), gotBody);
    }
}
