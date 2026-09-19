package com.ragagent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
 * 波 3 sandbox 子批 2 契约测试：/system/sandbox-check + templates/query provider 面
 * （对照 golden 逐字节）。
 *
 * golden 来源：Go dev server（localhost:8080，2026-09-19 录制，
 * scripts/record-sbx2-golden.sh，8 条 schk-* / tpl-*.json）。
 *
 * 确定性来源：dev 无 provider（cube 127.0.0.1 拒连、docker 禁用）——
 * sandbox-check 的失败文案走 sandboxCheckReason 固定中文分类；唯一动态项是
 * latency_ms（拒连即时失败时被 omitempty 省略，慢一次就出现）→ 两侧同掩码。
 * tpl 的 500 是 Go 全局 ErrorHandler 的 plain 分支固定形态（无 details 键），
 * 零掩码。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SandboxCheckContractTest {

    /**
     * 测试 JVM 没有 SYSTEM_AES_KEY（标准全量流程不导出该 env）——e2b 场景带
     * api_key，给 service 的「拒绝明文落密钥」检查一个固定 key 放行。真加密路径
     * 由 A/B（两侧真 server、同 key）覆盖；响应只看掩码 "***"。
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

    /**
     * latency 两侧粒度不同：Go 拒连耗时 0ms → omitempty 整键省略；Java HttpClient
     * 实测 1ms+ → 键出现。掩码把整个 latency_ms 片段（连同前置逗号）移除。
     */
    private static final Pattern LATENCY = Pattern.compile(",?\"latency_ms\":\\d+");

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

    // ── 1) 确定性 400（老式 {"code":1,"msg":...}） ────────────────────────

    @Test
    void legacyErrorFamily() throws Exception {
        String owner = "Bearer " + login();
        assertGolden(json(post("/api/v1/system/sandbox-check").header("Authorization", owner),
                "not-json"), 400, "schk-bad-body.json");
        assertGolden(json(post("/api/v1/system/sandbox-check").header("Authorization", owner),
                "{\"config_id\":\"00000000-0000-0000-0000-000000000000\"}"),
                400, "schk-config-missing.json");
        assertGolden(json(post("/api/v1/system/sandbox-check").header("Authorization", owner),
                "{\"config\":{\"sandbox_type\":\"docker\",\"docker\":{\"image\":\"ubuntu:22.04\"}}}"),
                400, "schk-docker.json");
        // URL 守卫先于必填：127.0.0.1 未开私网 → 私网拒绝分支
        assertGolden(json(post("/api/v1/system/sandbox-check").header("Authorization", owner),
                "{\"config\":{\"sandbox_type\":\"cube\",\"allow_private_endpoints\":true,"
                        + "\"cube\":{\"api_url\":\"http://127.0.0.1:39171\","
                        + "\"sandbox_domain\":\"sb.example.internal\"}}}"),
                400, "schk-incomplete.json");
    }

    // ── 2) 拒连探测（200 结构化，latency 掩码） ──────────────────────────

    @Test
    void unreachableProbes() throws Exception {
        String owner = "Bearer " + login();
        assertMasked(json(post("/api/v1/system/sandbox-check").header("Authorization", owner),
                "{\"config\":{\"sandbox_type\":\"cube\",\"allow_private_endpoints\":true,"
                        + "\"cube\":{\"api_url\":\"http://127.0.0.1:39171\","
                        + "\"proxy_url\":\"http://127.0.0.1:39172\","
                        + "\"sandbox_domain\":\"sb.example.internal\","
                        + "\"template_id\":\"tpl-probe-1\"}}}"),
                200, "schk-cube-unreachable.json");
        assertMasked(json(post("/api/v1/system/sandbox-check").header("Authorization", owner),
                "{\"config\":{\"sandbox_type\":\"e2b\",\"allow_private_endpoints\":true,"
                        + "\"e2b\":{\"api_key\":\"sk-e2b-secret-1\",\"template_id\":\"tpl-e2b-1\","
                        + "\"api_url\":\"http://127.0.0.1:39173\"}}}"),
                200, "schk-e2b-unreachable.json");
        assertMasked(json(post("/api/v1/system/sandbox-check").header("Authorization", owner),
                "{\"deep\":true,\"config\":{\"sandbox_type\":\"cube\","
                        + "\"allow_private_endpoints\":true,\"cube\":{"
                        + "\"api_url\":\"http://127.0.0.1:39171\","
                        + "\"proxy_url\":\"http://127.0.0.1:39172\","
                        + "\"sandbox_domain\":\"sb.example.internal\","
                        + "\"template_id\":\"tpl-probe-1\"}}}"),
                200, "schk-deep-unreachable.json");
    }

    // ── 3) templates/query provider 面（500 plain 固定形态，零掩码） ─────

    @Test
    void templatesProviderFace() throws Exception {
        String owner = "Bearer " + login();
        assertGolden(json(post("/api/v1/sandbox-configs/templates/query")
                .header("Authorization", owner),
                "{\"config\":{\"sandbox_type\":\"cube\",\"allow_private_endpoints\":true,"
                        + "\"cube\":{\"api_url\":\"http://127.0.0.1:39171\","
                        + "\"proxy_url\":\"http://127.0.0.1:39172\","
                        + "\"sandbox_domain\":\"sb.example.internal\","
                        + "\"template_id\":\"tpl-probe-1\"}}}"),
                500, "tpl-cube-unreachable.json");
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
        assertEquals(status, r.getResponse().getStatus(), goldenName + " 状态码不符: " + raw(r));
        assertEquals(golden(goldenName), raw(r), goldenName);
    }

    /** 动态 golden：latency_ms 两侧同掩码后逐字节。 */
    private void assertMasked(MockHttpServletRequestBuilder req, int status, String goldenName)
            throws Exception {
        MvcResult r = mockMvc.perform(req).andReturn();
        assertEquals(status, r.getResponse().getStatus(), goldenName + " 状态码不符: " + raw(r));
        assertEquals(mask(golden(goldenName)), mask(raw(r)), goldenName);
    }

    private static String mask(String s) {
        return LATENCY.matcher(s).replaceAll("");
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, String body) {
        return req.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** 响应体按 UTF-8 字节解码（中文文案；陷阱清单第 12 条）。 */
    private static String raw(MvcResult r) {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static String golden(String name) throws Exception {
        return new String(new ClassPathResource("contracts/" + name).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
    }
}
