package com.ragagent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
 * 波 3 sandbox 子批 3 契约测试：/sandbox-configs/{id}/skills* 13 条路由
 * （对照 golden 逐字节；go 源 internal/handler/sandbox_skill.go 全文）。
 *
 * golden 来源：Go dev server（2026-09-19 录制，scripts/record-sbx3-golden.sh，
 * 18 条 sbk-*.json）。dev 无 provider：install 管线在首个 provider 调用处失败
 * （RemoteError 分类），A/B 只对 202 响应字节——异步终态不进 golden。
 *
 * 场景顺序严格复刻录制脚本（同请求序列有状态依赖）：
 * 前置（cube 配置 + 固定 ready 技能行）→ 只读路径（list/get/events/transcript/guidance）→
 * 变更路径（patch ×2/stop/delete-missing）→ 上传三态 → files/reinstall。
 *
 * 掩码面：config id（uuid）、upload 响应的 skill_id（uuid）、patch-restore 的
 * updated_at（服务端时钟）。种子行 created_at/updated_at 用固定时间戳
 * （2026-09-01T10:00:00Z → +08:00 渲染）——列表/单查/patch 首响无需掩码
 * （ golden 是准绳，JVM 默认时区 = 录制机器时区 Asia/Shanghai）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SandboxSkillContractTest {

    /**
     * 测试 JVM 没有 SYSTEM_AES_KEY——给「拒绝明文落密钥」检查一个固定 key 放行
     * （与 SandboxConfigContractTest 同款；真实加密路径由 A/B 覆盖）。
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
    private static final String SKILL_ID = "22222222-3333-4444-5555-666666666601";
    private static final String MISSING_ID = "00000000-0000-0000-0000-000000000000";

    /** 与录制脚本逐字相同的配置体。 */
    private static final String CUBE_CFG =
            "{\"name\":\"sbk-probe-cube\",\"description\":\"skill probe\",\"config\":"
                    + "{\"sandbox_type\":\"cube\",\"allow_private_endpoints\":true,"
                    + "\"cube\":{\"api_url\":\"http://127.0.0.1:39171\","
                    + "\"proxy_url\":\"http://127.0.0.1:39172\","
                    + "\"sandbox_domain\":\"sb.example.internal\","
                    + "\"template_id\":\"tpl-probe-1\",\"api_key\":\"sk-cube-secret-1\"}}}";

    private static final Pattern TS_PATTERN = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})");
    private static final Pattern HEX36 = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

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
        // tenant_skill_catalog 的 DDL 已按约束 9 收编 TestSchema（主会话统一维护）

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

    /** 前置：建 cube 配置 + 固定 ready 技能行（脚本第 0 步），返回 config id。 */
    private String setupConfigAndSkill(String owner) throws Exception {
        MvcResult created = mockMvc.perform(json(
                post("/api/v1/sandbox-configs").header("Authorization", owner), CUBE_CFG))
                .andReturn();
        assertEquals(201, created.getResponse().getStatus(), raw(created));
        String cid = extractIdField(raw(created), "id");

        // 与录制脚本 psql 逐字同列：envs 声明无 value（is_set=false 的种子语义）
        jdbc.update("INSERT INTO tenant_skills (id, tenant_id, sandbox_config_id, name, "
                        + "version, description, instructions, bundle_ref, bundle_sha256, "
                        + "enabled, installed_snapshot_id, install_session_id, install_message_id, "
                        + "envs, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'probe-skill', '1.0.0', 'probe skill', "
                        + "'Do the probe thing.', '/bundles/probe-skill.zip', "
                        + "'deadbeefdeadbeefdeadbeefdeadbeef', TRUE, 'snap-0001', "
                        + "'sess-0001', 'msg-0001', "
                        + "'[{\"name\":\"PROBE_TOKEN\",\"description\":\"token for probe\","
                        + "\"required\":true}]', 'ready', "
                        + "TIMESTAMP WITH TIME ZONE '2026-09-01 10:00:00+00', "
                        + "TIMESTAMP WITH TIME ZONE '2026-09-01 10:00:00+00')",
                SKILL_ID, TENANT, cid);
        return cid;
    }

    // ── 完整录制顺序（脚本 0-4 步） ──────────────────────────────────────

    @Test
    void recordedScenario() throws Exception {
        String owner = "Bearer " + login();
        String cid = setupConfigAndSkill(owner);

        // ==> 1) 只读路径
        assertGolden(get("/api/v1/sandbox-configs/" + cid + "/skills")
                .header("Authorization", owner), 200, "sbk-list.json");
        assertGolden(get("/api/v1/sandbox-configs/" + cid + "/skills/" + SKILL_ID)
                .header("Authorization", owner), 200, "sbk-get.json");
        assertGolden(get("/api/v1/sandbox-configs/" + cid + "/skills/" + MISSING_ID)
                .header("Authorization", owner), 404, "sbk-get-missing.json");
        assertGolden(get("/api/v1/sandbox-configs/" + cid + "/skills/" + SKILL_ID + "/install-events")
                .header("Authorization", owner), 200, "sbk-events-ready.json");
        assertGolden(get("/api/v1/sandbox-configs/" + cid + "/skills/" + MISSING_ID + "/install-events")
                .header("Authorization", owner), 404, "sbk-events-missing.json");
        assertGolden(get("/api/v1/sandbox-configs/" + cid + "/skills/" + SKILL_ID + "/transcript")
                .header("Authorization", owner), 404, "sbk-transcript.json");
        assertGolden(get("/api/v1/sandbox-configs/" + cid + "/skills/" + SKILL_ID + "/guidance")
                .header("Authorization", owner), 200, "sbk-guidance.json");

        // ==> 2) 变更路径
        assertGolden(json(patch("/api/v1/sandbox-configs/" + cid + "/skills/" + SKILL_ID)
                .header("Authorization", owner),
                "{\"description\":\"patched desc\",\"enabled\":false}"), 200, "sbk-patch.json");
        assertMasked(json(patch("/api/v1/sandbox-configs/" + cid + "/skills/" + SKILL_ID)
                .header("Authorization", owner), "{\"enabled\":true}"),
                200, "sbk-patch-restore.json");
        assertGolden(post("/api/v1/sandbox-configs/" + cid + "/skills/" + SKILL_ID + "/stop")
                .header("Authorization", owner), 400, "sbk-stop.json");
        assertGolden(delete("/api/v1/sandbox-configs/" + cid + "/skills/" + MISSING_ID)
                .header("Authorization", owner), 404, "sbk-delete-missing.json");

        // ==> 3) 上传（本地 bundle 校验 + 首个 provider 调用失败）
        byte[] notAZip = "this is not a zip file at all, just some bytes\n"
                .getBytes(StandardCharsets.UTF_8);
        assertMasked(multipartUpload("/api/v1/sandbox-configs/" + cid + "/skills",
                "not-a-zip.bin", notAZip, owner), 400, "sbk-upload-invalid.json");

        byte[] zip = SkillBundleParser.zipOf(skillZipEntries());
        MvcResult uploaded = mockMvc.perform(multipartUploadRaw(
                "/api/v1/sandbox-configs/" + cid + "/skills", "probe-skill.zip", zip, owner))
                .andReturn();
        assertEquals(202, uploaded.getResponse().getStatus(), snippet(uploaded));
        assertMaskedBody("sbk-upload-zip.json", raw(uploaded));

        assertGolden(post("/api/v1/sandbox-configs/" + cid + "/skills")
                .header("Authorization", owner), 400, "sbk-upload-nobody.json");

        // ==> 4) files / reinstall（provider 失败分类）
        assertGolden(get("/api/v1/sandbox-configs/" + cid + "/skills/" + SKILL_ID + "/files")
                .header("Authorization", owner), 404, "sbk-files.json");
        assertGolden(get("/api/v1/sandbox-configs/" + cid + "/skills/" + SKILL_ID
                + "/files/content")
                .queryParam("path", "/opt/weknora/tenant/skills/probe-skill/SKILL.md")
                .header("Authorization", owner), 400, "sbk-file-content.json");
        assertGolden(post("/api/v1/sandbox-configs/" + cid + "/skills/" + SKILL_ID + "/reinstall")
                .header("Authorization", owner), 404, "sbk-reinstall.json");
    }

    /** 与录制脚本 python zipfile 同内容的 zip 条目（现场构造，java.util.zip）。 */
    static java.util.LinkedHashMap<String, String> skillZipEntries() {
        java.util.LinkedHashMap<String, String> files = new java.util.LinkedHashMap<>();
        files.put("SKILL.md", "---\nname: probe-upload\n"
                + "description: probe upload skill\n---\n\nBody instructions.\n");
        files.put("scripts/run.sh", "echo hi\n");
        return files;
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

    private static MockHttpServletRequestBuilder multipartUpload(String url, String filename,
            byte[] bytes, String owner) {
        return multipartUploadRaw(url, filename, bytes, owner);
    }

    private static MockHttpServletRequestBuilder multipartUploadRaw(String url, String filename,
            byte[] bytes, String owner) {
        MockMultipartFile file =
                new MockMultipartFile("file", filename, "application/octet-stream", bytes);
        return multipart(url).file(file).header("Authorization", owner);
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

    private static String mask(String s) {
        // upload 响应的 skill_id 与 golden 的录制值必然不同（两侧同掩 uuid）
        String out = Pattern.compile("\"skill_id\":\"[0-9a-f-]{36}\"")
                .matcher(s).replaceAll("\"skill_id\":\"<uuid>\"");
        // 测试自建表的配置 id（setup 响应不经 mask 断言，这里只防串场）
        return TS_PATTERN.matcher(out).replaceAll("<ts>");
    }

    private static String extractIdField(String body, String field) {
        Matcher m = Pattern.compile("\"" + field + "\":\"([0-9a-f-]{36})\"").matcher(body);
        assertTrue(m.find(), "响应应含 " + field + ": " + body);
        return m.group(1);
    }

    private static String snippet(MvcResult r) throws Exception {
        String body = raw(r);
        return body.length() > 300 ? body.substring(0, 300) + "…" : body;
    }

    /** 响应体按 UTF-8 字节解码（SSE 帧与中文拒绝体；陷阱清单第 12 条）。 */
    private static String raw(MvcResult r) {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * golden 原文不做 trim：SSE golden 的尾部 \n\n 是响应体的一部分
     * （JSON golden 由 curl -o 直写、无尾换行，同样逐字节）。
     */
    private static String golden(String name) throws Exception {
        return new String(new ClassPathResource("contracts/" + name).getInputStream()
                .readAllBytes(), StandardCharsets.UTF_8);
    }
}
