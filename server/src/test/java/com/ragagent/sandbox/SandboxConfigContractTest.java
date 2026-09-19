package com.ragagent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 波 3 sandbox 子批 1 契约测试：/sandbox-configs 配置 CRUD 8 端点（对照 golden 逐字节）。
 *
 * golden 来源：Go dev server（localhost:8080，2026-09-19 录制，
 * scripts/record-sbx-golden.sh，27 条 sbx-*.json）。
 *
 * 场景顺序严格复刻录制脚本（同请求序列有状态依赖）：
 * auth/binding → 校验 400 族 → CRUD 成功路径 → workspace-policy 开关 →
 * templates/query 的 pre-provider 校验分支。
 *
 * 掩码面：config id（uuid）与 created_at/updated_at（时戳；**Go 侧同一运行内
 * 时区形态都不稳定**——create 响应 Z、list 响应 +08:00，掩码是硬要求）。
 * api_key/env_vars 值两侧都由响应投影掩成 "***"（静态，无需掩码）。
 *
 * 确定性分支的来源：dev 两侧 WEKNORA_SANDBOX_DOCKER_ENABLED 未设（docker 恒
 * 禁用）、Create 不拨号、Delete/Update 的 provider 列举失败走固定文案 409/继续保存。
 * URL 守卫在必填校验<b>之前</b>——sbx-cube-incomplete 落在私网拒绝分支，是实测出的
 * 分支顺序（golden 原样保留）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SandboxConfigContractTest {

    /**
     * 测试 JVM 没有 SYSTEM_AES_KEY（标准全量流程不导出该 env）——给 service 的
     * 「拒绝明文落密钥」检查一个固定 key 放行。jsonb TypeHandler 的真实加解密
     * 路径由 A/B（两侧真 server、同 key）覆盖；本测试的响应断言只看掩码 "***"，
     * 与存储是否加密无关。
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class FixedKeyCryptoConfig {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.ragagent.common.crypto.CryptoService fixedKeyCryptoService() {
            return new com.ragagent.common.crypto.CryptoService() {
                @Override
                public byte[] getAESKey() {
                    return "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
                }
            };
        }
    }

    private static final String BCRYPT = "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK"; // Passw0rd!
    private static final long TENANT = 10002L;
    private static final String OWNER = "11111111-2222-3333-4444-555555555501";
    private static final String OWNER_EMAIL = "java-phase1@weknora.test";

    private static final Pattern TS_PATTERN = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})");
    private static final Pattern UUID_VALUE = Pattern.compile(
            "\"id\":\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"");

    /** 与录制脚本逐字相同的三个配置体。 */
    private static final String CUBE_V1 =
            "{\"sandbox_type\":\"cube\",\"allow_private_endpoints\":true,\"env_vars\":"
                    + "{\"PROBE_TOKEN\":\"tok-secret-1\"},\"cube\":{\"api_url\":\"http://127.0.0.1:39171\","
                    + "\"proxy_url\":\"http://127.0.0.1:39172\",\"sandbox_domain\":\"sb.example.internal\","
                    + "\"template_id\":\"tpl-probe-1\",\"api_key\":\"sk-cube-secret-1\"}}";
    private static final String CUBE_V2 = CUBE_V1.replace("39171", "39179");

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
        tenant.setId(TENANT);
        tenant.setName("phase1-test-tenant");
        tenant.setStatus("active");
        tenantMapper.insert(tenant);

        User user = new User();
        user.setId(OWNER);
        user.setUsername("phase1test");
        user.setEmail(OWNER_EMAIL);
        user.setPasswordHash(BCRYPT);
        user.setTenantId(TENANT);
        user.setIsActive(true);
        user.setPreferences(new UserPreferences());
        userMapper.insert(user);

        TenantMember member = new TenantMember();
        member.setUserId(OWNER);
        member.setTenantId(TENANT);
        member.setRole("owner");
        member.setStatus("active");
        member.setJoinedAt(OffsetDateTime.of(2026, 9, 1, 10, 0, 0, 0, ZoneOffset.UTC));
        memberMapper.insert(member);
    }

    // ── 1) 鉴权 + binding（无库上状态） ──────────────────────────────────

    @Test
    void authAndBindingFamily() throws Exception {
        assertGolden(get("/api/v1/sandbox-configs"), 401, "sbx-noauth.json");
        assertGolden(get("/api/v1/sandbox-configs")
                .header("Authorization", "Bearer garbage.token.here"), 401, "sbx-badtoken.json");
        String owner = "Bearer " + login();

        assertGolden(post("/api/v1/sandbox-configs").header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON), 400, "sbx-empty-body.json");
        assertGolden(json(post("/api/v1/sandbox-configs").header("Authorization", owner), "{}"),
                400, "sbx-missing-name.json");
        assertGolden(json(post("/api/v1/sandbox-configs").header("Authorization", owner),
                "{\"name\":\"probe\"}"), 400, "sbx-missing-type.json");
    }

    // ── 2) 校验 400 族（类型/后端开关/URL 守卫先于必填） ─────────────────

    @Test
    void validationFamily() throws Exception {
        String owner = "Bearer " + login();

        assertGolden(json(post("/api/v1/sandbox-configs").header("Authorization", owner),
                "{\"name\":\"probe\",\"config\":{\"sandbox_type\":\"openshift\"}}"),
                400, "sbx-bad-type.json");
        assertGolden(json(post("/api/v1/sandbox-configs").header("Authorization", owner),
                "{\"name\":\"probe\",\"config\":{\"sandbox_type\":\"docker\",\"docker\":{\"image\":\"ubuntu:22.04\"}}}"),
                400, "sbx-docker-disabled.json");
        // 未开 allow_private_endpoints：127.0.0.1 先被 URL 守卫拒绝（分支顺序实测）
        assertGolden(json(post("/api/v1/sandbox-configs").header("Authorization", owner),
                "{\"name\":\"probe\",\"config\":{\"sandbox_type\":\"cube\",\"cube\":{\"api_url\":\"http://127.0.0.1:39171\",\"sandbox_domain\":\"sb.example.internal\"}}}"),
                400, "sbx-cube-incomplete.json");
        assertGolden(json(post("/api/v1/sandbox-configs").header("Authorization", owner),
                "{\"name\":\"probe\",\"config\":{\"sandbox_type\":\"e2b\",\"e2b\":{\"template_id\":\"tpl-e2b-1\"}}}"),
                400, "sbx-e2b-incomplete.json");
        assertGolden(json(post("/api/v1/sandbox-configs").header("Authorization", owner),
                "{\"name\":\"probe\",\"config\":{\"sandbox_type\":\"cube\",\"cube\":{\"api_url\":\"http://169.254.169.254:8080\",\"proxy_url\":\"http://127.0.0.1:39172\",\"sandbox_domain\":\"sb.example.internal\",\"template_id\":\"tpl-probe-1\"}}}"),
                400, "sbx-cube-unsafe-url.json");
    }

    // ── 3) CRUD 成功路径（Create 不拨号；provider 失败分支固定文案） ─────

    @Test
    void crudLifecycle() throws Exception {
        String owner = "Bearer " + login();

        MvcResult cube = mockMvc.perform(json(post("/api/v1/sandbox-configs")
                .header("Authorization", owner),
                "{\"name\":\"sbx-probe-cube\",\"description\":\"probe cube\",\"config\":" + CUBE_V1 + "}"))
                .andReturn();
        assertEquals(201, cube.getResponse().getStatus(), snippet(cube));
        assertMaskedBody("sbx-create-cube.json", raw(cube));
        String cid = extractId(raw(cube));

        assertMasked(json(post("/api/v1/sandbox-configs").header("Authorization", owner),
                "{\"name\":\"sbx-probe-e2b\",\"description\":\"probe e2b\",\"config\":"
                        + "{\"sandbox_type\":\"e2b\",\"allow_private_endpoints\":true,"
                        + "\"e2b\":{\"api_key\":\"sk-e2b-secret-1\",\"template_id\":\"tpl-e2b-1\"}}}"),
                201, "sbx-create-e2b.json");

        assertMasked(get("/api/v1/sandbox-configs").header("Authorization", owner),
                200, "sbx-list-two.json");
        assertGolden(get("/api/v1/sandbox-configs/00000000-0000-0000-0000-000000000000")
                .header("Authorization", owner), 404, "sbx-get-missing.json");
        assertMasked(get("/api/v1/sandbox-configs/" + cid).header("Authorization", owner),
                200, "sbx-get-cube.json");

        assertMasked(json(put("/api/v1/sandbox-configs/" + cid).header("Authorization", owner),
                "{\"name\":\"sbx-probe-cube-rn\",\"description\":\"renamed\",\"config\":" + CUBE_V1 + "}"),
                200, "sbx-update-rename.json");
        assertMasked(json(put("/api/v1/sandbox-configs/" + cid).header("Authorization", owner),
                "{\"name\":\"sbx-probe-cube-rn\",\"description\":\"renamed\",\"config\":" + CUBE_V2 + "}"),
                200, "sbx-update-identity.json");
        assertGolden(get("/api/v1/sandbox-configs/" + cid + "/sandboxes")
                .header("Authorization", owner), 200, "sbx-inventory.json");

        assertGolden(delete("/api/v1/sandbox-configs/" + cid).header("Authorization", owner),
                409, "sbx-delete-noforce.json");
        assertGolden(delete("/api/v1/sandbox-configs/" + cid + "?force=true")
                .header("Authorization", owner), 200, "sbx-delete-force.json");
        assertGolden(delete("/api/v1/sandbox-configs/" + cid).header("Authorization", owner),
                404, "sbx-delete-again.json");
    }

    // ── 4) workspace-policy 开关（policy 行恒隐藏） ──────────────────────

    @Test
    void workspacePolicyToggle() throws Exception {
        String owner = "Bearer " + login();
        mockMvc.perform(json(post("/api/v1/sandbox-configs").header("Authorization", owner),
                "{\"name\":\"sbx-probe-e2b\",\"description\":\"probe e2b\",\"config\":"
                        + "{\"sandbox_type\":\"e2b\",\"allow_private_endpoints\":true,"
                        + "\"e2b\":{\"api_key\":\"sk-e2b-secret-1\",\"template_id\":\"tpl-e2b-1\"}}}"))
                .andReturn();

        assertGolden(json(put("/api/v1/sandbox-configs/workspace-policy").header("Authorization", owner),
                "{\"scripts_disabled\":true}"), 200, "sbx-policy-on.json");
        assertMasked(get("/api/v1/sandbox-configs").header("Authorization", owner),
                200, "sbx-policy-on-list.json");
        assertGolden(json(put("/api/v1/sandbox-configs/workspace-policy").header("Authorization", owner),
                "{\"scripts_disabled\":false}"), 200, "sbx-policy-off.json");
        assertMasked(get("/api/v1/sandbox-configs").header("Authorization", owner),
                200, "sbx-policy-off-list.json");
    }

    // ── 5) templates/query 的 pre-provider 校验分支 ──────────────────────

    @Test
    void templatesPreProvider() throws Exception {
        String owner = "Bearer " + login();
        assertGolden(json(post("/api/v1/sandbox-configs/templates/query").header("Authorization", owner),
                "{\"config\":{\"sandbox_type\":\"docker\",\"docker\":{\"image\":\"ubuntu:22.04\"}}}"),
                400, "sbx-templates-docker.json");
        assertGolden(json(post("/api/v1/sandbox-configs/templates/query").header("Authorization", owner),
                "{\"config\":{\"sandbox_type\":\"bogus\"}}"), 400, "sbx-templates-invalid.json");
    }

    // ── 辅助 ──────────────────────────────────────────────────────────────

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(json(post("/api/v1/auth/login"),
                "{\"email\":\"" + OWNER_EMAIL + "\",\"password\":\"Passw0rd!\"}")).andReturn();
        assertEquals(200, result.getResponse().getStatus(), raw(result));
        Matcher m = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(raw(result));
        assertTrue(m.find(), "login 响应应含 token: " + raw(result));
        return m.group(1);
    }

    /** 静态 golden：状态码 + 逐字节。 */
    private void assertGolden(MockHttpServletRequestBuilder req, int status, String goldenName)
            throws Exception {
        MvcResult r = mockMvc.perform(req).andReturn();
        assertEquals(status, r.getResponse().getStatus(), goldenName + " 状态码不符: " + snippet(r));
        assertEquals(golden(goldenName), raw(r), goldenName);
    }

    /** 动态 golden：uuid id 与时间戳两侧同掩码后逐字节。 */
    private void assertMasked(MockHttpServletRequestBuilder req, int status, String goldenName)
            throws Exception {
        MvcResult r = mockMvc.perform(req).andReturn();
        assertEquals(status, r.getResponse().getStatus(), goldenName + " 状态码不符: " + snippet(r));
        assertMaskedBody(goldenName, raw(r));
    }

    private void assertMaskedBody(String goldenName, String actual) throws Exception {
        assertEquals(mask(golden(goldenName)), mask(actual), goldenName);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, String body) {
        return req.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String extractId(String body) {
        Matcher m = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(body);
        assertTrue(m.find(), "响应应含配置 id: " + body);
        return m.group(1);
    }

    private static String mask(String s) {
        String out = UUID_VALUE.matcher(s).replaceAll("\"id\":\"<uuid>\"");
        return TS_PATTERN.matcher(out).replaceAll("<ts>");
    }

    private static String snippet(MvcResult r) throws Exception {
        String body = raw(r);
        return body.length() > 300 ? body.substring(0, 300) + "…" : body;
    }

    /** 响应体按 UTF-8 字节解码（中文拒绝体；陷阱清单第 12 条）。 */
    private static String raw(MvcResult r) {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static String golden(String name) throws Exception {
        return new String(new ClassPathResource("contracts/" + name).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
    }
}
