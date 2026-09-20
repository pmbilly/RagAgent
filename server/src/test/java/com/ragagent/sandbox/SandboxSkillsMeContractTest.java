package com.ragagent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import com.ragagent.sandbox.service.SkillBundleParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 波 3 sandbox 子批 4 契约测试：/skills 家族 7 条 + /me/env-vars 5 条
 * （对照 golden 逐字节；go 源 skill_handler.go / skill_catalog.go /
 * me_env_var.go / user_env.go / tenant_skill_catalog.go）。
 *
 * golden 来源：Go dev server（2026-09-19 录制，scripts/record-sbx4-golden.sh，
 * 16 条 slk-*.json + 9 条 mev-*.json）。catalog 归档在 dev 走本地存储（local://）；
 * install 的首个 provider 调用在后台失败（同子批 3 模式），A/B 只对受理响应字节。
 *
 * 场景顺序严格复刻录制脚本（同请求序列有状态依赖）：
 * 前置（cube 配置 + 固定 ready 技能行）→ /skills 只读 + catalog 空态/注册三态 →
 * catalog 列表/files → install（成功受理 + config 缺失）→ /me/env-vars 全序列 →
 * catalog 删除（被安装钉住 409 ×2）。
 *
 * 掩码面（对两侧同掩后比对，scripts/ab-sbx4.sh 的掩码 + bundle_sha256 一项）：
 * uuid（config/catalog/skill id 与 install 受理映射值）、时间戳（服务端时钟）、
 * bundle_sha256（录制用 python zipfile、测试用 java.util.zip——归档字节本就不同，
 * 摘要是上传内容的函数，与 uuid 同属环境方差）。
 * 种子行 created_at/updated_at 用固定时间戳（2026-09-01T10:00:00Z → +08:00 渲染）
 * ——列表/单查无需掩码（golden 是准绳，JVM 默认时区 = 录制机器 Asia/Shanghai）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SandboxSkillsMeContractTest {

    /**
     * 测试 JVM 没有 SYSTEM_AES_KEY——给「拒绝明文落密钥」检查一个固定 key 放行
     * （与 SandboxSkillContractTest 同款；真实加密路径由 A/B 覆盖）。/me/env-vars
     * 的值要落库加密、读回解密：没有 key 时解密侧会把存量报成 unset，
     * mev-list-after 的 source=user 契约就断了。
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

    private static final String BCRYPT = "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK";
    private static final long TENANT = 10002L;
    private static final String OWNER = "11111111-2222-3333-4444-555555555501";
    private static final String OWNER_EMAIL = "java-phase1@weknora.test";
    /** 与录制脚本 psql 逐字相同的固定 skill 行 ID。 */
    private static final String SKILL_ID = "22222222-3333-4444-5555-666666666602";
    private static final String MISSING_ID = "00000000-0000-0000-0000-000000000000";

    /** 与录制脚本逐字相同的配置体。 */
    private static final String CUBE_CFG =
            "{\"name\":\"slk-probe-cube\",\"description\":\"catalog probe\",\"config\":"
                    + "{\"sandbox_type\":\"cube\",\"allow_private_endpoints\":true,"
                    + "\"cube\":{\"api_url\":\"http://127.0.0.1:39171\","
                    + "\"proxy_url\":\"http://127.0.0.1:39172\","
                    + "\"sandbox_domain\":\"sb.example.internal\","
                    + "\"template_id\":\"tpl-probe-1\",\"api_key\":\"sk-cube-secret-1\"}}}";

    private static final Pattern TS_PATTERN = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})");
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"");
    private static final Pattern SHA_PATTERN = Pattern.compile(
            "\"bundle_sha256\":\"[0-9a-f]{64}\"");
    /** fake-ip DNS 池的受限段地址（198.18.0.0/15）——解析 IP 是环境锚（§9 ct 掩码族）。 */
    private static final Pattern FAKEIP_PATTERN = Pattern.compile(
            "198\\.18\\.\\d+\\.\\d+");

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

    /** 前置（脚本第 0 步）：建 cube 配置 + 固定 ready 技能行，返回 config id。 */
    private String setupConfigAndSkill(String owner) throws Exception {
        MvcResult created = mockMvc.perform(json(
                        post("/api/v1/sandbox-configs").header("Authorization", owner), CUBE_CFG))
                .andReturn();
        assertEquals(201, created.getResponse().getStatus(), raw(created));
        assertMaskedBody("slk-setup-config.json", raw(created));
        String cid = extractUuid(raw(created), "\"id\":\"");

        // 与录制脚本 psql 逐字同列：envs 声明无 value（source=unset 的种子语义）
        jdbc.update("INSERT INTO tenant_skills (id, tenant_id, sandbox_config_id, name, "
                        + "version, description, instructions, enabled, status, envs, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'mev-probe-skill', '1.0.0', 'mev probe', "
                        + "'Use PROBE_TOKEN.', TRUE, 'ready', "
                        + "'[{\"name\":\"PROBE_TOKEN\",\"description\":\"probe token\","
                        + "\"required\":true}]', "
                        + "TIMESTAMP WITH TIME ZONE '2026-09-01 10:00:00+00', "
                        + "TIMESTAMP WITH TIME ZONE '2026-09-01 10:00:00+00')",
                SKILL_ID, TENANT, cid);
        return cid;
    }

    /** 与录制脚本 python zipfile 同内容的 zip 条目（现场构造，java.util.zip）。 */
    static byte[] catalogZip() {
        java.util.LinkedHashMap<String, String> files = new java.util.LinkedHashMap<>();
        files.put("SKILL.md", "---\nname: cat-probe\n"
                + "description: catalog probe skill\n---\n\nBody instructions.\n");
        files.put("scripts/run.sh", "echo hi\n");
        return SkillBundleParser.zipOf(files);
    }

    // ── 完整录制顺序（脚本 0-5 步） ──────────────────────────────────────

    @Test
    void recordedScenario() throws Exception {
        String owner = "Bearer " + login();
        String cid = setupConfigAndSkill(owner);

        // ==> 1) /skills 只读 + catalog 空 + 注册三态
        assertGolden(get("/api/v1/skills").header("Authorization", owner),
                200, "slk-skills.json");
        assertMasked(get("/api/v1/skills/catalog").header("Authorization", owner),
                200, "slk-catalog-empty.json");
        assertGolden(post("/api/v1/skills/catalog").header("Authorization", owner),
                400, "slk-catalog-register-nobody.json");
        assertGolden(json(post("/api/v1/skills/catalog").header("Authorization", owner),
                "{\"source\":\"\"}"), 400, "slk-catalog-register-invalid.json");
        // SSRF 拒绝在出站校验层发生（dev 的 fake-ip DNS 把公网域名解进受限段）；
        // 校验通过后的真实抓取是波 4 接缝——任何分支都不会发出出站请求。
        // fake-ip 池每次解析回不同地址（录制 198.18.0.122 / 重跑 198.18.0.74）——
        // IP 是环境锚必须掩码（§9 ct 批"SSRF 解析 IP 掩码族"），受限段文案另行断言。
        assertMaskedBody("slk-catalog-register-src.json", actualSourceResponse(owner));
        org.junit.jupiter.api.Assertions.assertTrue(
                actualSourceResponse(owner).contains("restricted range 198.18.0.0/15"),
                "SSRF 拒绝必须落在 198.18.0.0/15 受限段");

        // ==> 2) catalog 归档注册（本地存储）+ 列表/files
        MvcResult registered = mockMvc.perform(multipart("/api/v1/skills/catalog")
                        .file(new MockMultipartFile("file", "sbx4-catalog.zip",
                                "application/octet-stream", catalogZip()))
                        .header("Authorization", owner))
                .andReturn();
        assertEquals(201, registered.getResponse().getStatus(), raw(registered));
        assertMaskedBody("slk-catalog-register.json", raw(registered));
        String catId = extractUuid(raw(registered), "\"id\":\"");

        assertMasked(get("/api/v1/skills/catalog").header("Authorization", owner),
                200, "slk-catalog-list.json");
        assertGolden(get("/api/v1/skills/catalog/" + catId + "/files")
                .header("Authorization", owner), 200, "slk-catalog-files.json");
        assertGolden(get("/api/v1/skills/catalog/" + catId
                        + "/files/content").queryParam("path", "SKILL.md")
                .header("Authorization", owner), 200, "slk-catalog-file.json");
        assertGolden(get("/api/v1/skills/catalog/" + catId
                        + "/files/content").queryParam("path", "../escape.md")
                .header("Authorization", owner), 400, "slk-catalog-file-bad.json");

        // ==> 3) install（异步受理 + config 缺失）
        assertMasked(json(post("/api/v1/skills/catalog/" + catId + "/install")
                        .header("Authorization", owner),
                "{\"sandbox_config_ids\":[\"" + cid + "\"]}"), 202,
                "slk-catalog-install.json");
        assertGolden(json(post("/api/v1/skills/catalog/" + catId + "/install")
                        .header("Authorization", owner),
                "{\"sandbox_config_ids\":[\"" + MISSING_ID + "\"]}"), 404,
                "slk-catalog-install-missing.json");

        // ==> 4) /me/env-vars
        assertMasked(get("/api/v1/me/env-vars").header("Authorization", owner),
                200, "mev-list.json");
        assertGolden(json(put("/api/v1/me/env-vars/skill").header("Authorization", owner),
                        "{\"skill_id\":\"" + SKILL_ID
                                + "\",\"name\":\"PROBE_TOKEN\",\"value\":\"tok-secret-9\"}"),
                200, "mev-set-skill.json");
        assertMasked(get("/api/v1/me/env-vars").header("Authorization", owner),
                200, "mev-list-after.json");
        assertGolden(json(put("/api/v1/me/env-vars/skill").header("Authorization", owner),
                        "{\"skill_id\":\"" + SKILL_ID
                                + "\",\"name\":\"NOT_DECLARED\",\"value\":\"x\"}"),
                400, "mev-set-skill-badname.json");
        assertGolden(json(delete("/api/v1/me/env-vars/skill").header("Authorization", owner),
                        "{\"skill_id\":\"" + SKILL_ID + "\",\"name\":\"PROBE_TOKEN\"}"),
                200, "mev-delete-skill.json");
        assertGolden(json(put("/api/v1/me/env-vars/sandbox").header("Authorization", owner),
                        "{\"sandbox_config_id\":\"" + cid
                                + "\",\"name\":\"CFG_VAR\",\"value\":\"cfg-secret\"}"),
                200, "mev-set-sandbox.json");
        assertMasked(get("/api/v1/me/env-vars").header("Authorization", owner),
                200, "mev-list-after2.json");
        assertGolden(json(delete("/api/v1/me/env-vars/sandbox").header("Authorization", owner),
                        "{\"sandbox_config_id\":\"" + cid + "\",\"name\":\"CFG_VAR\"}"),
                200, "mev-delete-sandbox.json");
        assertGolden(json(put("/api/v1/me/env-vars/skill").header("Authorization", owner),
                "{}"), 400, "mev-set-skill-nofield.json");

        // ==> 5) catalog 删除（安装行钉住 → 409 ×2）
        assertGolden(delete("/api/v1/skills/catalog/" + catId).header("Authorization", owner),
                409, "slk-catalog-delete.json");
        assertGolden(delete("/api/v1/skills/catalog/" + catId).header("Authorization", owner),
                409, "slk-catalog-delete-again.json");
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

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req,
            String body) {
        return req.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void assertGolden(MockHttpServletRequestBuilder req, int status, String goldenName)
            throws Exception {
        MvcResult r = mockMvc.perform(req).andReturn();
        assertEquals(status, r.getResponse().getStatus(), goldenName + " 状态码不符: " + snippet(r));
        assertEquals(golden(goldenName), raw(r), goldenName);
    }

    private void assertMasked(MockHttpServletRequestBuilder req, int status, String goldenName)
            throws Exception {
        MvcResult r = mockMvc.perform(req).andReturn();
        assertEquals(status, r.getResponse().getStatus(), goldenName + " 状态码不符: " + snippet(r));
        assertMaskedBody(goldenName, raw(r));
    }

    private void assertMaskedBody(String goldenName, String actual) throws Exception {
        assertEquals(mask(golden(goldenName)), mask(actual), goldenName);
    }

    /**
     * 对 golden 与 actual 同掩（ab-sbx4.sh 的 sed 掩码 + bundle_sha256）：
     * uuid / 时间戳 / 归档摘要都是环境或字节方差的函数。
     */
    private static String mask(String s) {
        String out = UUID_PATTERN.matcher(s).replaceAll("\"<uuid>\"");
        out = TS_PATTERN.matcher(out).replaceAll("<ts>");
        out = FAKEIP_PATTERN.matcher(out).replaceAll("<fakeip>");
        return SHA_PATTERN.matcher(out).replaceAll("\"bundle_sha256\":\"<sha>\"");
    }

    /** slk-catalog-register-src 专用：发请求、钉 400、回响应体（IP 环境锚掩码比对）。 */
    private String actualSourceResponse(String owner) throws Exception {
        MvcResult r = mockMvc.perform(json(
                        post("/api/v1/skills/catalog").header("Authorization", owner),
                        "{\"source\":\"https://example.com/not-reachable.zip\"}"))
                .andReturn();
        assertEquals(400, r.getResponse().getStatus(), snippet(r));
        return raw(r);
    }

    private static String extractUuid(String body, String anchor) {
        int at = body.indexOf(anchor);
        assertTrue(at >= 0, "响应应含 " + anchor + ": " + body);
        Matcher m = UUID_PATTERN.matcher(body.substring(at));
        assertTrue(m.find(), "响应应含 uuid: " + body);
        return m.group().replace("\"", "");
    }

    private static String snippet(MvcResult r) throws Exception {
        String body = raw(r);
        return body.length() > 300 ? body.substring(0, 300) + "…" : body;
    }

    /** 响应体按 UTF-8 字节解码（中文拒绝体；陷阱清单第 12 条）。 */
    private static String raw(MvcResult r) {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    /** golden 原文不做 trim：curl -o 直写、无尾换行，逐字节。 */
    private static String golden(String name) throws Exception {
        return new String(new ClassPathResource("contracts/" + name).getInputStream()
                .readAllBytes(), StandardCharsets.UTF_8);
    }
}
