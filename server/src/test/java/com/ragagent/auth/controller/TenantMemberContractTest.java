package com.ragagent.auth.controller;

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
 * 空间成员 / 邀请 / API-Principal 契约测试（波 2 第六批，17 条路由）。
 * golden：record-members-golden.sh（90+ 条 mb-*，全部 Go 实录）。
 *
 * <p>种子严格复刻录制脚本：租户 10002 基线成员行（owner/viewer/contributor，
 * joined_at 显式固定 → 列表顺序稳定）、三个一次性用户（601/602/603）的用户名/邮箱
 * 逐字一致（成员列表的 email/username 是响应字段，不是掩码）。</p>
 *
 * <p>录制顺序影响状态：涉及状态的用例在单个 @Test 内按录制顺序串完线性路径
 * （@BeforeEach 重播种）。掩码：UUID / 时间戳 / 邀请 id / JWT / invite_url /
 * expires_at_unix。</p>
 *
 * <p><b>已知不录</b>：API-Key 直加成功（201）的 invited_by 依赖 Go 的
 * GetUserByTenantID（caller=租户第一个用户）；Java 侧未接线（合成用户兜底）——
 * 见 APIKeyRoutePolicies 注释与 §9 API Key 回补差异 #2。403 拒授 Owner 的用例
 * 与 caller 身份无关，正常覆盖。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class TenantMemberContractTest {

    private static final String BCRYPT =
            "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK";
    private static final long TENANT = 10002L;
    private static final String OWNER = "11111111-2222-3333-4444-555555555501";
    private static final String VIEWER = "11111111-2222-3333-4444-555555555504";
    private static final String CONTRIBUTOR = "11111111-2222-3333-4444-555555555505";
    private static final String INVITEE_A = "11111111-2222-3333-4444-555555555601";
    private static final String INVITEE_B = "11111111-2222-3333-4444-555555555602";
    private static final String NEW_MEMBER = "11111111-2222-3333-4444-555555555603";
    private static final String UNKNOWN = "11111111-2222-3333-4444-999999999999";

    private static final String OWNER_EMAIL = "java-phase1@weknora.test";
    private static final String VIEWER_EMAIL = "java-phase1-viewer@weknora.test";
    private static final String A_EMAIL = "java-mb-invitee-a@weknora.test";
    private static final String B_EMAIL = "java-mb-invitee-b@weknora.test";
    private static final String NEW_EMAIL = "java-mb-newmember@weknora.test";

    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");

    /** 掩码用（含 expires_at_unix / 裸数字邀请 id / invite_url / JWT） */
    private static final Pattern UUID_VALUE = Pattern.compile(
            "\"([a-zA-Z_]+)\":\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"");
    private static final Pattern TS_VALUE = Pattern.compile(
            "\"([a-zA-Z_]+)\":\"[2-9]\\d{3}-\\d{2}-\\d{2}T[0-9:.+\\-Z]+\"");
    private static final Pattern INV_ID = Pattern.compile(
            "\"id\":(\\d+)");
    private static final Pattern JWT = Pattern.compile(
            "\"token\":\"(eyJ[^\"]+)\"");
    private static final Pattern INVITE_URL = Pattern.compile(
            "\"inviteUrl\":\"[^\"]*\"");
    private static final Pattern UNIX_TS = Pattern.compile(
            "\"expiresAtUnix\":\\d+");

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

    private String owner;
    private String viewer;
    private String userA;
    private String userB;

    @BeforeEach
    void seed() throws Exception {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);

        Tenant tenant = new Tenant();
        tenant.setId(TENANT);
        tenant.setName("phase1-test-tenant");
        tenant.setStatus("active");
        tenantMapper.insert(tenant);

        seedUser(OWNER, "phase1test", OWNER_EMAIL);
        seedUser(VIEWER, "phase1viewer", VIEWER_EMAIL);
        seedUser(CONTRIBUTOR, "phase1contrib", "java-phase1-contrib@weknora.test");
        seedUser(INVITEE_A, "mbinviteea", A_EMAIL);
        seedUser(INVITEE_B, "mbinviteeb", B_EMAIL);
        seedUser(NEW_MEMBER, "mbnewmember", NEW_EMAIL);

        // 基线成员行：joined_at 显式固定（列表按 joined_at, id 排序，避免并列不稳定）
        seedMember(OWNER, "owner", "2026-09-01T10:00:00Z");
        seedMember(VIEWER, "viewer", "2026-09-01T10:01:00Z");
        seedMember(CONTRIBUTOR, "contributor", "2026-09-01T10:02:00Z");

        owner = "Bearer " + login(OWNER_EMAIL);
        viewer = "Bearer " + login(VIEWER_EMAIL);
        userA = "Bearer " + login(A_EMAIL);
        userB = "Bearer " + login(B_EMAIL);
    }

    private void seedUser(String id, String username, String email) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(BCRYPT);
        user.setTenantId(TENANT);
        user.setIsActive(true);
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

    // ════════════════ 1) 成员列表与分页（基线只读） ════════════════

    @Test
    void listMatchesGo() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/tenants/10002/members")
                .header("Authorization", owner)).andReturn();
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("mb-member-list.json")), mask(raw(r)));
    }

    /** Viewer 可读名册（Go g.Viewer()）。 */
    @Test
    void listViewerAllowed() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/tenants/10002/members")
                .header("Authorization", viewer)).andReturn();
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("mb-member-list-viewer.json")), mask(raw(r)));
    }

    /** q 无命中 → members:[] total:0（q 过 LIKE 转义后拼 %）。 */
    @Test
    void listQFiltersMatchGo() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/tenants/10002/members?q=mbviewer")
                .header("Authorization", owner)).andReturn();
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("mb-member-list-q.json"), raw(r));

        // 大写邮箱子串：LOWER(email) LIKE LOWER(?)——devPG 邮箱里没有 java-mb-contrib → 空
        MvcResult m = mockMvc.perform(get("/api/v1/tenants/10002/members?q=JAVA-MB-CONTRIB")
                .header("Authorization", owner)).andReturn();
        assertEquals(200, m.getResponse().getStatus(), raw(m));
        assertEquals(golden("mb-member-list-qemail.json"), raw(m));

        // 命中 contributor（email 子串）
        MvcResult h = mockMvc.perform(get("/api/v1/tenants/10002/members?q=phase1contrib")
                .header("Authorization", owner)).andReturn();
        assertEquals(200, h.getResponse().getStatus(), raw(h));
        assertEquals(mask(golden("mb-member-list-qhit.json")), mask(raw(h)));

        // 大小写不敏感命中 viewer
        MvcResult c = mockMvc.perform(get("/api/v1/tenants/10002/members?q=PHASE1VIEWER")
                .header("Authorization", owner)).andReturn();
        assertEquals(200, c.getResponse().getStatus(), raw(c));
        assertEquals(mask(golden("mb-member-list-qcase.json")), mask(raw(c)));

        MvcResult z = mockMvc.perform(get("/api/v1/tenants/10002/members?q=zzz-nomatch")
                .header("Authorization", owner)).andReturn();
        assertEquals(200, z.getResponse().getStatus(), raw(z));
        assertEquals(golden("mb-member-list-qnone.json"), raw(z));
    }

    /** page=2 & size=2 → 第 3 行（贡献者）；排序 joined_at ASC, id ASC。 */
    @Test
    void listPage2MatchesGo() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/tenants/10002/members?page=2&page_size=2")
                .header("Authorization", owner)).andReturn();
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("mb-member-list-page2.json")), mask(raw(r)));
    }

    /** 分页参数"给了就必须合法"：非数字 page / 越界 size 都是 400（1010）。 */
    @Test
    void listPaginationErrorsMatchGo() throws Exception {
        MvcResult p = mockMvc.perform(get("/api/v1/tenants/10002/members?page=abc")
                .header("Authorization", owner)).andReturn();
        assertEquals(400, p.getResponse().getStatus(), raw(p));
        assertEquals(golden("mb-member-list-badpage.json"), raw(p));

        MvcResult s = mockMvc.perform(get("/api/v1/tenants/10002/members?page_size=200")
                .header("Authorization", owner)).andReturn();
        assertEquals(400, s.getResponse().getStatus(), raw(s));
        assertEquals(golden("mb-member-list-badsize.json"), raw(s));
    }

    /** 跨租户 403（PathTenantMatch 中间件）与非法 id 400——拒绝都发生在 handler 之前。 */
    @Test
    void listCrossTenantAndBadIdMatchGo() throws Exception {
        MvcResult c = mockMvc.perform(get("/api/v1/tenants/10000/members")
                .header("Authorization", owner)).andReturn();
        assertEquals(403, c.getResponse().getStatus(), raw(c));
        assertEquals(golden("mb-member-list-cross.json"), raw(c));

        MvcResult b = mockMvc.perform(get("/api/v1/tenants/abc/members")
                .header("Authorization", owner)).andReturn();
        assertEquals(400, b.getResponse().getStatus(), raw(b));
        assertEquals(golden("mb-member-list-badid.json"), raw(b));
    }

    /** Viewer 直加 → RBAC 纯字符串 403（g.Owner() 路由守卫）。 */
    @Test
    void viewerAddIsForbidden() throws Exception {
        MvcResult r = mockMvc.perform(jsonBody(post("/api/v1/tenants/10002/members"), viewer,
                "{\"email\":\"" + NEW_EMAIL + "\",\"role\":\"viewer\"}")).andReturn();
        assertEquals(403, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("mb-member-viewer-add.json"), raw(r));
    }

    /** 直加的 handler 级校验：badrole / bademail（validator 原文）/ 缺字段 / 未注册 404。 */
    @Test
    void addValidationMatchesGo() throws Exception {
        MvcResult br = mockMvc.perform(jsonBody(post("/api/v1/tenants/10002/members"), owner,
                "{\"email\":\"" + NEW_EMAIL + "\",\"role\":\"bogus\"}")).andReturn();
        assertEquals(400, br.getResponse().getStatus(), raw(br));
        assertEquals(golden("mb-member-add-badrole.json"), raw(br));

        MvcResult be = mockMvc.perform(jsonBody(post("/api/v1/tenants/10002/members"), owner,
                "{\"email\":\"notanemail\",\"role\":\"viewer\"}")).andReturn();
        assertEquals(400, be.getResponse().getStatus(), raw(be));
        assertEquals(golden("mb-member-add-bademail.json"), raw(be));

        MvcResult mi = mockMvc.perform(jsonBody(post("/api/v1/tenants/10002/members"), owner, "{}"))
                .andReturn();
        assertEquals(400, mi.getResponse().getStatus(), raw(mi));
        assertEquals(golden("mb-member-add-missing.json"), raw(mi));

        MvcResult un = mockMvc.perform(jsonBody(post("/api/v1/tenants/10002/members"), owner,
                "{\"email\":\"nobody@weknora.test\",\"role\":\"viewer\"}")).andReturn();
        assertEquals(404, un.getResponse().getStatus(), raw(un));
        assertEquals(golden("mb-member-add-unregistered.json"), raw(un));
    }

    /** 最后一位 Owner：直改（PUT 降级）与直删都在第二个 Owner 出现前录 → 409。 */
    @Test
    void lastOwnerGuardsMatchGo() throws Exception {
        MvcResult d = mockMvc.perform(jsonBody(
                put("/api/v1/tenants/10002/members/" + OWNER), owner, "{\"role\":\"viewer\"}"))
                .andReturn();
        assertEquals(409, d.getResponse().getStatus(), raw(d));
        assertEquals(golden("mb-owner-demote-last.json"), raw(d));

        MvcResult r = mockMvc.perform(delete("/api/v1/tenants/10002/members/" + OWNER)
                .header("Authorization", owner)).andReturn();
        assertEquals(409, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("mb-owner-remove-last.json"), raw(r));
    }

    /** 角色更新线性路径：成功 → 同角色 no-op → badrole → 未知 404 → 缺 role 400。 */
    @Test
    void updateRoleSequenceMatchesGo() throws Exception {
        String path = "/api/v1/tenants/10002/members/" + CONTRIBUTOR;

        MvcResult u = mockMvc.perform(jsonBody(put(path), owner, "{\"role\":\"admin\"}")).andReturn();
        assertEquals(204, u.getResponse().getStatus(), raw(u));
        assertEquals(golden("mb-member-update.json"), raw(u));

        // 同角色 no-op：仍是 200 {"success":true}（不审计）
        MvcResult s = mockMvc.perform(jsonBody(put(path), owner, "{\"role\":\"admin\"}")).andReturn();
        assertEquals(204, s.getResponse().getStatus(), raw(s));
        assertEquals(golden("mb-member-update-same.json"), raw(s));

        MvcResult b = mockMvc.perform(jsonBody(put(path), owner, "{\"role\":\"bogus\"}")).andReturn();
        assertEquals(400, b.getResponse().getStatus(), raw(b));
        assertEquals(golden("mb-member-update-badrole.json"), raw(b));

        MvcResult n = mockMvc.perform(jsonBody(
                put("/api/v1/tenants/10002/members/" + UNKNOWN), owner, "{\"role\":\"viewer\"}"))
                .andReturn();
        assertEquals(404, n.getResponse().getStatus(), raw(n));
        assertEquals(golden("mb-member-update-404.json"), raw(n));

        MvcResult m = mockMvc.perform(jsonBody(
                put("/api/v1/tenants/10002/members/" + UNKNOWN), owner, "{}")).andReturn();
        assertEquals(400, m.getResponse().getStatus(), raw(m));
        assertEquals(golden("mb-member-update-nobody.json"), raw(m));
    }

    // ════════════════ 2) 直加成功 / 冲突 / 移除（线性） ════════════════

    @Test
    void addConflictRemoveSequenceMatchesGo() throws Exception {
        MvcResult a = mockMvc.perform(jsonBody(post("/api/v1/tenants/10002/members"), owner,
                "{\"email\":\"" + NEW_EMAIL + "\",\"role\":\"contributor\"}")).andReturn();
        assertEquals(201, a.getResponse().getStatus(), raw(a));
        assertEquals(mask(golden("mb-member-add.json")), mask(raw(a)));

        MvcResult d = mockMvc.perform(jsonBody(post("/api/v1/tenants/10002/members"), owner,
                "{\"email\":\"" + NEW_EMAIL + "\",\"role\":\"viewer\"}")).andReturn();
        assertEquals(409, d.getResponse().getStatus(), raw(d));
        assertEquals(golden("mb-member-add-dup.json"), raw(d));

        MvcResult r = mockMvc.perform(delete("/api/v1/tenants/10002/members/" + NEW_MEMBER)
                .header("Authorization", owner)).andReturn();
        assertEquals(204, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("mb-member-remove.json"), raw(r));

        MvcResult r2 = mockMvc.perform(delete("/api/v1/tenants/10002/members/" + NEW_MEMBER)
                .header("Authorization", owner)).andReturn();
        assertEquals(404, r2.getResponse().getStatus(), raw(r2));
        assertEquals(golden("mb-member-remove-again.json"), raw(r2));
    }

    // ════════════════ 3) 第二个 Owner 与 leave（线性） ════════════════

    /**
     * leave 三态：非成员且 tenantless → TENANT_REQUIRED 409（路由不是 tenant-optional）；
     * 第二 Owner 自愿退出 → 200（成员行软删 + token 吊销）；最后 Owner 留下 → 409。
     */
    @Test
    void leaveSequenceMatchesGo() throws Exception {
        MvcResult a = mockMvc.perform(jsonBody(post("/api/v1/tenants/10002/members"), owner,
                "{\"email\":\"" + A_EMAIL + "\",\"role\":\"owner\"}")).andReturn();
        assertEquals(201, a.getResponse().getStatus(), raw(a));
        assertEquals(mask(golden("mb-member-add-owner.json")), mask(raw(a)));

        // B 的 token 是 tenantless 的（登录时无成员关系）→ PathTenantMatch 前的 TENANT_REQUIRED
        MvcResult nb = mockMvc.perform(post("/api/v1/tenants/10002/leave")
                .header("Authorization", userB)).andReturn();
        assertEquals(409, nb.getResponse().getStatus(), raw(nb));
        assertEquals(golden("mb-leave-nonmember.json"), raw(nb));

        // A（tenantless token + 刚建立的成员关系）→ resolveFirstMembershipTarget 兜底 → 200
        MvcResult l = mockMvc.perform(post("/api/v1/tenants/10002/leave")
                .header("Authorization", userA)).andReturn();
        assertEquals(204, l.getResponse().getStatus(), raw(l));
        assertEquals(golden("mb-leave.json"), raw(l));

        // leave 成功后 A 的 token 已被清理吊销：后续请求 401 invalid or expired token
        MvcResult ra = mockMvc.perform(get("/api/v1/tenants/10002/members")
                .header("Authorization", userA)).andReturn();
        assertEquals(401, ra.getResponse().getStatus(), raw(ra));
        assertThat(raw(ra)).contains("Unauthorized: invalid or expired token");

        MvcResult lo = mockMvc.perform(post("/api/v1/tenants/10002/leave")
                .header("Authorization", owner)).andReturn();
        assertEquals(409, lo.getResponse().getStatus(), raw(lo));
        assertEquals(golden("mb-leave-last-owner.json"), raw(lo));
    }

    /**
     * 成员行"绕开 service"消失后：有租户上下文的旧 token 在 auth 中间件就被
     * 403（fail-closed）——handler 的 404 "you are not a member" 分支不可达。
     */
    @Test
    void leaveAfterOutOfBandRemovalIsForbiddenByAuth() throws Exception {
        MvcResult a = mockMvc.perform(jsonBody(post("/api/v1/tenants/10002/members"), owner,
                "{\"email\":\"" + B_EMAIL + "\",\"role\":\"contributor\"}")).andReturn();
        assertEquals(201, a.getResponse().getStatus(), raw(a));
        assertEquals(mask(golden("mb-member-add-b.json")), mask(raw(a)));

        // B 重登：现在有成员关系 → token 带租户上下文（contributor）
        String bScoped = "Bearer " + login(B_EMAIL);

        // SQL 直删成员行（不走 service：service 路径会顺带吊销 token → 401 挡在 404 前）
        jdbc.update("DELETE FROM tenant_members WHERE user_id = ? AND tenant_id = ?", INVITEE_B, TENANT);

        MvcResult r = mockMvc.perform(post("/api/v1/tenants/10002/leave")
                .header("Authorization", bScoped)).andReturn();
        assertEquals(403, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("mb-leave-not-member.json"), raw(r));
    }

    /** API-Key 授 Owner → 403（manage_members 的 service 层边界；与 caller 身份无关）。 */
    @Test
    void apiKeyCannotAssignOwnerMatchesGo() throws Exception {
        String key = createFullAccessKey();
        try {
            MvcResult r = mockMvc.perform(jsonBody(post("/api/v1/tenants/10002/members"), null,
                            "{\"email\":\"" + NEW_EMAIL + "\",\"role\":\"owner\"}")
                    .header("X-API-Key", key)).andReturn();
            assertEquals(403, r.getResponse().getStatus(), raw(r));
            assertEquals(golden("mb-member-apikey-owner.json"), raw(r));
        } finally {
            deleteFullAccessKey();
        }
    }

    private String createdKeyId;

    private String createFullAccessKey() throws Exception {
        MvcResult r = mockMvc.perform(jsonBody(post("/api/v1/tenants/10002/api-keys"), owner,
                "{\"name\":\"mb-golden-key\",\"fullAccess\":true}")).andReturn();
        assertEquals(201, r.getResponse().getStatus(), raw(r));
        Matcher m = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(raw(r));
        assertThat(m.find()).as("key create 响应应含 token: " + raw(r)).isTrue();
        String token = m.group(1);
        Matcher id = Pattern.compile("\"id\":(\\d+)").matcher(raw(r));
        assertThat(id.find()).isTrue();
        createdKeyId = id.group(1);
        return token;
    }

    private void deleteFullAccessKey() {
        if (createdKeyId != null) {
            try {
                mockMvc.perform(delete("/api/v1/tenants/10002/api-keys/" + createdKeyId)
                        .header("Authorization", owner));
            } catch (Exception ignored) {
                // 收尾尽力而为
            }
        }
    }

    // ════════════════ 4) 邀请（租户侧） ════════════════

    @Test
    void invitationCreateAndConflictsMatchGo() throws Exception {
        MvcResult e = mockMvc.perform(get("/api/v1/tenants/10002/invitations")
                .header("Authorization", owner)).andReturn();
        assertEquals(200, e.getResponse().getStatus(), raw(e));
        assertEquals(golden("mb-inv-list-empty.json"), raw(e));

        // 复刻录制态：B 先经 API 直加成员 → 登录（JWT 带租户 10002）→ 成员行被 SQL 删掉
        // —— 这种 token 访问 /me/invitations 时 auth 中间件 403（有租户上下文但无成员关系）；
        // 而"从未是成员"的 B 登录解析为 tenantless，/me 是 tenant-optional 会 200 空列表。
        jdbc.update("INSERT INTO tenant_members (user_id, tenant_id, role, status) "
                + "VALUES (?, 10002, 'contributor', 'active')", INVITEE_B);
        String memberedB = "Bearer " + login(B_EMAIL);
        jdbc.update("DELETE FROM tenant_members WHERE user_id = ? AND tenant_id = 10002", INVITEE_B);

        MvcResult me = mockMvc.perform(get("/api/v1/me/invitations")
                .header("Authorization", memberedB)).andReturn();
        assertEquals(403, me.getResponse().getStatus(), raw(me));
        assertEquals(golden("mb-my-list-empty.json"), raw(me));

        MvcResult c = mockMvc.perform(jsonBody(post("/api/v1/tenants/10002/invitations"), owner,
                "{\"email\":\"" + B_EMAIL + "\",\"role\":\"viewer\",\"message\":\"welcome to mb golden\"}"))
                .andReturn();
        assertEquals(201, c.getResponse().getStatus(), raw(c));
        assertEquals(mask(golden("mb-inv-create.json")), mask(raw(c)));

        MvcResult d = mockMvc.perform(jsonBody(post("/api/v1/tenants/10002/invitations"), owner,
                "{\"email\":\"" + B_EMAIL + "\",\"role\":\"viewer\"}")).andReturn();
        assertEquals(409, d.getResponse().getStatus(), raw(d));
        assertEquals(golden("mb-inv-create-dup.json"), raw(d));

        MvcResult am = mockMvc.perform(jsonBody(post("/api/v1/tenants/10002/invitations"), owner,
                "{\"email\":\"java-phase1-contrib@weknora.test\",\"role\":\"viewer\"}")).andReturn();
        assertEquals(409, am.getResponse().getStatus(), raw(am));
        assertEquals(golden("mb-inv-create-already-member.json"), raw(am));

        MvcResult un = mockMvc.perform(jsonBody(post("/api/v1/tenants/10002/invitations"), owner,
                "{\"email\":\"nobody@weknora.test\",\"role\":\"viewer\"}")).andReturn();
        assertEquals(404, un.getResponse().getStatus(), raw(un));
        assertEquals(golden("mb-inv-create-unregistered.json"), raw(un));

        MvcResult br = mockMvc.perform(jsonBody(post("/api/v1/tenants/10002/invitations"), owner,
                "{\"email\":\"" + B_EMAIL + "\",\"role\":\"bogus\"}")).andReturn();
        assertEquals(400, br.getResponse().getStatus(), raw(br));
        assertEquals(golden("mb-inv-create-badrole.json"), raw(br));

        MvcResult l = mockMvc.perform(get("/api/v1/tenants/10002/invitations")
                .header("Authorization", owner)).andReturn();
        assertEquals(200, l.getResponse().getStatus(), raw(l));
        assertEquals(mask(golden("mb-inv-list.json")), mask(raw(l)));

        MvcResult lv = mockMvc.perform(get("/api/v1/tenants/10002/invitations")
                .header("Authorization", viewer)).andReturn();
        assertEquals(200, lv.getResponse().getStatus(), raw(lv));
        assertEquals(mask(golden("mb-inv-list-viewer.json")), mask(raw(lv)));
    }

    /** 撤销：404 → 200 → 409 no-longer-pending → include_terminal 可见 revoked 行。 */
    @Test
    void revokeSequenceMatchesGo() throws Exception {
        MvcResult c = mockMvc.perform(jsonBody(post("/api/v1/tenants/10002/invitations"), owner,
                "{\"email\":\"" + B_EMAIL + "\",\"role\":\"viewer\",\"message\":\"welcome to mb golden\"}"))
                .andReturn();
        assertEquals(201, c.getResponse().getStatus(), raw(c));
        String invId = extractInvitationId(raw(c));

        MvcResult nf = mockMvc.perform(delete("/api/v1/tenants/10002/invitations/999999")
                .header("Authorization", owner)).andReturn();
        assertEquals(404, nf.getResponse().getStatus(), raw(nf));
        assertEquals(golden("mb-inv-revoke-404.json"), raw(nf));

        MvcResult r = mockMvc.perform(delete("/api/v1/tenants/10002/invitations/" + invId)
                .header("Authorization", owner)).andReturn();
        assertEquals(204, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("mb-inv-revoke.json"), raw(r));

        MvcResult r2 = mockMvc.perform(delete("/api/v1/tenants/10002/invitations/" + invId)
                .header("Authorization", owner)).andReturn();
        assertEquals(409, r2.getResponse().getStatus(), raw(r2));
        assertEquals(golden("mb-inv-revoke-again.json"), raw(r2));

        MvcResult t = mockMvc.perform(get("/api/v1/tenants/10002/invitations?include_terminal=true")
                .header("Authorization", owner)).andReturn();
        assertEquals(200, t.getResponse().getStatus(), raw(t));
        assertEquals(mask(golden("mb-inv-list-terminal.json")), mask(raw(t)));
    }

    // ════════════════ 5) 收件箱（/me/invitations*） ════════════════

    /** 计数 → 列表 → 接受 → 再接受 409（B 全程 tenantless token，/me 无角色门）。 */
    @Test
    void inboxAcceptSequenceMatchesGo() throws Exception {
        MvcResult c2 = mockMvc.perform(jsonBody(post("/api/v1/tenants/10002/invitations"), owner,
                "{\"email\":\"" + B_EMAIL + "\",\"role\":\"contributor\"}")).andReturn();
        assertEquals(201, c2.getResponse().getStatus(), raw(c2));
        assertEquals(mask(golden("mb-inv-create2.json")), mask(raw(c2)));
        String invId = extractInvitationId(raw(c2));

        // 非被邀请人（A）接受 B 的邀请 → 403 only-invitee；未知 id → 404
        MvcResult w = mockMvc.perform(post("/api/v1/me/invitations/" + invId + "/accept")
                .header("Authorization", userA)).andReturn();
        assertEquals(403, w.getResponse().getStatus(), raw(w));
        assertEquals(golden("mb-my-accept-wrong-user.json"), raw(w));

        MvcResult nf = mockMvc.perform(post("/api/v1/me/invitations/999999/accept")
                .header("Authorization", userA)).andReturn();
        assertEquals(404, nf.getResponse().getStatus(), raw(nf));
        assertEquals(golden("mb-my-accept-404.json"), raw(nf));

        MvcResult pc = mockMvc.perform(get("/api/v1/me/invitations/pending-count")
                .header("Authorization", userB)).andReturn();
        assertEquals(200, pc.getResponse().getStatus(), raw(pc));
        assertEquals(golden("mb-my-pending-count.json"), raw(pc));

        MvcResult li = mockMvc.perform(get("/api/v1/me/invitations")
                .header("Authorization", userB)).andReturn();
        assertEquals(200, li.getResponse().getStatus(), raw(li));
        assertEquals(mask(golden("mb-my-list.json")), mask(raw(li)));

        MvcResult ac = mockMvc.perform(post("/api/v1/me/invitations/" + invId + "/accept")
                .header("Authorization", userB)).andReturn();
        assertEquals(200, ac.getResponse().getStatus(), raw(ac));
        assertEquals(mask(golden("mb-my-accept.json")), mask(raw(ac)));

        MvcResult aa = mockMvc.perform(post("/api/v1/me/invitations/" + invId + "/accept")
                .header("Authorization", userB)).andReturn();
        assertEquals(409, aa.getResponse().getStatus(), raw(aa));
        assertEquals(golden("mb-my-accept-again.json"), raw(aa));

        MvcResult la = mockMvc.perform(get("/api/v1/me/invitations")
                .header("Authorization", userB)).andReturn();
        assertEquals(200, la.getResponse().getStatus(), raw(la));
        assertEquals(golden("mb-my-list-after.json"), raw(la));
    }

    /** 拒绝主线（A 为收件人）：404 → 200 → 终态后再接受 409。 */
    @Test
    void declineSequenceMatchesGo() throws Exception {
        MvcResult c3 = mockMvc.perform(jsonBody(post("/api/v1/tenants/10002/invitations"), owner,
                "{\"email\":\"" + A_EMAIL + "\",\"role\":\"admin\"}")).andReturn();
        assertEquals(201, c3.getResponse().getStatus(), raw(c3));
        assertEquals(mask(golden("mb-inv-create3.json")), mask(raw(c3)));
        String invId = extractInvitationId(raw(c3));

        MvcResult nf = mockMvc.perform(post("/api/v1/me/invitations/999999/decline")
                .header("Authorization", userA)).andReturn();
        assertEquals(404, nf.getResponse().getStatus(), raw(nf));
        assertEquals(golden("mb-my-decline-404.json"), raw(nf));

        MvcResult d = mockMvc.perform(post("/api/v1/me/invitations/" + invId + "/decline")
                .header("Authorization", userA)).andReturn();
        assertEquals(204, d.getResponse().getStatus(), raw(d));
        assertEquals(golden("mb-my-decline.json"), raw(d));

        MvcResult a = mockMvc.perform(post("/api/v1/me/invitations/" + invId + "/accept")
                .header("Authorization", userA)).andReturn();
        assertEquals(409, a.getResponse().getStatus(), raw(a));
        assertEquals(golden("mb-my-accept-declined.json"), raw(a));
    }

    // ════════════════ 6) 共享邀请链接 ════════════════

    /** 建链（含 owner 角色 JWT 可建）→ Owner/Viewer 列表的 invite_url 差异 → token 接受幂等。 */
    @Test
    void shareLinkLifecycleMatchesGo() throws Exception {
        MvcResult cl = mockMvc.perform(jsonBody(post("/api/v1/tenants/10002/invite-links"), owner,
                "{\"role\":\"contributor\",\"message\":\"join us\"}")).andReturn();
        assertEquals(201, cl.getResponse().getStatus(), raw(cl));
        assertEquals(mask(golden("mb-link-create.json")), mask(raw(cl)));
        String linkToken = extractLinkToken(raw(cl));

        MvcResult br = mockMvc.perform(jsonBody(post("/api/v1/tenants/10002/invite-links"), owner,
                "{\"role\":\"bogus\"}")).andReturn();
        assertEquals(400, br.getResponse().getStatus(), raw(br));
        assertEquals(golden("mb-link-create-badrole.json"), raw(br));

        MvcResult ol = mockMvc.perform(jsonBody(post("/api/v1/tenants/10002/invite-links"), owner,
                "{\"role\":\"owner\"}")).andReturn();
        assertEquals(201, ol.getResponse().getStatus(), raw(ol));
        assertEquals(mask(golden("mb-link-create-owner.json")), mask(raw(ol)));

        MvcResult l = mockMvc.perform(get("/api/v1/tenants/10002/invitations")
                .header("Authorization", owner)).andReturn();
        assertEquals(200, l.getResponse().getStatus(), raw(l));
        assertEquals(mask(golden("mb-inv-list-withlinks.json")), mask(raw(l)));

        MvcResult lv = mockMvc.perform(get("/api/v1/tenants/10002/invitations")
                .header("Authorization", viewer)).andReturn();
        assertEquals(200, lv.getResponse().getStatus(), raw(lv));
        assertEquals(mask(golden("mb-inv-list-viewer-links.json")), mask(raw(lv)));

        // token 接受：A（tenantless）通过共享链接成为 contributor；重复点击幂等
        MvcResult at = mockMvc.perform(jsonBody(post("/api/v1/me/invitations/accept-by-token"), userA,
                "{\"token\":\"" + linkToken + "\"}")).andReturn();
        assertEquals(200, at.getResponse().getStatus(), raw(at));
        assertEquals(mask(golden("mb-my-accept-by-token.json")), mask(raw(at)));

        MvcResult at2 = mockMvc.perform(jsonBody(post("/api/v1/me/invitations/accept-by-token"), userA,
                "{\"token\":\"" + linkToken + "\"}")).andReturn();
        assertEquals(200, at2.getResponse().getStatus(), raw(at2));
        assertEquals(mask(golden("mb-my-accept-by-token-again.json")), mask(raw(at2)));

        // accepted_count 只在第一次 +1（幂等分支不 bump）
        MvcResult la = mockMvc.perform(get("/api/v1/tenants/10002/invitations")
                .header("Authorization", owner)).andReturn();
        assertEquals(200, la.getResponse().getStatus(), raw(la));
        assertEquals(mask(golden("mb-inv-list-after-accept.json")), mask(raw(la)));

        // 未知 token → 410 Gone（code 1003）
        MvcResult bt = mockMvc.perform(jsonBody(post("/api/v1/me/invitations/accept-by-token"), userA,
                "{\"token\":\"no-such-token\"}")).andReturn();
        assertEquals(410, bt.getResponse().getStatus(), raw(bt));
        assertEquals(golden("mb-my-accept-by-token-bad.json"), raw(bt));

        // 纯空白过 binding（required=非零值）→ trim 后 400（无 details）
        MvcResult bl = mockMvc.perform(jsonBody(post("/api/v1/me/invitations/accept-by-token"), userA,
                "{\"token\":\"   \"}")).andReturn();
        assertEquals(400, bl.getResponse().getStatus(), raw(bl));
        assertEquals(golden("mb-my-accept-by-token-blank.json"), raw(bl));

        // 显式空串 / 缺字段 → binding required 原文进 details
        MvcResult em = mockMvc.perform(jsonBody(post("/api/v1/me/invitations/accept-by-token"), userA,
                "{\"token\":\"\"}")).andReturn();
        assertEquals(400, em.getResponse().getStatus(), raw(em));
        assertEquals(golden("mb-my-accept-by-token-empty.json"), raw(em));

        MvcResult nb = mockMvc.perform(jsonBody(post("/api/v1/me/invitations/accept-by-token"), userA,
                "{}")).andReturn();
        assertEquals(400, nb.getResponse().getStatus(), raw(nb));
        assertEquals(golden("mb-my-accept-by-token-nobody.json"), raw(nb));
    }

    // ════════════════ 7) api-principal（config + test-token） ════════════════

    @Test
    void apcDefaultMatchesGo() throws Exception {
        MvcResult g = mockMvc.perform(get("/api/v1/tenants/10002/api-principal-config")
                .header("Authorization", owner)).andReturn();
        assertEquals(200, g.getResponse().getStatus(), raw(g));
        assertEquals(golden("mb-apc-get.json"), raw(g));

        MvcResult v = mockMvc.perform(get("/api/v1/tenants/10002/api-principal-config")
                .header("Authorization", viewer)).andReturn();
        assertEquals(403, v.getResponse().getStatus(), raw(v));
        assertEquals(golden("mb-apc-get-viewer.json"), raw(v));
    }

    /** PUT 校验：非法 mode / signed_token 缺密钥 / 非法 JSON（details=Go 解析器原文）。 */
    @Test
    void apcPutValidationMatchesGo() throws Exception {
        MvcResult bm = mockMvc.perform(jsonBody(put("/api/v1/tenants/10002/api-principal-config"), owner,
                "{\"mode\":\"bogus\"}")).andReturn();
        assertEquals(400, bm.getResponse().getStatus(), raw(bm));
        assertEquals(golden("mb-apc-put-badmode.json"), raw(bm));

        MvcResult ns = mockMvc.perform(jsonBody(put("/api/v1/tenants/10002/api-principal-config"), owner,
                "{\"mode\":\"signed_token\"}")).andReturn();
        assertEquals(400, ns.getResponse().getStatus(), raw(ns));
        assertEquals(golden("mb-apc-put-signed-nosecret.json"), raw(ns));

        MvcResult bj = mockMvc.perform(jsonBody(put("/api/v1/tenants/10002/api-principal-config"), owner,
                "not-json")).andReturn();
        assertEquals(400, bj.getResponse().getStatus(), raw(bj));
        assertEquals(golden("mb-apc-put-badjson.json"), raw(bj));
    }

    /** *** 占位符=保留存量密钥；显式 null 同义；显式空白=清空 → signed_token 校验 400。 */
    @Test
    void apcPutSecretSemanticsMatchGo() throws Exception {
        String putUrl = "/api/v1/tenants/10002/api-principal-config";

        MvcResult s = mockMvc.perform(jsonBody(put(putUrl), owner,
                "{\"mode\":\"signed_token\",\"hmacSecret\":\"mb-golden-hmac-secret-0123456789abcdef\"}"))
                .andReturn();
        assertEquals(200, s.getResponse().getStatus(), raw(s));
        assertEquals(golden("mb-apc-put-signed.json"), raw(s));

        MvcResult g = mockMvc.perform(get(putUrl).header("Authorization", owner)).andReturn();
        assertEquals(200, g.getResponse().getStatus(), raw(g));
        assertEquals(golden("mb-apc-get-after.json"), raw(g));

        MvcResult p = mockMvc.perform(jsonBody(put(putUrl), owner,
                "{\"mode\":\"signed_token\",\"hmacSecret\":\"***\"}")).andReturn();
        assertEquals(200, p.getResponse().getStatus(), raw(p));
        assertEquals(golden("mb-apc-put-placeholder.json"), raw(p));

        // 显式 null：Go 反序列化成 nil → 未提供 → 保留存量
        MvcResult n = mockMvc.perform(jsonBody(put(putUrl), owner,
                "{\"mode\":\"signed_token\",\"hmacSecret\":null}")).andReturn();
        assertEquals(200, n.getResponse().getStatus(), raw(n));
        assertEquals(golden("mb-apc-put-null-secret.json"), raw(n));

        MvcResult c = mockMvc.perform(jsonBody(put(putUrl), owner,
                "{\"mode\":\"signed_token\",\"hmacSecret\":\"   \"}")).andReturn();
        assertEquals(400, c.getResponse().getStatus(), raw(c));
        assertEquals(golden("mb-apc-put-clear-secret.json"), raw(c));
    }

    /** test-token：HS256 签发 / TTL 边界 / external_user_id 校验（details=AppError 双前缀）。 */
    @Test
    void testTokenSequenceMatchesGo() throws Exception {
        String postUrl = "/api/v1/tenants/10002/api-principal-test-token";
        String putUrl = "/api/v1/tenants/10002/api-principal-config";

        // 先进入 signed_token 模式（密钥见 mb-apc-put-signed）
        mockMvc.perform(jsonBody(put(putUrl), owner,
                "{\"mode\":\"signed_token\",\"hmacSecret\":\"mb-golden-hmac-secret-0123456789abcdef\"}"))
                .andReturn();

        MvcResult t = mockMvc.perform(jsonBody(post(postUrl), owner,
                "{\"externalUserId\":\"ext-user-1\"}")).andReturn();
        assertEquals(200, t.getResponse().getStatus(), raw(t));
        assertEquals(mask(golden("mb-test-token.json")), mask(raw(t)));

        MvcResult mx = mockMvc.perform(jsonBody(post(postUrl), owner,
                "{\"externalUserId\":\"ext-user-2\",\"expiresInSeconds\":3600}")).andReturn();
        assertEquals(200, mx.getResponse().getStatus(), raw(mx));
        assertEquals(mask(golden("mb-test-token-ttl-max.json")), mask(raw(mx)));

        MvcResult df = mockMvc.perform(jsonBody(post(postUrl), owner,
                "{\"externalUserId\":\"ext-user-3\",\"expiresInSeconds\":0}")).andReturn();
        assertEquals(200, df.getResponse().getStatus(), raw(df));
        assertEquals(mask(golden("mb-test-token-ttl-default.json")), mask(raw(df)));

        MvcResult ov = mockMvc.perform(jsonBody(post(postUrl), owner,
                "{\"externalUserId\":\"ext-user-4\",\"expiresInSeconds\":3601}")).andReturn();
        assertEquals(400, ov.getResponse().getStatus(), raw(ov));
        assertEquals(golden("mb-test-token-ttl-over.json"), raw(ov));

        MvcResult em = mockMvc.perform(jsonBody(post(postUrl), owner, "{\"externalUserId\":\"\"}")).andReturn();
        assertEquals(400, em.getResponse().getStatus(), raw(em));
        assertEquals(golden("mb-test-token-empty.json"), raw(em));

        String longId = "a".repeat(129);
        MvcResult ln = mockMvc.perform(jsonBody(post(postUrl), owner,
                "{\"externalUserId\":\"" + longId + "\"}")).andReturn();
        assertEquals(400, ln.getResponse().getStatus(), raw(ln));
        assertEquals(golden("mb-test-token-long.json"), raw(ln));

        MvcResult ct = mockMvc.perform(jsonBody(post(postUrl), owner,
                "{\"externalUserId\":\"bad\\u0007id\"}")).andReturn();
        assertEquals(400, ct.getResponse().getStatus(), raw(ct));
        assertEquals(golden("mb-test-token-ctrl.json"), raw(ct));

        // 切回 tenant 模式（密钥保留）→ 非 signed_token 拒签发
        MvcResult tn = mockMvc.perform(jsonBody(put(putUrl), owner, "{\"mode\":\"tenant\"}")).andReturn();
        assertEquals(200, tn.getResponse().getStatus(), raw(tn));
        assertEquals(golden("mb-apc-put-tenant.json"), raw(tn));

        MvcResult ns = mockMvc.perform(jsonBody(post(postUrl), owner,
                "{\"externalUserId\":\"ext-user-5\"}")).andReturn();
        assertEquals(400, ns.getResponse().getStatus(), raw(ns));
        assertEquals(golden("mb-test-token-not-signed.json"), raw(ns));
    }

    // ════════════════ 辅助 ════════════════

    private String login(String email) throws Exception {
        MvcResult r = mockMvc.perform(jsonBody(post("/api/v1/auth/login"), null,
                "{\"email\":\"" + email + "\",\"password\":\"Passw0rd!\"}")).andReturn();
        Matcher m = TOKEN.matcher(raw(r));
        assertThat(m.find()).as("login 响应应含 token: " + raw(r)).isTrue();
        return m.group(1);
    }

    /** 创建响应 data.id（邀请行的自增 id，掩码后与 golden 对齐）。 */
    private static String extractInvitationId(String body) {
        // PR4：键序归一后邻接正则不可靠 → Jackson 直取
        try {
            var __root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            return String.valueOf(__root.path("id").asLong());
        } catch (Exception e) {
            throw new IllegalStateException("创建响应应含 data.id: " + body, e);
        }
    }

    /** 从 invite_url 提取明文 token（录制脚本同款提取方式）。 */
    private static String extractLinkToken(String body) {
        Matcher m = Pattern.compile("inviteUrl\":\"[^\"]*/register\\?token=([A-Za-z0-9_-]+)").matcher(body);
        assertThat(m.find()).as("创建响应应含 inviteUrl: " + body).isTrue();
        return m.group(1);
    }

    private static MockHttpServletRequestBuilder jsonBody(MockHttpServletRequestBuilder builder,
                                                          String bearer, String body) {
        if (bearer != null) {
            builder.header("Authorization", bearer);
        }
        builder.contentType("application/json");
        if (body != null) {
            builder.content(body);
        }
        return builder;
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

    /**
     * 两侧同掩码：UUID / 时间戳 / 邀请 id / JWT / invite_url / expires_at_unix。
     * 顺序刻意：先 invite_url（内含 token=，避免被其它规则撕开）、再 JWT。
     */
    private static String mask(String s) {
        // PR4 语义比较入口：键序/转义归一后再掩码
        s = com.ragagent.support.ContractJson.semantic(s);
        String out = INVITE_URL.matcher(s).replaceAll("\"inviteUrl\":\"<inviteUrl>\"");
        out = JWT.matcher(out).replaceAll("\"token\":\"<jwt>\"");
        out = UNIX_TS.matcher(out).replaceAll("\"expiresAtUnix\":\"<unix>\"");
        out = UUID_VALUE.matcher(out).replaceAll("\"$1\":\"<uuid>\"");
        out = TS_VALUE.matcher(out).replaceAll("\"$1\":\"<ts>\"");
        out = INV_ID.matcher(out).replaceAll("\"id\":\"<id>\"");
        return out;
    }
}
