package com.ragagent.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import com.ragagent.system.mapper.SystemSettingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 系统管理端契约测试（波 2 收官批：/system 组 7 条 + /system/admin 组 16 条）。
 * golden：record-system-golden.sh（sys-* / adm-*，全部 Go dev 实录）。
 *
 * <p>种子严格复刻录制脚本：租户 10002 + 系统管理员 javasysadmin（is_system_admin=true，
 * owner 成员行）+ 基线 owner/viewer。掩码：UUID / 时间戳 / key 数字 id / key 明文 /
 * quota affected / started_at / uptime_seconds / db_version / timestamp。</p>
 *
 * <p><b>部署状态差异（不做 golden 字节比对的条目，报告注明）</b>：</p>
 * <ul>
 *   <li>capabilities：Go dev 已注册 agents/im/embed 路由，
 *       Java 均未翻译 → supported=false。按 Java 部署断言 + 与 golden 的键集对比；</li>
 *   <li>parser-engines（与 check）：golden 打了真 docreader（connected=true，远端覆盖
 *       builtin 描述/文件类型 + markitdown/opendataloader 追加）；测试禁真实网络 →
 *       connected=false 分支按 Go 源（静态注册表）内联断言；</li>
 *   <li>info 的 db_version：Go=golang-migrate 97；Java 在 H2 无 flyway_schema_history →
 *       键省略（掩码比对）。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SystemContractTest {

    private static final String BCRYPT =
            "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK";
    private static final long TENANT = 10002L;
    private static final String OWNER = "11111111-2222-3333-4444-555555555501";
    private static final String VIEWER = "11111111-2222-3333-4444-555555555504";
    private static final String SYS_ADMIN = "11111111-2222-3333-4444-555555555701";
    private static final String UNKNOWN = "11111111-2222-3333-4444-999999999999";

    private static final String OWNER_EMAIL = "java-phase1@weknora.test";
    private static final String VIEWER_EMAIL = "java-phase1-viewer@weknora.test";
    private static final String SYS_EMAIL = "java-sys-admin@weknora.test";

    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");

    private static final Pattern UUID_VALUE = Pattern.compile(
            "\"([a-zA-Z_]+)\":\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"");
    private static final Pattern TS_VALUE = Pattern.compile(
            "\"([a-zA-Z_]+)\":\"[2-9]\\d{3}-\\d{2}-\\d{2}T[0-9:.+\\-Z]+\"");
    /** 数字 id（key 行 / 设置持久行——两侧取值都是部署态，统一掩码；虚拟行 id:0 也遮） */
    private static final Pattern KEY_ID = Pattern.compile("\"id\":(\\d+)");
    private static final Pattern API_KEY_TOKEN = Pattern.compile("\"token\":\"(sk-[^\"]+)\"");
    /** api_key 字段是 Go maskManagedAPIKey 的输出（每次随机）→ 两侧同掩码 */
    private static final Pattern API_KEY_FIELD = Pattern.compile("\"api_key\":\"[^\"]*\"");
    private static final Pattern AFFECTED = Pattern.compile("\"affected\":\\d+");
    private static final Pattern QUEUE_TS = Pattern.compile("\"timestamp\":\\d+");

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
    private SystemSettingMapper settingMapper;

    private String owner;
    private String viewer;
    private String sysAdmin;

    @BeforeEach
    void seed() throws Exception {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);

        Tenant tenant = new Tenant();
        tenant.setId(TENANT);
        tenant.setName("phase1-test-tenant");
        tenant.setStatus("active");
        tenantMapper.insert(tenant);

        seedUser(OWNER, "phase1test", OWNER_EMAIL, false);
        seedUser(VIEWER, "phase1viewer", VIEWER_EMAIL, false);
        seedUser(SYS_ADMIN, "javasysadmin", SYS_EMAIL, true);
        // 基线成员行（dev PG 的 owner/viewer 有成员行；登录解析活动租户依赖它）
        seedMember(OWNER, "owner", "2026-09-01T10:00:00Z");
        seedMember(VIEWER, "viewer", "2026-09-01T10:01:00Z");
        seedMember(SYS_ADMIN, "owner", "2026-09-01T10:03:00Z");

        owner = "Bearer " + login(OWNER_EMAIL);
        viewer = "Bearer " + login(VIEWER_EMAIL);
        sysAdmin = "Bearer " + login(SYS_EMAIL);
    }

    private void seedUser(String id, String username, String email, boolean sysAdminFlag) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(BCRYPT);
        user.setTenantId(TENANT);
        user.setIsActive(true);
        user.setIsSystemAdmin(sysAdminFlag);
        user.setPreferences(new UserPreferences());
        userMapper.insert(user);
    }

    private void seedMember(String userId, String role, String joinedAt) {
        TenantMember member = new TenantMember();
        member.setUserId(userId);
        member.setTenantId(TENANT);
        member.setRole(role);
        member.setStatus("active");
        member.setJoinedAt(OffsetDateTime.parse(joinedAt));
        memberMapper.insert(member);
    }

    // ════════════════ /system 组（读端） ════════════════

    /**
     * capabilities：响应外壳 + 键集与 Go golden 一致；各能力**值**按 Java 部署断言
     * （Go dev 的 agents/im/embed=true 是它的部署状态；organizations 随空间分享裁撤）。
     */
    @Test
    void capabilitiesMatchesDeployment() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/system/capabilities")
                .header("Authorization", owner)).andReturn();
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        String java = raw(r);
        // PR4：外壳与键集改树断言（键序已归一）
        {
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(java);
            assertThat(root.path("edition").asText()).isEqualTo("standard");
        }
        String goldenCaps = golden("sys-capabilities.json");
        for (String key : subsetKeys(goldenCaps).split("\\|")) {
            assertThat(java).contains(key);
        }
        // Java 部署：api/mcp/websearch/vectorstore/storage=true；agents 随波 3 agents 批
        // 注册（routes_agent.go 的 agents 家族落地）→ supported=true；其余 route_not_registered
        assertThat(java).contains("\"agents\":{\"reason\":null,\"supported\":true}");
        assertThat(java).contains("\"integrations.api\":{\"reason\":null,\"supported\":true}");
        assertThat(java).contains("\"settings.mcp\":{\"reason\":null,\"supported\":true}");
        // settings.sandbox 两键随沙箱裁剪退役（对照 capabilities 键集同步收缩）
    }

    /** 从 golden 提取 map 键名（部署无关的结构对齐检查）。 */
    private static String subsetKeys(String golden) {
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("\"([a-z.]+)\\\":\\{\"supported\"").matcher(golden.replace("\\", ""));
        // golden 是紧凑 JSON："agents":{"supported":true}
        Matcher km = Pattern.compile("\"([a-z.]+)\":\\{\"supported\"").matcher(golden);
        while (km.find()) {
            if (sb.length() > 0) {
                sb.append("|");
            }
            sb.append("\"").append(km.group(1)).append("\":{\"supported\"");
        }
        return sb.toString();
    }

    /** viewer 也能读（apiKeyAny + Viewer 双轨之一）。 */
    @Test
    void capabilitiesViewerAllowed() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/system/capabilities")
                .header("Authorization", viewer)).andReturn();
        assertEquals(200, r.getResponse().getStatus(), raw(r));
    }

    /** scoped/full key 无 X-Tenant-ID → 409 TENANT_REQUIRED（平台 Key 专属文案，Go 实录）。 */
    @Test
    void capabilitiesWithApiKeyWithoutTenant() throws Exception {
        String key = createPlatformKeyRaw();
        MvcResult r = mockMvc.perform(get("/api/v1/system/capabilities")
                .header("X-API-Key", key)).andReturn();
        assertEquals(409, r.getResponse().getStatus(), raw(r));
        assertEquals("{\"code\":\"TENANT_REQUIRED\",\"error\":\"Workspace required: "
                + "platform API keys must send X-Tenant-ID\"}", raw(r));
        deletePlatformKey(key);
    }

    /** info：掩码 db_version/started_at/uptime_seconds 后与 Go golden 字节比对。 */
    @Test
    void infoMatchesGo() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/system/info")
                .header("Authorization", owner)).andReturn();
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(maskInfo(golden("sys-info.json")), maskInfo(raw(r)));
    }

    /** parser-engines：测试禁网络 → connected=false 的静态注册表形态（Go 源内联断言）。 */
    @Test
    void parserEnginesOfflineShape() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/system/parser-engines")
                .header("Authorization", owner)).andReturn();
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        String java = raw(r);
        // 外壳字母序 + connected=false + 本地 8 引擎（无远端追加）
        assertThat(java).contains("\"connected\":false");
        assertThat(java).contains("\"docreaderTransport\":\"grpc\"");
        for (String engine : new String[]{"builtin", "simple", "anydoc", "weknoracloud",
                "mineru", "mineru_cloud", "paddleocr_vl", "paddleocr_vl_cloud"}) {
            assertThat(java).contains("\"name\":\"" + engine + "\"");
        }
        assertThat(java).doesNotContain("markitdown").doesNotContain("opendataloader");
        // 未连接 → builtin 不可用；simple 恒可用；UnavailableReason 恒输出（Go 无 json tag）
        // PR4：相邻键子串在键序归一后不可靠 → 树断言
        {
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(java);
            var engines = root.path("engines");
            assertThat(engines.isArray()).isTrue();
            com.fasterxml.jackson.databind.JsonNode hit = null;
            for (var e : engines) {
                if ("builtin".equals(e.path("name").asText())) {
                    hit = e;
                    break;
                }
            }
            assertThat(hit).as("builtin engine row").isNotNull();
            assertThat(hit.path("available").asBoolean()).isFalse();
            assertThat(hit.path("unavailableReason").asText())
                    .isEqualTo("DocReader service not connected");
        }
    }

    /** storage-engine-status：H2 干净租户 → 与 Go golden 字节一致（全确定性）。 */
    @Test
    void storageStatusMatchesGo() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/system/storage-engine-status")
                .header("Authorization", owner)).andReturn();
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("sys-storage-status.json"), raw(r));
    }

    // ════════════════ /system 组（Admin 探测） ════════════════

    @Test
    void parserCheckBadJsonMatchesGo() throws Exception {
        MvcResult r = mockMvc.perform(jsonBody(post("/api/v1/system/parser-engines/check"), sysAdmin, "nope"))
                .andReturn();
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals("{\"error\":{\"code\":1000,\"details\":\"请求体格式不正确\",\"message\":\"请求参数不合法\"},\"success\":false}", raw(r));
    }

    /** viewer 打 check → 403（Admin 门）。 */
    @Test
    void parserCheckViewerForbidden() throws Exception {
        MvcResult r = mockMvc.perform(jsonBody(post("/api/v1/system/parser-engines/check"), viewer, "{}"))
                .andReturn();
        assertEquals(403, r.getResponse().getStatus(), raw(r));
        assertEquals("{\"error\":\"Forbidden: insufficient workspace role\"}", raw(r));
    }

    @Test
    void reconnectValidationMatchesGo() throws Exception {
        MvcResult r = mockMvc.perform(jsonBody(post("/api/v1/system/docreader/reconnect"), sysAdmin, "{}"))
                .andReturn();
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals("{\"error\":{\"code\":1000,\"details\":null,\"message\":\"请提供 addr 参数\"},\"success\":false}", raw(r));

        MvcResult b = mockMvc.perform(jsonBody(post("/api/v1/system/docreader/reconnect"), sysAdmin,
                "{\"addr\":\"   \"}")).andReturn();
        assertEquals(400, b.getResponse().getStatus(), raw(b));
        assertEquals("{\"error\":{\"code\":1000,\"details\":null,\"message\":\"addr 不能为空\"},\"success\":false}", raw(b));

        MvcResult j = mockMvc.perform(jsonBody(post("/api/v1/system/docreader/reconnect"), sysAdmin, "nope"))
                .andReturn();
        assertEquals(400, j.getResponse().getStatus(), raw(j));
        assertEquals("{\"error\":{\"code\":1000,\"details\":null,\"message\":\"请提供 addr 参数\"},\"success\":false}", raw(j));

        MvcResult s = mockMvc.perform(jsonBody(post("/api/v1/system/docreader/reconnect"), sysAdmin,
                "{\"addr\":\"http://169.254.169.254:50051\"}")).andReturn();
        assertEquals(400, s.getResponse().getStatus(), raw(s));
        assertEquals(mask(golden("sys-reconnect-ssrf.json")), mask(raw(s)));
    }

    @Test
    void storageCheckMatchesGo() throws Exception {
        MvcResult m = mockMvc.perform(jsonBody(post("/api/v1/system/storage-engine-check"), sysAdmin,
                "{\"provider\":\"minio\"}")).andReturn();
        assertEquals(200, m.getResponse().getStatus(), raw(m));
        assertEquals(golden("sys-storage-check-minio-empty.json"), raw(m));

        MvcResult b = mockMvc.perform(jsonBody(post("/api/v1/system/storage-engine-check"), sysAdmin,
                "{\"provider\":\"bogus\"}")).andReturn();
        assertEquals(403, b.getResponse().getStatus(), raw(b));
        assertEquals(golden("sys-storage-check-bogus.json"), raw(b));

        MvcResult j = mockMvc.perform(jsonBody(post("/api/v1/system/storage-engine-check"), sysAdmin, "nope"))
                .andReturn();
        assertEquals(400, j.getResponse().getStatus(), raw(j));
        assertEquals(golden("sys-storage-check-badjson.json"), raw(j));

        MvcResult l = mockMvc.perform(jsonBody(post("/api/v1/system/storage-engine-check"), sysAdmin,
                "{\"provider\":\"local\"}")).andReturn();
        assertEquals(200, l.getResponse().getStatus(), raw(l));
        assertEquals(golden("sys-storage-check-local.json"), raw(l));
    }

    // ════════════════ /system/admin 组：守卫 ════════════════

    @Test
    void adminGuardMatchesGo() throws Exception {
        MvcResult o = mockMvc.perform(post("/api/v1/system/admin/promote")
                .header("Authorization", owner)
                .contentType("application/json").content("{}")).andReturn();
        assertEquals(403, o.getResponse().getStatus(), raw(o));
        assertEquals("{\"error\":\"Forbidden: system administrator required\"}", raw(o));

        MvcResult v = mockMvc.perform(get("/api/v1/system/admin/list")
                .header("Authorization", viewer)).andReturn();
        assertEquals(403, v.getResponse().getStatus(), raw(v));
        assertEquals("{\"error\":\"Forbidden: system administrator required\"}", raw(v));

        MvcResult n = mockMvc.perform(get("/api/v1/system/admin/list")).andReturn();
        assertEquals(401, n.getResponse().getStatus(), raw(n));
        assertEquals(golden("adm-guard-noauth.json"), raw(n));
    }

    // ════════════════ /system/admin 组：升降级 ════════════════

    @Test
    void promoteAndRevokeMatchGo() throws Exception {
        // 1) {} → 400 either-required
        MvcResult missing = mockMvc.perform(jsonBody(post("/api/v1/system/admin/promote"), sysAdmin, "{}"))
                .andReturn();
        assertEquals(400, missing.getResponse().getStatus(), raw(missing));
        assertEquals(golden("adm-promote-missing.json"), raw(missing));
        // 2) unknown id → 404
        MvcResult nf = mockMvc.perform(jsonBody(post("/api/v1/system/admin/promote"), sysAdmin,
                "{\"userId\":\"" + UNKNOWN + "\"}")).andReturn();
        assertEquals(404, nf.getResponse().getStatus(), raw(nf));
        assertEquals(golden("adm-promote-404.json"), raw(nf));
        // 3) list（只有 sysadmin 一行；掩码 UUID/时间戳）
        MvcResult list = mockMvc.perform(get("/api/v1/system/admin/list")
                .header("Authorization", sysAdmin)).andReturn();
        assertEquals(200, list.getResponse().getStatus(), raw(list));
        assertEquals(mask(golden("adm-list.json")), mask(raw(list)));
        // 4) promote self → 幂等 200
        MvcResult self = mockMvc.perform(jsonBody(post("/api/v1/system/admin/promote"), sysAdmin,
                "{\"userId\":\"" + SYS_ADMIN + "\"}")).andReturn();
        assertEquals(200, self.getResponse().getStatus(), raw(self));
        assertEquals(mask(golden("adm-promote-self.json")), mask(raw(self)));
        // 5) promote owner（真实）→ is_system_admin=true
        MvcResult po = mockMvc.perform(jsonBody(post("/api/v1/system/admin/promote"), sysAdmin,
                "{\"userId\":\"" + OWNER + "\"}")).andReturn();
        assertEquals(200, po.getResponse().getStatus(), raw(po));
        assertEquals(mask(golden("adm-promote-owner.json")), mask(raw(po)));
        // 6) revoke self → 400
        MvcResult rs = mockMvc.perform(jsonBody(post("/api/v1/system/admin/revoke"), sysAdmin,
                "{\"userId\":\"" + SYS_ADMIN + "\"}")).andReturn();
        assertEquals(400, rs.getResponse().getStatus(), raw(rs));
        assertEquals(golden("adm-revoke-self.json"), raw(rs));
        // 7) revoke owner（此时 owner 已是管理员 → 真实撤销）
        MvcResult rn = mockMvc.perform(jsonBody(post("/api/v1/system/admin/revoke"), sysAdmin,
                "{\"userId\":\"" + OWNER + "\"}")).andReturn();
        assertEquals(200, rn.getResponse().getStatus(), raw(rn));
        assertEquals(mask(golden("adm-revoke-noop.json")), mask(raw(rn)));
        // 8) 再提权 + 再撤销（真实撤销形态）
        mockMvc.perform(jsonBody(post("/api/v1/system/admin/promote"), sysAdmin,
                "{\"userId\":\"" + OWNER + "\"}")).andReturn();
        MvcResult ro = mockMvc.perform(jsonBody(post("/api/v1/system/admin/revoke"), sysAdmin,
                "{\"userId\":\"" + OWNER + "\"}")).andReturn();
        assertEquals(200, ro.getResponse().getStatus(), raw(ro));
        assertEquals(mask(golden("adm-revoke-owner.json")), mask(raw(ro)));
        // 9) revoke 未知 → 404；{} → validator 原文
        MvcResult r404 = mockMvc.perform(jsonBody(post("/api/v1/system/admin/revoke"), sysAdmin,
                "{\"userId\":\"" + UNKNOWN + "\"}")).andReturn();
        assertEquals(404, r404.getResponse().getStatus(), raw(r404));
        assertEquals(golden("adm-revoke-404.json"), raw(r404));
        MvcResult rbad = mockMvc.perform(jsonBody(post("/api/v1/system/admin/revoke"), sysAdmin, "{}"))
                .andReturn();
        assertEquals(400, rbad.getResponse().getStatus(), raw(rbad));
        assertEquals(golden("adm-revoke-badbody.json"), raw(rbad));
        // 10) 非管理员 revoke → 幂等 200（changed=false 形态同 200 + is_system_admin=false）
        MvcResult rn2 = mockMvc.perform(jsonBody(post("/api/v1/system/admin/revoke"), sysAdmin,
                "{\"userId\":\"" + VIEWER + "\"}")).andReturn();
        assertEquals(200, rn2.getResponse().getStatus(), raw(rn2));
        // 11) 终态 list
        MvcResult after = mockMvc.perform(get("/api/v1/system/admin/list")
                .header("Authorization", sysAdmin)).andReturn();
        assertEquals(200, after.getResponse().getStatus(), raw(after));
        assertEquals(mask(golden("adm-list-after.json")), mask(raw(after)));
    }

    // ════════════════ /system/admin 组：用户管理 ════════════════

    @Test
    void resetPasswordMatchesGo() throws Exception {
        // 顺序与录制一致：weak → self → 404 → badbody → ok
        MvcResult weak = mockMvc.perform(jsonBody(post("/api/v1/system/admin/users/reset-password"), sysAdmin,
                "{\"email\":\"java-phase1-viewer@weknora.test\",\"newPassword\":\"short\"}")).andReturn();
        assertEquals(400, weak.getResponse().getStatus(), raw(weak));
        assertEquals(golden("adm-reset-weak.json"), raw(weak));

        MvcResult self = mockMvc.perform(jsonBody(post("/api/v1/system/admin/users/reset-password"), sysAdmin,
                "{\"email\":\"java-sys-admin@weknora.test\",\"newPassword\":\"Passw0rd!\"}")).andReturn();
        assertEquals(400, self.getResponse().getStatus(), raw(self));
        assertEquals(golden("adm-reset-self.json"), raw(self));

        MvcResult nf = mockMvc.perform(jsonBody(post("/api/v1/system/admin/users/reset-password"), sysAdmin,
                "{\"email\":\"nobody@weknora.test\",\"newPassword\":\"Passw0rd!\"}")).andReturn();
        assertEquals(404, nf.getResponse().getStatus(), raw(nf));
        assertEquals(golden("adm-reset-404.json"), raw(nf));

        MvcResult bad = mockMvc.perform(jsonBody(post("/api/v1/system/admin/users/reset-password"), sysAdmin,
                "{\"email\":\"notanemail\",\"newPassword\":\"x\"}")).andReturn();
        assertEquals(400, bad.getResponse().getStatus(), raw(bad));
        assertEquals(golden("adm-reset-badbody.json"), raw(bad));

        // 成功（viewer 会话被吊销 → 重登可用；密码相同）
        MvcResult ok = mockMvc.perform(jsonBody(post("/api/v1/system/admin/users/reset-password"), sysAdmin,
                "{\"email\":\"java-phase1-viewer@weknora.test\",\"newPassword\":\"Passw0rd!\"}")).andReturn();
        assertEquals(204, ok.getResponse().getStatus(), raw(ok));
        assertEquals("", raw(ok)); // 204 无响应体
        // 被吊销的旧 token 401（AdminResetPassword 的 RevokeTokensByUserID）
        MvcResult revoked = mockMvc.perform(get("/api/v1/system/info")
                .header("Authorization", viewer)).andReturn();
        assertEquals(401, revoked.getResponse().getStatus(), raw(revoked));
    }

    @Test
    void createUserMatchesGo() throws Exception {
        MvcResult bad = mockMvc.perform(jsonBody(post("/api/v1/system/admin/users/create"), sysAdmin,
                "{\"username\":\"x\",\"email\":\"notanemail\"}")).andReturn();
        assertEquals(400, bad.getResponse().getStatus(), raw(bad));
        assertEquals(golden("adm-create-badbody.json"), raw(bad));

        MvcResult shortName = mockMvc.perform(jsonBody(post("/api/v1/system/admin/users/create"), sysAdmin,
                "{\"username\":\"x\",\"email\":\"java-sys-golden-1@weknora.test\"}")).andReturn();
        assertEquals(400, shortName.getResponse().getStatus(), raw(shortName));
        assertEquals(golden("adm-create-shortname.json"), raw(shortName));

        MvcResult missing = mockMvc.perform(jsonBody(post("/api/v1/system/admin/users/create"), sysAdmin, "{}"))
                .andReturn();
        assertEquals(400, missing.getResponse().getStatus(), raw(missing));
        assertEquals(golden("adm-create-missing.json"), raw(missing));

        MvcResult created = mockMvc.perform(jsonBody(post("/api/v1/system/admin/users/create"), sysAdmin,
                "{\"username\":\"sysgolden1\",\"email\":\"java-sys-golden-1@weknora.test\","
                        + "\"password\":\"Passw0rd!\"}")).andReturn();
        assertEquals(201, created.getResponse().getStatus(), raw(created));
        assertEquals(maskTenant(mask(golden("adm-create.json"))), maskTenant(mask(raw(created))));

        MvcResult dup = mockMvc.perform(jsonBody(post("/api/v1/system/admin/users/create"), sysAdmin,
                "{\"username\":\"sysgolden1\",\"email\":\"java-sys-golden-1@weknora.test\","
                        + "\"password\":\"Passw0rd!\"}")).andReturn();
        assertEquals(200, dup.getResponse().getStatus(), raw(dup));
        assertEquals(maskTenant(mask(golden("adm-create-dup.json"))), maskTenant(mask(raw(dup))));

        MvcResult conflict = mockMvc.perform(jsonBody(post("/api/v1/system/admin/users/create"), sysAdmin,
                "{\"username\":\"sysgoldenX\",\"email\":\"java-sys-golden-1@weknora.test\","
                        + "\"password\":\"Passw0rd!\"}")).andReturn();
        assertEquals(409, conflict.getResponse().getStatus(), raw(conflict));
        assertEquals(golden("adm-create-conflict.json"), raw(conflict));

        MvcResult weak = mockMvc.perform(jsonBody(post("/api/v1/system/admin/users/create"), sysAdmin,
                "{\"username\":\"sysgolden3\",\"email\":\"java-sys-golden-3@weknora.test\","
                        + "\"password\":\"short\"}")).andReturn();
        assertEquals(400, weak.getResponse().getStatus(), raw(weak));
        assertEquals(golden("adm-create-weakpw.json"), raw(weak));

        // 生成密码：generated_password 出现（掩码）+ 201
        MvcResult generated = mockMvc.perform(jsonBody(post("/api/v1/system/admin/users/create"), sysAdmin,
                "{\"username\":\"sysgolden2\",\"email\":\"java-sys-golden-2@weknora.test\"}")).andReturn();
        assertEquals(201, generated.getResponse().getStatus(), raw(generated));
        assertThat(mask(raw(generated))).contains("\"generatedPassword\":\"<genpw>\"");
        assertEquals(maskTenant(mask(golden("adm-create-generated.json"))),
                maskTenant(mask(raw(generated))));
    }

    // ════════════════ /system/admin 组：平台 API Key ════════════════

    @Test
    void platformKeysMatchGo() throws Exception {
        MvcResult empty = mockMvc.perform(get("/api/v1/system/admin/api-keys")
                .header("Authorization", sysAdmin)).andReturn();
        assertEquals(200, empty.getResponse().getStatus(), raw(empty));
        assertEquals(golden("adm-key-list-empty.json"), raw(empty));

        MvcResult noname = mockMvc.perform(jsonBody(post("/api/v1/system/admin/api-keys"), sysAdmin,
                "{\"name\":\"  \",\"capabilities\":[\"chat\"]}")).andReturn();
        assertEquals(400, noname.getResponse().getStatus(), raw(noname));
        assertEquals(mask(golden("adm-key-create-noname.json")), mask(raw(noname)));

        MvcResult badcap = mockMvc.perform(jsonBody(post("/api/v1/system/admin/api-keys"), sysAdmin,
                "{\"name\":\"x\",\"capabilities\":[\"bogus\"]}")).andReturn();
        assertEquals(400, badcap.getResponse().getStatus(), raw(badcap));
        assertEquals(golden("adm-key-create-badcap.json"), raw(badcap));

        MvcResult exppast = mockMvc.perform(jsonBody(post("/api/v1/system/admin/api-keys"), sysAdmin,
                "{\"name\":\"x\",\"capabilities\":[\"chat\"],\"expiresAtUnix\":1000000000}")).andReturn();
        assertEquals(400, exppast.getResponse().getStatus(), raw(exppast));
        assertEquals(golden("adm-key-create-exppast.json"), raw(exppast));

        MvcResult badjson = mockMvc.perform(jsonBody(post("/api/v1/system/admin/api-keys"), sysAdmin, "nope"))
                .andReturn();
        assertEquals(400, badjson.getResponse().getStatus(), raw(badjson));
        assertEquals(mask(golden("adm-key-create-badjson.json")), mask(raw(badjson)));

        // 创建（201；token 明文 + api_key 掩码 + expires_at 原样）
        MvcResult created = mockMvc.perform(jsonBody(post("/api/v1/system/admin/api-keys"), sysAdmin,
                "{\"name\":\"sys-golden-key\",\"capabilities\":[\"chat\"],\"expiresAtUnix\":4102444800}"))
                .andReturn();
        assertEquals(201, created.getResponse().getStatus(), raw(created));
        assertThat(mask(raw(created))).contains("\"token\":\"<keytoken>\"");
        assertEquals(mask(golden("adm-key-create.json")), mask(raw(created)));

        // 列表（掩码 id；录制序：list 在 key 被使用之前——Go 的 last_used_at 触碰
        // 在 AuthenticateAPIKey，list 先行才与 golden 的键省略一致）
        MvcResult list = mockMvc.perform(get("/api/v1/system/admin/api-keys")
                .header("Authorization", sysAdmin)).andReturn();
        assertEquals(200, list.getResponse().getStatus(), raw(list));
        assertEquals(mask(golden("adm-key-list.json")), mask(raw(list)));

        // 平台 key 打 settings（无 platform 能力 → 403 门禁文案）
        // PR4：键序归一后邻接正则不可靠 → Jackson 直取
        String key = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(raw(created)).path("token").asText();
        assertThat(key).startsWith("sk-");
        MvcResult guard = mockMvc.perform(get("/api/v1/system/admin/settings")
                .header("X-API-Key", key)).andReturn();
        assertEquals(403, guard.getResponse().getStatus(), raw(guard));
        assertEquals(golden("adm-guard-platformkey.json"), raw(guard));

        // 删除：404 / 非法 id / 成功 / 终态列表
        MvcResult d404 = mockMvc.perform(delete("/api/v1/system/admin/api-keys/999999")
                .header("Authorization", sysAdmin)).andReturn();
        assertEquals(404, d404.getResponse().getStatus(), raw(d404));
        assertEquals(mask(golden("adm-key-delete-404.json")), mask(raw(d404)));

        MvcResult dbad = mockMvc.perform(delete("/api/v1/system/admin/api-keys/abc")
                .header("Authorization", sysAdmin)).andReturn();
        assertEquals(400, dbad.getResponse().getStatus(), raw(dbad));
        assertEquals(golden("adm-key-delete-badid.json"), raw(dbad));

        // PR4：键序归一后邻接正则不可靠 → Jackson 直取
        long delId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(raw(created)).path("id").asLong();
        MvcResult del = mockMvc.perform(delete("/api/v1/system/admin/api-keys/" + delId)
                .header("Authorization", sysAdmin)).andReturn();
        assertEquals(204, del.getResponse().getStatus(), raw(del));
        assertEquals("", raw(del)); // 204 无响应体

        MvcResult after = mockMvc.perform(get("/api/v1/system/admin/api-keys")
                .header("Authorization", sysAdmin)).andReturn();
        assertEquals(200, after.getResponse().getStatus(), raw(after));
        assertEquals(golden("adm-key-list-after.json"), raw(after));
    }

    // ════════════════ /system/admin 组：settings ════════════════

    @Test
    void settingsMatchGo() throws Exception {
        // 空表 → 全虚拟行（id 0 + Go 零值时间）→ 字节一致
        MvcResult list = mockMvc.perform(get("/api/v1/system/admin/settings")
                .header("Authorization", sysAdmin)).andReturn();
        assertEquals(200, list.getResponse().getStatus(), raw(list));
        assertEquals(golden("adm-settings-list.json"), raw(list));

        MvcResult virtual = mockMvc.perform(get("/api/v1/system/admin/settings/auth.registration_mode")
                .header("Authorization", sysAdmin)).andReturn();
        assertEquals(200, virtual.getResponse().getStatus(), raw(virtual));
        assertEquals(golden("adm-settings-get-virtual.json"), raw(virtual));

        MvcResult unknown = mockMvc.perform(get("/api/v1/system/admin/settings/nope.key")
                .header("Authorization", sysAdmin)).andReturn();
        assertEquals(400, unknown.getResponse().getStatus(), raw(unknown));
        assertEquals(golden("adm-settings-404.json"), raw(unknown));

        MvcResult badType = mockMvc.perform(jsonBody(put("/api/v1/system/admin/settings/asynq.core_concurrency"),
                sysAdmin, "{\"value\":\"abc\"}")).andReturn();
        assertEquals(400, badType.getResponse().getStatus(), raw(badType));
        assertEquals(golden("adm-settings-put-badtype.json"), raw(badType));

        MvcResult badEnum = mockMvc.perform(jsonBody(put("/api/v1/system/admin/settings/auth.registration_mode"),
                sysAdmin, "{\"value\":\"bogus\"}")).andReturn();
        assertEquals(400, badEnum.getResponse().getStatus(), raw(badEnum));
        assertEquals(golden("adm-settings-put-enum.json"), raw(badEnum));

        MvcResult nullValue = mockMvc.perform(jsonBody(put("/api/v1/system/admin/settings/auth.registration_mode"),
                sysAdmin, "{}")).andReturn();
        assertEquals(400, nullValue.getResponse().getStatus(), raw(nullValue));
        assertEquals(golden("adm-settings-put-null.json"), raw(nullValue));

        MvcResult badJson = mockMvc.perform(jsonBody(put("/api/v1/system/admin/settings/auth.registration_mode"),
                sysAdmin, "nope")).andReturn();
        assertEquals(400, badJson.getResponse().getStatus(), raw(badJson));
        assertEquals(golden("adm-settings-put-badjson.json"), raw(badJson));

        // PUT 落库（响应行：last_modified_by=调用者 UUID、时间戳 → 掩码）
        MvcResult put = mockMvc.perform(jsonBody(put("/api/v1/system/admin/settings/tenant.max_owned_per_user"),
                sysAdmin, "{\"value\":12}")).andReturn();
        assertEquals(200, put.getResponse().getStatus(), raw(put));
        assertEquals(mask(golden("adm-settings-put.json")), mask(raw(put)));

        MvcResult persisted = mockMvc.perform(get("/api/v1/system/admin/settings/tenant.max_owned_per_user")
                .header("Authorization", sysAdmin)).andReturn();
        assertEquals(200, persisted.getResponse().getStatus(), raw(persisted));
        assertEquals(mask(golden("adm-settings-get-persisted.json")), mask(raw(persisted)));

        MvcResult listAfter = mockMvc.perform(get("/api/v1/system/admin/settings")
                .header("Authorization", sysAdmin)).andReturn();
        assertEquals(200, listAfter.getResponse().getStatus(), raw(listAfter));
        assertEquals(mask(golden("adm-settings-list-after.json")), mask(raw(listAfter)));

        MvcResult del = mockMvc.perform(delete("/api/v1/system/admin/settings/tenant.max_owned_per_user")
                .header("Authorization", sysAdmin)).andReturn();
        assertEquals(204, del.getResponse().getStatus(), raw(del));
        assertEquals("", raw(del)); // 204 无响应体

        MvcResult delUnknown = mockMvc.perform(delete("/api/v1/system/admin/settings/nope.key")
                .header("Authorization", sysAdmin)).andReturn();
        assertEquals(400, delUnknown.getResponse().getStatus(), raw(delUnknown));
        assertEquals(golden("adm-settings-delete-unknown.json"), raw(delUnknown));
    }

    // ════════════════ /system/admin 组：runtime 队列（Lite） ════════════════

    @Test
    void runtimeQueuesErrorsMatchGo() throws Exception {
        MvcResult unknown = mockMvc.perform(get("/api/v1/system/admin/runtime/queues/nope/tasks?state=pending")
                .header("Authorization", sysAdmin)).andReturn();
        assertEquals(400, unknown.getResponse().getStatus(), raw(unknown));
        assertEquals(golden("adm-queues-unknown-list.json"), raw(unknown));

        MvcResult badState = mockMvc.perform(get("/api/v1/system/admin/runtime/queues/default/tasks?state=bogus")
                .header("Authorization", sysAdmin)).andReturn();
        assertEquals(400, badState.getResponse().getStatus(), raw(badState));
        assertEquals(golden("adm-queues-badstate.json"), raw(badState));

        MvcResult mutateUnknown = mockMvc.perform(
                post("/api/v1/system/admin/runtime/queues/nope/tasks/t1/actions/cancel")
                        .header("Authorization", sysAdmin)).andReturn();
        assertEquals(400, mutateUnknown.getResponse().getStatus(), raw(mutateUnknown));
        assertEquals(golden("adm-queues-mutate-unknown.json"), raw(mutateUnknown));

        MvcResult purgeUnknown = mockMvc.perform(delete("/api/v1/system/admin/runtime/queues/nope/archived")
                .header("Authorization", sysAdmin)).andReturn();
        assertEquals(400, purgeUnknown.getResponse().getStatus(), raw(purgeUnknown));
        assertEquals(golden("adm-queues-purge-unknown.json"), raw(purgeUnknown));
    }

    /** Lite 形态（Go noopTaskInspector 源码；dev Go 跑 asynq 模式录不到）：确定性内联断言。 */
    @Test
    void runtimeQueuesLiteShapes() throws Exception {
        MvcResult queues = mockMvc.perform(get("/api/v1/system/admin/runtime/queues")
                .header("Authorization", sysAdmin)).andReturn();
        assertEquals(200, queues.getResponse().getStatus(), raw(queues));
        String body = com.ragagent.support.ContractJson.semantic(
                QUEUE_TS.matcher(raw(queues)).replaceAll("\"timestamp\":\"<ts>\""));
        assertEquals(com.ragagent.support.ContractJson.semantic("{\"available\":false,\"upstreamConcurrency\":32,\"parseConcurrency\":32,"
                + "\"wikiConcurrency\":8,\"pools\":["
                + "{\"name\":\"core\",\"concurrency\":8,\"queueCount\":2,\"instances\":0,"
                + "\"clusterCapacity\":0,\"active\":0,\"utilization\":0},"
                + "{\"name\":\"postprocess\",\"concurrency\":2,\"queueCount\":1,\"instances\":0,"
                + "\"clusterCapacity\":0,\"active\":0,\"utilization\":0},"
                + "{\"name\":\"enrichment\",\"concurrency\":12,\"queueCount\":5,\"instances\":0,"
                + "\"clusterCapacity\":0,\"active\":0,\"utilization\":0},"
                + "{\"name\":\"maintenance\",\"concurrency\":4,\"queueCount\":2,\"instances\":0,"
                + "\"clusterCapacity\":0,\"active\":0,\"utilization\":0},"
                + "{\"name\":\"shared\",\"concurrency\":6,\"queueCount\":7,\"instances\":0,"
                + "\"clusterCapacity\":0,\"active\":0,\"utilization\":0},"
                + "{\"name\":\"wiki\",\"concurrency\":8,\"queueCount\":1,\"instances\":0,"
                + "\"clusterCapacity\":0,\"active\":0,\"utilization\":0}],"
                + "\"queues\":[],\"modelLimiterAvailable\":true,\"models\":[],\"timestamp\":\"<ts>\"}"), body);

        MvcResult tasks = mockMvc.perform(get("/api/v1/system/admin/runtime/queues/default/tasks?state=pending")
                .header("Authorization", sysAdmin)).andReturn();
        assertEquals(200, tasks.getResponse().getStatus(), raw(tasks));
        assertEquals(com.ragagent.support.ContractJson.semantic(
                "{\"available\":false,\"tasks\":[],\"pageSize\":20,\"hasMore\":false,\"nextCursor\":null}"), raw(tasks));

        MvcResult mutate = mockMvc.perform(
                post("/api/v1/system/admin/runtime/queues/default/tasks/t1/actions/cancel")
                        .header("Authorization", sysAdmin)).andReturn();
        assertEquals(503, mutate.getResponse().getStatus(), raw(mutate));
        assertEquals("{\"error\":{\"code\":1008,\"details\":null,\"message\":\"Task queue is unavailable\"},\"success\":false}", raw(mutate));

        MvcResult purge = mockMvc.perform(delete("/api/v1/system/admin/runtime/queues/default/archived")
                .header("Authorization", sysAdmin)).andReturn();
        assertEquals(503, purge.getResponse().getStatus(), raw(purge));
        assertEquals("{\"error\":{\"code\":1008,\"details\":null,\"message\":\"Task queue is unavailable\"},\"success\":false}", raw(purge));
    }

    // ════════════════ /system/admin 组：配额批量应用 ════════════════

    @Test
    void applyDefaultStorageQuotaMatchesGo() throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/system/admin/tenants/apply-default-storage-quota")
                .header("Authorization", sysAdmin)).andReturn();
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        // affected 是部署态（dev 2-4 个租户 vs H2 一个）→ 掩码
        assertEquals(AFFECTED.matcher(golden("adm-quota-apply.json")).replaceAll("\"affected\":<n>"),
                AFFECTED.matcher(raw(r)).replaceAll("\"affected\":<n>"));
        // 落库效果：quota 回到默认 10GiB
        Long quota = jdbc.queryForObject("SELECT storage_quota FROM tenants WHERE id = 10002", Long.class);
        assertEquals(10737418240L, quota);
    }

    // ════════════════ 辅助 ════════════════

    private String login(String email) throws Exception {
        MvcResult r = mockMvc.perform(jsonBody(post("/api/v1/auth/login"), null,
                "{\"email\":\"" + email + "\",\"password\":\"Passw0rd!\"}")).andReturn();
        Matcher m = TOKEN.matcher(raw(r));
        assertThat(m.find()).as("login 响应应含 token: " + raw(r)).isTrue();
        return m.group(1);
    }

    /** 平台 key 创建（供 capabilities-apikey 用例；删除由调用方负责）。 */
    private String createPlatformKeyRaw() throws Exception {
        MvcResult r = mockMvc.perform(jsonBody(post("/api/v1/system/admin/api-keys"), sysAdmin,
                "{\"name\":\"sys-contract-key\",\"capabilities\":[\"chat\"]}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        Matcher m = Pattern.compile("\"token\":\"(sk-[^\"]+)\"").matcher(raw(r));
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private void deletePlatformKey(String token) {
        jdbc.update("DELETE FROM tenant_api_keys WHERE name = ?", "sys-contract-key");
    }

    private static MockHttpServletRequestBuilder jsonBody(MockHttpServletRequestBuilder builder,
                                                          String bearer, String body) {
        if (bearer != null) {
            builder.header("Authorization", bearer);
        }
        return builder.contentType("application/json").content(body);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper RAW_SEMANTIC_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static String raw(MvcResult r) throws Exception {
        // PR4 语义比较：与 golden 同侧归一（非 JSON 文本原样）
        return com.ragagent.support.ContractJson.semantic(RAW_SEMANTIC_MAPPER,
                r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 创建用户的新空间 id 是部署态（dev 序列 vs H2 身份列）→ 掩码 */
    private static String maskTenant(String s) {
        return s.replaceAll("\"tenantId\":\\d+", "\"tenantId\":<tid>");
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

    /** info 专属：db_version（部署态：Java/H2 无迁移历史 → 键省略）整体剔除；started_at/uptime 掩码。 */
    private static String maskInfo(String s) {
        String out = s.replaceAll("\"dbVersion\":\"[^\"]*\",?", "");
        out = out.replaceAll("\"startedAt\":\"[^\"]*\"", "\"startedAt\":\"<ts>\"");
        out = out.replaceAll("\"uptimeSeconds\":\\d+", "\"uptimeSeconds\":0");
        return out;
    }

    /** 两侧同掩码：UUID / 时间戳 / key id / key 明文 token / 生成密码。 */
    private static String mask(String s) {
        String out = API_KEY_TOKEN.matcher(s).replaceAll("\"token\":\"<keytoken>\"");
        out = API_KEY_FIELD.matcher(out).replaceAll("\"api_key\":\"<masked>\"");
        out = Pattern.compile("\"generatedPassword\":\"[^\"]*\"")
                .matcher(out).replaceAll("\"generatedPassword\":\"<genpw>\"");
        out = UUID_VALUE.matcher(out).replaceAll("\"$1\":\"<uuid>\"");
        out = TS_VALUE.matcher(out).replaceAll("\"$1\":\"<ts>\"");
        // Go 零值时间（虚拟设置行）：两侧字节一致，不需掩码——但要压成同一形态防时区漂移
        out = out.replace("0001-01-01T00:00:00Z", "0001-01-01T00:00:00Z");
        out = KEY_ID.matcher(out).replaceAll("\"id\":\"<keyid>\"");
        return out;
    }
}
