package com.ragagent.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 波 2 扫尾批 1 契约测试：auth 注册族 9 端点，对照 golden 逐字节比对。
 *
 * golden 来源：Go dev server（localhost:8080，2026-09-19 录制，scripts/record-reg-golden.sh，
 * 46 条 reg-*.json；其中 reg-mode-set-invite-only / reg-mode-restore 属系统批端点，
 * 本测试只借它们切换状态、不做字节比对）。
 *
 * 场景顺序严格复刻录制脚本（同一 @Test 内的请求顺序敏感）：
 * register 400 家族 → register 成功 + 重复 → validate/me/preferences →
 * change-password（成功吊销旧 token）→ auto-setup → 邀请族 → invite_only 切换。
 *
 * H2 种子镜像录制脚本的固定身份：租户 10002（phase1-test-tenant）+
 * owner phase1test + 系统管理员 javasysadmin（invite_only 切换用）+
 * 固定 token 的 pending 分享邀请（角色 viewer）。
 *
 * 动态值两侧同掩码：uuid / 时间戳 / JWT / tenant_id 数字 / last_active_tenant_id /
 * 租户对象的 "id" 数字（H2 自增序列与 PG serial 取值不同）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthRegisterContractTest {

    private static final String BCRYPT = "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK"; // Passw0rd!
    private static final long TENANT = 10002L;
    private static final String OWNER = "11111111-2222-3333-4444-555555555501";
    private static final String SYS_ADMIN = "11111111-2222-3333-4444-555555555701";
    private static final String OWNER_EMAIL = "java-phase1@weknora.test";
    private static final String SYS_EMAIL = "java-sys-admin@weknora.test";

    private static final String PROBE_USER = "regprobe-user";
    private static final String PROBE_EMAIL = "reg-probe@weknora.test";
    private static final String PROBE_PW = "Passw0rd1";
    private static final String PROBE_PW_NEW = "Newpass123";
    private static final String INVITE_EMAIL = "reg-invite@weknora.test";
    private static final String INVITE_USER = "reginvite-user";
    private static final String INVITE_TOKEN = "reggoldenfixedtoken0123456789abcdef";
    /** 4001 字符（限 4000，rune 计） */

    private static final Pattern TS_PATTERN = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})");
    private static final Pattern UUID_VALUE = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern TOKEN_VALUE = Pattern.compile("\"token\":\"[^\"]*\"");
    private static final Pattern REFRESH_VALUE = Pattern.compile("\"refreshToken\":\"[^\"]*\"");
    private static final Pattern TENANT_ID_VALUE = Pattern.compile("\"tenantId\":\\d+");
    private static final Pattern LAST_ACTIVE_VALUE = Pattern.compile("\"lastActiveTenantId\":\\d+");
    private static final Pattern NUMERIC_ID = Pattern.compile("\"id\":\\d+");

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

        seedUser(OWNER, "phase1test", OWNER_EMAIL, false);
        seedUser(SYS_ADMIN, "javasysadmin", SYS_EMAIL, true);
        seedMember(OWNER, "owner");
        seedMember(SYS_ADMIN, "owner");

        // 固定 token 的 pending 分享邀请（对照录制脚本里 message='reg-golden' 的链接）
        jdbc.update("INSERT INTO tenant_invitations (tenant_id, invitee_user_id, invited_by, role,"
                        + " status, message, expires_at, token, accepted_count)"
                        + " VALUES (?, '', ?, 'viewer', 'pending', 'reg-golden', ?, ?, 0)",
                TENANT, OWNER,
                OffsetDateTime.of(2027, 9, 19, 0, 0, 0, 0, ZoneOffset.UTC),
                INVITE_TOKEN);
    }

    private void seedUser(String id, String username, String email, boolean sysAdmin) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(BCRYPT);
        user.setTenantId(TENANT);
        user.setIsActive(true);
        user.setIsSystemAdmin(sysAdmin);
        user.setPreferences(new UserPreferences());
        userMapper.insert(user);
    }

    private void seedMember(String userId, String role) {
        TenantMember member = new TenantMember();
        member.setUserId(userId);
        member.setTenantId(TENANT);
        member.setRole(role);
        member.setStatus("active");
        member.setJoinedAt(OffsetDateTime.of(2026, 9, 1, 10, 0, 0, 0, ZoneOffset.UTC));
        memberMapper.insert(member);
    }

    // ── 1) register 的 400 家族（binding → 策略；静态 golden 逐字节） ────────

    @Test
    void registerBindingAndPolicyFamily() throws Exception {
        assertGolden(post("/api/v1/auth/register"), 400, "reg-empty-body.json");
        assertGolden(json(post("/api/v1/auth/register"), "{}"), 400, "reg-missing-fields.json");
        assertGolden(json(post("/api/v1/auth/register"),
                "{\"username\":\"someone-ok\",\"email\":\"not-an-email\",\"password\":\"Passw0rd1\"}"),
                400, "reg-bad-email.json");
        assertGolden(json(post("/api/v1/auth/register"),
                "{\"username\":\"a\",\"email\":\"reg-x1@weknora.test\",\"password\":\"Passw0rd1\"}"),
                400, "reg-short-username.json");
        assertGolden(json(post("/api/v1/auth/register"),
                "{\"username\":\"abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxy\","
                        + "\"email\":\"reg-x2@weknora.test\",\"password\":\"Passw0rd1\"}"),
                400, "reg-long-username.json");
        assertGolden(json(post("/api/v1/auth/register"),
                "{\"username\":\"someone-ok\",\"email\":\"reg-x3@weknora.test\",\"password\":\"abc12\"}"),
                400, "reg-short-password.json");
        assertGolden(json(post("/api/v1/auth/register"),
                "{\"username\":\"someone-ok\",\"email\":\"reg-x4@weknora.test\",\"password\":\"abcdefgh\"}"),
                400, "reg-weak-nodigit.json");
        assertGolden(json(post("/api/v1/auth/register"),
                "{\"username\":\"someone-ok\",\"email\":\"reg-x5@weknora.test\",\"password\":\"12345678\"}"),
                400, "reg-weak-noletter.json");
        assertGolden(json(post("/api/v1/auth/register"),
                "{\"username\":\"someone-ok\",\"email\":\"reg-x6@weknora.test\","
                        + "\"password\":\"a123456789012345678901234567890123\"}"),
                400, "reg-long-password.json");
    }

    // ── 2) register 成功（动态掩码）+ 重复身份（静态） ───────────────────────

    @Test
    void registerSuccessThenDuplicates() throws Exception {
        MvcResult ok = mockMvc.perform(json(post("/api/v1/auth/register"),
                "{\"username\":\"" + PROBE_USER + "\",\"email\":\"" + PROBE_EMAIL
                        + "\",\"password\":\"" + PROBE_PW + "\"}")).andReturn();
        assertEquals(201, ok.getResponse().getStatus(), raw(ok));
        assertMasked("reg-success.json", raw(ok));

        assertGolden(json(post("/api/v1/auth/register"),
                "{\"username\":\"regprobe-other\",\"email\":\"" + PROBE_EMAIL
                        + "\",\"password\":\"" + PROBE_PW + "\"}"), 400, "reg-dup-email.json");
        assertGolden(json(post("/api/v1/auth/register"),
                "{\"username\":\"" + PROBE_USER + "\",\"email\":\"reg-other@weknora.test\","
                        + "\"password\":\"" + PROBE_PW + "\"}"), 400, "reg-dup-username.json");
    }

    // ── 3) validate + me ───────────────────────────────────────────────────

    @Test
    void validateAndMe() throws Exception {
        registerProbe();
        String probe = "Bearer " + login(PROBE_EMAIL, PROBE_PW);

        assertGolden(get("/api/v1/auth/validate"), 401, "reg-validate-noheader.json");
        assertGolden(get("/api/v1/auth/validate").header("Authorization", "Bearer"),
                401, "reg-validate-badformat.json");
        assertGolden(get("/api/v1/auth/validate").header("Authorization", "Bearer garbage.token.here"),
                401, "reg-validate-garbage.json");

        MvcResult valid = mockMvc.perform(get("/api/v1/auth/validate")
                .header("Authorization", probe)).andReturn();
        assertEquals(200, valid.getResponse().getStatus(), raw(valid));
        assertMasked("reg-validate-ok.json", raw(valid));

        MvcResult me = mockMvc.perform(get("/api/v1/auth/me")
                .header("Authorization", probe)).andReturn();
        assertEquals(200, me.getResponse().getStatus(), raw(me));
        assertMasked("reg-me-ok.json", raw(me));
    }

    // ── 4) preferences（顺序敏感：set → too-long → default-clear → tenant → tenant-clear） ──

    @Test
    void preferencesFlow() throws Exception {
        registerProbe();
        String probe = "Bearer " + login(PROBE_EMAIL, PROBE_PW);

        assertGolden(put("/api/v1/auth/me/preferences").header("Authorization", probe),
                400, "reg-prefs-empty-body.json");

        // 录制脚本从 reg-me-ok 取探针主租户 id；此处同样先取再回放
        long probeTenant = currentTenantId(probe);
        MvcResult tenantPref = mockMvc.perform(json(
                put("/api/v1/auth/me/preferences").header("Authorization", probe),
                "{\"lastActiveTenantId\":" + probeTenant + "}")).andReturn();
        assertEquals(200, tenantPref.getResponse().getStatus(), raw(tenantPref));
        assertMasked("reg-prefs-tenant.json", raw(tenantPref));

        assertGolden(json(put("/api/v1/auth/me/preferences").header("Authorization", probe),
                "{\"lastActiveTenantId\":0}"), 200, "reg-prefs-tenant-clear.json");
    }

    // ── 5) change-password（成功 → 旧 token 吊销 → 密码轮换） ────────────────

    @Test
    void changePasswordFlow() throws Exception {
        registerProbe();
        String probe = "Bearer " + login(PROBE_EMAIL, PROBE_PW);

        assertGolden(json(post("/api/v1/auth/change-password").header("Authorization", probe), "{}"),
                400, "reg-chpw-binding.json");
        assertGolden(json(post("/api/v1/auth/change-password").header("Authorization", probe),
                "{\"oldPassword\":\"WrongPass1\",\"newPassword\":\"" + PROBE_PW_NEW + "\"}"),
                400, "reg-chpw-wrong-old.json");
        assertGolden(json(post("/api/v1/auth/change-password").header("Authorization", probe),
                "{\"oldPassword\":\"" + PROBE_PW + "\",\"newPassword\":\"" + PROBE_PW + "\"}"),
                400, "reg-chpw-same.json");
        assertGolden(json(post("/api/v1/auth/change-password").header("Authorization", probe),
                "{\"oldPassword\":\"" + PROBE_PW + "\",\"newPassword\":\"abcdefgh\"}"),
                400, "reg-chpw-weak.json");
        assertGolden(json(post("/api/v1/auth/change-password").header("Authorization", probe),
                "{\"oldPassword\":\"" + PROBE_PW + "\",\"newPassword\":\"" + PROBE_PW_NEW + "\"}"),
                204, "reg-chpw-success.json");

        // 改密成功吊销全部会话
        assertGolden(get("/api/v1/auth/validate").header("Authorization", probe),
                401, "reg-validate-revoked.json");
        assertGolden(json(post("/api/v1/auth/login"),
                "{\"email\":\"" + PROBE_EMAIL + "\",\"password\":\"" + PROBE_PW + "\"}"),
                401, "reg-relogin-old.json");
        MvcResult relogin = mockMvc.perform(json(post("/api/v1/auth/login"),
                "{\"email\":\"" + PROBE_EMAIL + "\",\"password\":\"" + PROBE_PW_NEW + "\"}")).andReturn();
        assertEquals(200, relogin.getResponse().getStatus(), raw(relogin));
        assertMasked("reg-relogin-new.json", raw(relogin));
    }

    // ── 6) auto-setup（standard edition → 403） ────────────────────────────

    @Test
    void autoSetupForbidden() throws Exception {
        assertGolden(json(post("/api/v1/auth/auto-setup"), "{}"), 403, "reg-auto-setup.json");
    }

    // ── 7) 邀请族：lookup + register-by-invite ─────────────────────────────

    @Test
    void invitationFamily() throws Exception {
        assertGolden(json(post("/api/v1/auth/invitations/lookup"), "{\"token\":\"nosuchtoken123\"}"),
                410, "reg-inv-lookup-bad.json");
        assertGolden(json(post("/api/v1/auth/invitations/lookup"), "{}"),
                400, "reg-inv-lookup-empty.json");
        assertGolden(json(post("/api/v1/auth/register-by-invite"),
                "{\"token\":\"nosuchtoken123\",\"email\":\"" + INVITE_EMAIL + "\",\"username\":\""
                        + INVITE_USER + "\",\"password\":\"" + PROBE_PW + "\"}"),
                410, "reg-byinvite-badtoken.json");
        assertGolden(json(post("/api/v1/auth/register-by-invite"), "{}"),
                400, "reg-byinvite-binding.json");

        MvcResult lookup = mockMvc.perform(json(post("/api/v1/auth/invitations/lookup"),
                "{\"token\":\"" + INVITE_TOKEN + "\"}")).andReturn();
        assertEquals(200, lookup.getResponse().getStatus(), raw(lookup));
        assertMasked("reg-inv-lookup-ok.json", raw(lookup));

        assertGolden(json(post("/api/v1/auth/register-by-invite"),
                "{\"token\":\"" + INVITE_TOKEN + "\",\"email\":\"" + OWNER_EMAIL
                        + "\",\"username\":\"someone-ok\",\"password\":\"" + PROBE_PW + "\"}"),
                409, "reg-byinvite-existing-email.json");
        assertGolden(json(post("/api/v1/auth/register-by-invite"),
                "{\"token\":\"" + INVITE_TOKEN + "\",\"email\":\"" + INVITE_EMAIL
                        + "\",\"username\":\"" + INVITE_USER + "\",\"password\":\"abcdefgh\"}"),
                400, "reg-byinvite-weakpw.json");

        MvcResult joined = mockMvc.perform(json(post("/api/v1/auth/register-by-invite"),
                "{\"token\":\"" + INVITE_TOKEN + "\",\"email\":\"" + INVITE_EMAIL
                        + "\",\"username\":\"" + INVITE_USER + "\",\"password\":\"" + PROBE_PW + "\"}"))
                .andReturn();
        assertEquals(201, joined.getResponse().getStatus(), raw(joined));
        assertMasked("reg-byinvite-success.json", raw(joined));
    }

    // ── 8) invite_only 模式（sysadmin 切换 → 403 → 还原） ────────────────────

    @Test
    void inviteOnlyMode() throws Exception {
        assertGolden(get("/api/v1/auth/config"), 200, "reg-config.json");

        String sysAdmin = "Bearer " + login(SYS_EMAIL, "Passw0rd!");
        MvcResult set = mockMvc.perform(json(
                put("/api/v1/system/admin/settings/auth.registration_mode").header("Authorization", sysAdmin),
                "{\"value\":\"invite_only\"}")).andReturn();
        assertEquals(200, set.getResponse().getStatus(), raw(set));

        assertGolden(json(post("/api/v1/auth/register"),
                "{\"username\":\"regblock-user\",\"email\":\"reg-block@weknora.test\","
                        + "\"password\":\"" + PROBE_PW + "\"}"), 403, "reg-invite-only.json");
        assertGolden(get("/api/v1/auth/config"), 200, "reg-config-invite-only.json");

        MvcResult restore = mockMvc.perform(delete(
                "/api/v1/system/admin/settings/auth.registration_mode")
                .header("Authorization", sysAdmin)).andReturn();
        assertEquals(204, restore.getResponse().getStatus(), raw(restore));
        assertGolden(get("/api/v1/auth/config"), 200, "reg-config-restored.json");
    }

    // ── 工具 ──────────────────────────────────────────────────────────────

    private void registerProbe() throws Exception {
        MvcResult r = mockMvc.perform(json(post("/api/v1/auth/register"),
                "{\"username\":\"" + PROBE_USER + "\",\"email\":\"" + PROBE_EMAIL
                        + "\",\"password\":\"" + PROBE_PW + "\"}")).andReturn();
        assertEquals(201, r.getResponse().getStatus(), raw(r));
    }

    private long currentTenantId(String bearer) throws Exception {
        MvcResult me = mockMvc.perform(get("/api/v1/auth/me")
                .header("Authorization", bearer)).andReturn();
        // PR4：键序归一后邻接正则不可靠 → Jackson 直取
        var __root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw(me));
        return __root.path("user").path("tenantId").asLong();
    }

    private String login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(json(post("/api/v1/auth/login"),
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")).andReturn();
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

    /** 动态 golden：两侧同掩码后逐字节。 */
    private void assertMasked(String goldenName, String actual) throws Exception {
        assertEquals(mask(golden(goldenName)), mask(actual), goldenName);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, String body) {
        return req.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper RAW_SEMANTIC_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static String raw(MvcResult r) throws Exception {
        // PR4 语义比较：与 golden 同侧归一（非 JSON 文本原样）
        return com.ragagent.support.ContractJson.semantic(RAW_SEMANTIC_MAPPER,
                r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
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

    /** 与 golden 比对前对动态字段做同一种掩码（顺序敏感：先 token/uuid 再时间戳）。 */
    private static String mask(String s) {
        String out = TOKEN_VALUE.matcher(s).replaceAll("\"token\":\"<masked>\"");
        out = REFRESH_VALUE.matcher(out).replaceAll("\"refreshToken\":\"<masked>\"");
        out = UUID_VALUE.matcher(out).replaceAll("<uuid>");
        out = TENANT_ID_VALUE.matcher(out).replaceAll("\"tenantId\":<tid>");
        out = LAST_ACTIVE_VALUE.matcher(out).replaceAll("\"lastActiveTenantId\":<tid>");
        out = NUMERIC_ID.matcher(out).replaceAll("\"id\":<tid>");
        out = TS_PATTERN.matcher(out).replaceAll("\"<ts>\"");
        return out;
    }
}
