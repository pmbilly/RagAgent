package com.ragagent.wiki.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ragagent.TestSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Wiki 守卫的共享访问矩阵（{@link WikiPageController#requireWikiKB} 接上 Go
 * {@code access.ResolveKB} 的 org-share / shared-agent 两条读路径之后的契约测试）。
 *
 * <p>拓扑（fixture 种子对齐 {@code W5sSharedAgentContractTest} 的装配模式）：
 * 租户 10012（源，UA=owner）把 KB/agent 共享到 ORG；租户 10014（UB=owner）是组织成员
 * （org 角色 viewer），以 B 身份访问：</p>
 * <ul>
 *   <li>KB_SHARED  —— kb_shares viewer  → 读 200、写 403（Go 同款：viewer &lt; editor）；</li>
 *   <li>KB_EDITOR  —— kb_shares editor  → 读 200；写 403 是<b>刻意收紧</b>
 *       （Go 的 KBAccessWrite(Editor) 会放行 org-share editor，任务书裁定共享场景
 *       read-only，见控制器类注释的已知差异）；</li>
 *   <li>KB_AGENT   —— 仅出现在 AG_SEL 的 selected 范围（org-share 未共享）→
 *       agent_id 命中 200 / 无 agent_id 经 TenantCanAccessKBViaSomeSharedAgent 200；</li>
 *   <li>KB_NOACCESS—— 源租户内但无 org-share / selected 授予 → AG_SEL 拒 403；
 *       AG_ALL 的 all 范围覆盖源租户全部 KB（含它）；</li>
 *   <li>KB_STRANGER—— 无授予第三方租户的 KB：任何路径都 403（"无关租户"基线）；</li>
 *   <li>KB_DISABLED—— 共享 viewer + wiki 未启用 → 授予通过后 400（handler 文案）；</li>
 *   <li>KB_B       —— 调用方自有库：同租户读 200、写 201（写路径不受本批影响）。</li>
 * </ul>
 *
 * <p>错误形态：守卫拒绝走全局错误信封（code 1000/1002），与 handler 直写的
 * {@code {"error":...}} 不同（对照 Go 守卫 {@code c.Error()} → ErrorHandler）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class WikiSharedAccessGuardTest {

    private static final String BCRYPT =
            "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK"; // Passw0rd!

    /** KB/agent 源空间 */
    private static final long TENANT_SRC = 10012L;
    /** 调用方空间（组织成员） */
    private static final long TENANT_CALLER = 10014L;
    /** 未授予的第三方空间 */
    private static final long TENANT_STRANGER = 10099L;

    private static final String ORG = "77777777-1111-4444-8888-0000000000w1";
    private static final String UA = "11111111-2222-3333-4444-555555555101";
    private static final String UB = "11111111-2222-3333-4444-555555555102";
    private static final String US = "11111111-2222-3333-4444-555555555103";

    private static final String KB_SHARED = "32222222-2222-2222-2222-2222222222b1";
    private static final String KB_EDITOR = "32222222-2222-2222-2222-2222222222b2";
    private static final String KB_AGENT = "32222222-2222-2222-2222-2222222222b3";
    private static final String KB_NOACCESS = "32222222-2222-2222-2222-2222222222b4";
    private static final String KB_DISABLED = "32222222-2222-2222-2222-2222222222b5";
    private static final String KB_B = "32222222-2222-2222-2222-2222222222b6";
    private static final String KB_STRANGER = "32222222-2222-2222-2222-2222222222b7";

    private static final String AG_SEL = "44444444-4444-4444-4444-44444444b101";
    private static final String AG_ALL = "44444444-4444-4444-4444-44444444b102";
    private static final String AG_NONE = "44444444-4444-4444-4444-44444444b103";

    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);

        jdbc.execute("INSERT INTO tenants (id, name, description, business, status) VALUES "
                + "(" + TENANT_SRC + ",'wiki-shr-src','','','active'),"
                + "(" + TENANT_CALLER + ",'wiki-shr-caller','','','active'),"
                + "(" + TENANT_STRANGER + ",'wiki-shr-stranger','','','active')");

        insertUser(UA, "wikishr-a", "wiki-shr-a@weknora.test", TENANT_SRC);
        insertUser(UB, "wikishr-b", "wiki-shr-b@weknora.test", TENANT_CALLER);
        insertUser(US, "wikishr-s", "wiki-shr-s@weknora.test", TENANT_STRANGER);

        insertMember(UA, TENANT_SRC, "owner");
        insertMember(UB, TENANT_CALLER, "owner");
        insertMember(US, TENANT_STRANGER, "owner");

        // 组织：源空间 admin，调用方空间 org 角色 viewer（三维帽之一）
        jdbc.update("INSERT INTO organizations (id, name, description, owner_id, owner_tenant_id, "
                        + "invite_code, invite_code_validity_days, avatar, require_approval, searchable, "
                        + "member_limit, created_at, updated_at) VALUES "
                        + "(?, 'wiki-shr-org', 'wiki shared access org', ?, ?, 'wikishrcode000001', 7, '', "
                        + "false, false, 200, '2026-09-01 09:00:00+00', '2026-09-01 09:00:00+00')",
                ORG, UA, TENANT_SRC);
        jdbc.update("INSERT INTO organization_tenant_members (id, organization_id, tenant_id, role, "
                        + "representative_user_id, joined_at, created_at, updated_at) VALUES "
                        + "('66666666-6666-4444-8888-0000000000w1', ?, ?, 'admin', ?, "
                        + "'2026-09-01 09:00:00+00', '2026-09-01 09:00:00+00', '2026-09-01 09:00:00+00'),"
                        + "('66666666-6666-4444-8888-0000000000w2', ?, ?, 'viewer', ?, "
                        + "'2026-09-01 09:00:00+00', '2026-09-01 09:00:00+00', '2026-09-01 09:00:00+00')",
                ORG, TENANT_SRC, UA, ORG, TENANT_CALLER, UB);

        insertKb(KB_SHARED, TENANT_SRC, UA, true);
        insertKb(KB_EDITOR, TENANT_SRC, UA, true);
        insertKb(KB_AGENT, TENANT_SRC, UA, true);
        insertKb(KB_NOACCESS, TENANT_SRC, UA, true);
        insertKb(KB_DISABLED, TENANT_SRC, UA, false);
        insertKb(KB_B, TENANT_CALLER, UB, true);
        insertKb(KB_STRANGER, TENANT_STRANGER, US, true);

        // org-share：viewer 与 editor 两档
        jdbc.update("INSERT INTO kb_shares (id, knowledge_base_id, organization_id, shared_by_user_id, "
                        + "source_tenant_id, permission, created_at, updated_at) VALUES "
                        + "('88888888-8888-4444-8888-0000000000w1', ?, ?, ?, ?, 'viewer', "
                        + "'2026-09-01 09:10:00+00', '2026-09-01 09:10:00+00'),"
                        + "('88888888-8888-4444-8888-0000000000w2', ?, ?, ?, ?, 'editor', "
                        + "'2026-09-01 09:11:00+00', '2026-09-01 09:11:00+00')",
                KB_SHARED, ORG, UA, TENANT_SRC, KB_EDITOR, ORG, UA, TENANT_SRC);

        // 共享 agent：selected（KB_AGENT）/ all / none（空范围），全部共享到 ORG
        jdbc.update("INSERT INTO custom_agents (id, name, description, avatar, is_builtin, tenant_id, "
                + "created_by, config, created_at, updated_at) VALUES "
                + "(?, 'wiki-shr-sel', 'selected scope', '', false, ?, ?, "
                + "'{\"agent_mode\":\"quick-answer\",\"model_id\":\"m1\",\"kb_selection_mode\":\"selected\","
                + "\"knowledge_bases\":[\"" + KB_AGENT + "\"],\"web_search_enabled\":false}', "
                + "'2026-09-01 08:10:00+00', '2026-09-01 08:10:00+00'),"
                + "(?, 'wiki-shr-all', 'all scope', '', false, ?, ?, "
                + "'{\"agent_mode\":\"smart\",\"model_id\":\"m1\",\"kb_selection_mode\":\"all\","
                + "\"web_search_enabled\":false}', '2026-09-01 08:11:00+00', '2026-09-01 08:11:00+00'),"
                + "(?, 'wiki-shr-none', 'empty scope', '', false, ?, ?, "
                + "'{\"agent_mode\":\"quick-answer\",\"model_id\":\"m1\",\"kb_selection_mode\":\"none\","
                + "\"web_search_enabled\":false}', '2026-09-01 08:12:00+00', '2026-09-01 08:12:00+00')",
                AG_SEL, TENANT_SRC, UA, AG_ALL, TENANT_SRC, UA, AG_NONE, TENANT_SRC, UA);
        jdbc.update("INSERT INTO agent_shares (id, agent_id, organization_id, shared_by_user_id, "
                        + "source_tenant_id, permission, created_at, updated_at) VALUES "
                        + "('88888888-8888-4444-8888-0000000000w3', ?, ?, ?, ?, 'viewer', "
                        + "'2026-09-01 09:12:00+00', '2026-09-01 09:12:00+00'),"
                        + "('88888888-8888-4444-8888-0000000000w4', ?, ?, ?, ?, 'viewer', "
                        + "'2026-09-01 09:13:00+00', '2026-09-01 09:13:00+00'),"
                        + "('88888888-8888-4444-8888-0000000000w5', ?, ?, ?, ?, 'viewer', "
                        + "'2026-09-01 09:14:00+00', '2026-09-01 09:14:00+00')",
                AG_SEL, ORG, UA, TENANT_SRC, AG_ALL, ORG, UA, TENANT_SRC, AG_NONE, ORG, UA, TENANT_SRC);
    }

    private void insertUser(String id, String username, String email, long tenantId) {
        jdbc.update("INSERT INTO users (id, username, email, password_hash, tenant_id, is_active) "
                + "VALUES (?, ?, ?, ?, ?, true)", id, username, email, BCRYPT, tenantId);
    }

    private void insertMember(String userId, long tenantId, String role) {
        jdbc.update("INSERT INTO tenant_members (user_id, tenant_id, role, status) VALUES (?, ?, ?, 'active')",
                userId, tenantId, role);
    }

    private void insertKb(String kbId, long tenantId, String creatorId, boolean wikiEnabled) {
        jdbc.update("INSERT INTO knowledge_bases (id, name, tenant_id, type, creator_id, "
                        + "indexing_strategy, chunking_config, cos_config) "
                        + "VALUES (?, ?, ?, 'document', ?, ?, '{}', '{}')",
                kbId, kbId, tenantId, creatorId,
                "{\"vector_enabled\":false,\"keyword_enabled\":false,\"wiki_enabled\":"
                        + wikiEnabled + ",\"graph_enabled\":false}");
    }

    // ═══════════════════════ org-share（kb_shares）读授予 ═══════════════════════

    /** org-share viewer：只读放行（org 角色 viewer × share viewer = effective viewer）。 */
    @Test
    void orgShareViewerGrantsReadOnly() throws Exception {
        String b = login("wiki-shr-b@weknora.test");
        assertEquals(200, status(get("/api/v1/knowledgebase/" + KB_SHARED + "/wiki/pages")
                .header("Authorization", "Bearer " + b)), "org-share viewer 读必须放行");
        assertEquals(200, status(get("/api/v1/knowledgebase/" + KB_SHARED + "/wiki/stats")
                .header("Authorization", "Bearer " + b)));
        assertEquals(200, status(get("/api/v1/knowledgebase/" + KB_SHARED + "/wiki/folders")
                .header("Authorization", "Bearer " + b)));
    }

    /** org-share editor：读同样放行（editor ≥ viewer）。 */
    @Test
    void orgShareEditorGrantsRead() throws Exception {
        String b = login("wiki-shr-b@weknora.test");
        assertEquals(200, status(get("/api/v1/knowledgebase/" + KB_EDITOR + "/wiki/pages")
                .header("Authorization", "Bearer " + b)));
    }

    /** 无关租户：KB 在第三方租户且无任何授予 → 403 全局信封（文案逐字不变）。 */
    @Test
    void unsharedForeignKbIs403WithGuardEnvelope() throws Exception {
        String b = login("wiki-shr-b@weknora.test");
        MvcResult r = perform(get("/api/v1/knowledgebase/" + KB_STRANGER + "/wiki/pages")
                .header("Authorization", "Bearer " + b));
        assertEquals(403, r.getResponse().getStatus(), body(r));
        assertEquals("{\"error\":{\"code\":1002,\"details\":null,"
                        + "\"message\":\"Permission denied to access this knowledge base\"},\"success\":false}",
                body(r));
    }

    /**
     * org-share <b>不授予写</b>：viewer 共享 403（Go 同款：viewer &lt; KBAccessWrite 的
     * editor 下限）；editor 共享也 403 —— 后者是任务书裁定的刻意收紧（Go 会放行，
     * 见类注释已知差异），写必须仍走创建者/Admin+。
     */
    @Test
    void orgShareNeverGrantsWrite() throws Exception {
        String b = login("wiki-shr-b@weknora.test");

        MvcResult viewer = perform(post("/api/v1/knowledgebase/" + KB_SHARED + "/wiki/pages")
                .header("Authorization", "Bearer " + b)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slug\":\"a\",\"title\":\"A\"}"));
        assertEquals(403, viewer.getResponse().getStatus(), body(viewer));
        assertTrue(body(viewer).contains("Permission denied to access this knowledge base"), body(viewer));

        MvcResult editor = perform(post("/api/v1/knowledgebase/" + KB_EDITOR + "/wiki/pages")
                .header("Authorization", "Bearer " + b)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slug\":\"a\",\"title\":\"A\"}"));
        assertEquals(403, editor.getResponse().getStatus(), body(editor));

        MvcResult del = perform(delete("/api/v1/knowledgebase/" + KB_SHARED + "/wiki/pages/nope")
                .header("Authorization", "Bearer " + b));
        assertEquals(403, del.getResponse().getStatus(), body(del));
    }

    /** 授予通过后仍要过 handler 的 wiki 开关：共享 + wiki 未启用 → 400（handler 直写文案）。 */
    @Test
    void orgShareDisabledWikiStillReturns400() throws Exception {
        String b = login("wiki-shr-b@weknora.test");
        MvcResult r = perform(get("/api/v1/knowledgebase/" + KB_DISABLED + "/wiki/pages")
                .header("Authorization", "Bearer " + b));
        assertEquals(400, r.getResponse().getStatus(), body(r));
        assertEquals("{\"error\":\"error code: 400, error message: Wiki feature is not enabled "
                + "for this knowledge base\"}", body(r));
    }

    // ═══════════════════════ shared-agent 读授予 ═══════════════════════

    /** 显式 agent_id 命中 selected 范围 → 200；范围外 KB → 403；空范围 agent → 403。 */
    @Test
    void sharedAgentExplicitScopeGrantsReadOnlyWithinScope() throws Exception {
        String b = login("wiki-shr-b@weknora.test");
        String path = "/api/v1/knowledgebase/" + KB_AGENT + "/wiki/pages";

        assertEquals(200, status(get(path).param("agent_id", AG_SEL)
                .header("Authorization", "Bearer " + b)), "selected 范围内必须放行");

        assertEquals(403, status(get("/api/v1/knowledgebase/" + KB_NOACCESS + "/wiki/pages")
                .param("agent_id", AG_SEL).header("Authorization", "Bearer " + b)), "范围外必须拒绝");

        assertEquals(403, status(get(path).param("agent_id", AG_NONE)
                .header("Authorization", "Bearer " + b)), "空范围 agent 一律拒绝");
    }

    /** all 范围绑定在 agent 源租户上：AG_ALL 覆盖源租户内任何 KB。 */
    @Test
    void sharedAgentAllScopeCoversSourceTenantKbs() throws Exception {
        String b = login("wiki-shr-b@weknora.test");
        assertEquals(200, status(get("/api/v1/knowledgebase/" + KB_NOACCESS + "/wiki/pages")
                .param("agent_id", AG_ALL).header("Authorization", "Bearer " + b)));
    }

    /** 无 agent_id 的扫描路径（TenantCanAccessKBViaSomeSharedAgent）：任一可达 agent 覆盖即放行。 */
    @Test
    void sharedAgentScanWithoutAgentIdGrantsRead() throws Exception {
        String b = login("wiki-shr-b@weknora.test");
        assertEquals(200, status(get("/api/v1/knowledgebase/" + KB_AGENT + "/wiki/pages")
                .header("Authorization", "Bearer " + b)), "AG_SEL 的范围覆盖 KB_AGENT");
        assertEquals(403, status(get("/api/v1/knowledgebase/" + KB_STRANGER + "/wiki/pages")
                .header("Authorization", "Bearer " + b)), "第三方租户没有任何可达 agent → 仍 403");
    }

    /** agent_source_tenant_id 非法 → 400 "invalid agent_source_tenant_id"（ErrInvalidAgentSource）。 */
    @Test
    void sharedAgentBadSourceReturns400() throws Exception {
        String b = login("wiki-shr-b@weknora.test");
        MvcResult r = perform(get("/api/v1/knowledgebase/" + KB_AGENT + "/wiki/pages")
                .param("agent_id", AG_SEL).param("agent_source_tenant_id", "abc")
                .header("Authorization", "Bearer " + b));
        assertEquals(400, r.getResponse().getStatus(), body(r));
        assertEquals("{\"error\":{\"code\":1000,\"details\":null,"
                        + "\"message\":\"invalid agent_source_tenant_id\"},\"success\":false}",
                body(r));
    }

    /** 显式 source 与调用方租户相同 / 指向不存在的 share → 解析失败被吞掉 → 403（不外抛）。 */
    @Test
    void sharedAgentSourceMismatchIsDenied() throws Exception {
        String b = login("wiki-shr-b@weknora.test");
        assertEquals(403, status(get("/api/v1/knowledgebase/" + KB_AGENT + "/wiki/pages")
                .param("agent_id", AG_SEL).param("agent_source_tenant_id", String.valueOf(TENANT_CALLER))
                .header("Authorization", "Bearer " + b)), "source==caller → 无 share → 403");
        assertEquals(403, status(get("/api/v1/knowledgebase/" + KB_AGENT + "/wiki/pages")
                .param("agent_id", AG_SEL).param("agent_source_tenant_id", String.valueOf(TENANT_STRANGER))
                .header("Authorization", "Bearer " + b)), "source 无成员关系 → 403");
    }

    // ═══════════════════════ 同租户基线（本批不改写路径） ═══════════════════════

    /** 同租户读放行、同租户创建者写放行——写路径的创建者/Admin+ 校验不受本批影响。 */
    @Test
    void sameTenantReadAndOwnerWriteUnaffected() throws Exception {
        String b = login("wiki-shr-b@weknora.test");
        assertEquals(200, status(get("/api/v1/knowledgebase/" + KB_B + "/wiki/pages")
                .header("Authorization", "Bearer " + b)));

        MvcResult create = perform(post("/api/v1/knowledgebase/" + KB_B + "/wiki/pages")
                .header("Authorization", "Bearer " + b)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slug\":\"own/acme\",\"title\":\"Acme\"}"));
        assertEquals(201, create.getResponse().getStatus(), body(create));
    }

    /** 非成员租户（ strangers 与共享链完全无关）的请求仍 403。 */
    @Test
    void unrelatedTenantMemberIsDenied() throws Exception {
        String s = login("wiki-shr-s@weknora.test");
        assertEquals(403, status(get("/api/v1/knowledgebase/" + KB_SHARED + "/wiki/pages")
                .header("Authorization", "Bearer " + s)));
        assertEquals(403, status(get("/api/v1/knowledgebase/" + KB_AGENT + "/wiki/pages")
                .param("agent_id", AG_SEL).header("Authorization", "Bearer " + s)));
    }

    // ══════════════════════════════ 工具 ══════════════════════════════

    private String login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Passw0rd!\"}"))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(), raw(result));
        Matcher m = TOKEN.matcher(raw(result));
        assertTrue(m.find(), "login 响应应含 token: " + raw(result));
        return m.group(1);
    }

    private int status(org.springframework.test.web.servlet.RequestBuilder rb) throws Exception {
        return mockMvc.perform(rb).andReturn().getResponse().getStatus();
    }

    private MvcResult perform(org.springframework.test.web.servlet.RequestBuilder rb) throws Exception {
        return mockMvc.perform(rb).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String raw(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
