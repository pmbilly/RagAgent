package com.ragagent.wiki.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Wiki 页面 HTTP 层的契约测试（对照 Go internal/handler/wiki_page.go 的 21 个端点）。
 *
 * <p><b>三个来源</b>：</p>
 * <ol>
 *   <li><b>路由级越权</b>：{@code internal/router/router_wiki_test.go}（212 行）——跨空间 KB 的
 *       读/写拒绝、KB 不存在的 404。Go 侧这些 403 由 {@code KBAccessRead/KBAccessWrite} 守卫产生；
 *       Java 侧由控制器内的等价判定产生（见 {@link WikiPageController#requireWikiKB}），
 *       断言的状态码与 Go 测试逐条一致。</li>
 *   <li><b>所有权</b>：{@code OwnedWikiKBOrAdmin}（Go {@code rbac.go:500} →
 *       {@code RequireOwnershipOrRole}）——非创建者的 Contributor 写被拒、读放行，
 *       文案与 Go 一致。</li>
 *   <li><b>端点契约</b>：响应形态（实体直出 / gin.H / 裸数组 / handler 直写错误）、
 *       状态码、关键字段序。</li>
 * </ol>
 *
 * <p>走完整过滤链（含登录签发的 JWT），所以租户/角色是端到端生效的，而不是靠 mock。
 * RBAC 的<b>角色下限</b>规则由主会话在 WebConfig 注册（本模块不动该文件），因此这里不测
 * "Viewer 访问 Admin 端点"之类；控制器内部的所有权判定用真实身份覆盖。</p>
 *
 * <p>共享 H2 内存库（见 {@link TestSchema}），每个用例前重建数据。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class WikiHttpContractTest {

    private static final String BCRYPT =
            "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK"; // Passw0rd!

    /** 调用方所在空间 */
    private static final long TENANT = 10002L;
    /** 诱饵空间：里面的 KB 对调用方既不可读也不可写 */
    private static final long FOREIGN_TENANT = 9999L;

    private static final String KB_WIKI = "22222222-2222-2222-2222-2222222222a1";
    private static final String KB_WIKI_DISABLED = "22222222-2222-2222-2222-2222222222a2";
    private static final String KB_FOREIGN = "22222222-2222-2222-2222-2222222222a3";
    private static final String KB_OTHER_CREATOR = "22222222-2222-2222-2222-2222222222a4";
    private static final String KB_MISSING = "22222222-2222-2222-2222-2222222222ff";

    private static final String USER_OWNER = "11111111-2222-3333-4444-555555555501";
    private static final String USER_CONTRIBUTOR = "11111111-2222-3333-4444-555555555502";
    private static final String USER_VIEWER = "11111111-2222-3333-4444-555555555503";
    /** 另一个用户的 id：作为 KB_OTHER_CREATOR 的创建者 */
    private static final String USER_STRANGER = "11111111-2222-3333-4444-555555555504";

    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");

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
        tenant.setName("wiki-http-test-tenant");
        tenant.setStatus("active");
        tenantMapper.insert(tenant);

        insertUser(USER_OWNER, "wikiowner", "wiki-owner@weknora.test");
        insertUser(USER_CONTRIBUTOR, "wikicontrib", "wiki-contrib@weknora.test");
        insertUser(USER_VIEWER, "wikiviewer", "wiki-viewer@weknora.test");

        insertMember(USER_OWNER, "owner");
        insertMember(USER_CONTRIBUTOR, "contributor");
        insertMember(USER_VIEWER, "viewer");

        insertKb(KB_WIKI, TENANT, USER_OWNER, true);
        insertKb(KB_WIKI_DISABLED, TENANT, USER_OWNER, false);
        insertKb(KB_FOREIGN, FOREIGN_TENANT, USER_STRANGER, true);
        insertKb(KB_OTHER_CREATOR, TENANT, USER_STRANGER, true);
    }

    private void insertUser(String id, String username, String email) {
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

    private void insertMember(String userId, String role) {
        TenantMember member = new TenantMember();
        member.setUserId(userId);
        member.setTenantId(TENANT);
        member.setRole(role);
        member.setStatus("active");
        memberMapper.insert(member);
    }

    /** 对照 Go 测试的 {@code stubWikiKBLookup} 夹具：KB 行带 tenant_id + creator_id */
    private void insertKb(String kbId, long tenantId, String creatorId, boolean wikiEnabled) {
        jdbc.update("INSERT INTO knowledge_bases (id, name, tenant_id, type, creator_id, "
                        + "indexing_strategy, chunking_config, storage_config) "
                        + "VALUES (?, ?, ?, 'document', ?, ?, '{}', '{}')",
                kbId, kbId, tenantId, creatorId,
                "{\"vectorEnabled\":false,\"keywordEnabled\":false,\"wikiEnabled\":"
                        + wikiEnabled + ",\"graphEnabled\":false}");
    }

    // ══════════════════════════ 越权（对照 router_wiki_test.go） ══════════════════════════

    /**
     * 对照 Go {@code TestWikiReadRoutesDenyCrossTenantKB}：跨空间 KB 的全部读端点必须 403。
     * Go 由 {@code KBAccessRead} 守卫产生该 403，Java 由控制器内的等价判定产生。
     */
    @ParameterizedTest(name = "GET {0}")
    @CsvSource({
            "/api/v1/knowledgebase/%s/wiki/pages",
            "/api/v1/knowledgebase/%s/wiki/pages/secret-page",
            "/api/v1/knowledgebase/%s/wiki/folders",
            "/api/v1/knowledgebase/%s/wiki/index",
            "/api/v1/knowledgebase/%s/wiki/graph",
            "/api/v1/knowledgebase/%s/wiki/stats",
            "/api/v1/knowledgebase/%s/wiki/search?q=test",
            "/api/v1/knowledgebase/%s/wiki/lint",
            "/api/v1/knowledgebase/%s/wiki/issues",
            "/api/v1/knowledgebase/%s/wiki/revisions/secret-page",
    })
    void wikiReadRoutesDenyCrossTenantKb(String pathTemplate) throws Exception {
        String token = loginOwner();
        String path = pathTemplate.formatted(KB_FOREIGN);

        assertEquals(403, status(get(path).header("Authorization", "Bearer " + token)),
                path + " 必须拒绝跨空间读取");
    }

    /** 写端点的守卫矩阵同 {@code RegisterWikiPageRoutes}：跨空间一律 403。 */
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "POST,   /api/v1/knowledgebase/%s/wiki/pages",
            "PUT,    /api/v1/knowledgebase/%s/wiki/move-page",
            "PUT,    /api/v1/knowledgebase/%s/wiki/pages/some-page",
            "DELETE, /api/v1/knowledgebase/%s/wiki/pages/some-page",
            "POST,   /api/v1/knowledgebase/%s/wiki/revert",
            "POST,   /api/v1/knowledgebase/%s/wiki/folders",
            "PUT,    /api/v1/knowledgebase/%s/wiki/folders/f-1",
            "DELETE, /api/v1/knowledgebase/%s/wiki/folders/f-1",
            "POST,   /api/v1/knowledgebase/%s/wiki/rebuild-links",
            "POST,   /api/v1/knowledgebase/%s/wiki/auto-fix",
            "PUT,    /api/v1/knowledgebase/%s/wiki/issues/1/status",
    })
    void wikiWriteRoutesDenyCrossTenantKb(String method, String pathTemplate) throws Exception {
        String token = loginOwner();
        String path = pathTemplate.formatted(KB_FOREIGN);

        assertEquals(403, status(json(method, path, "{}").header("Authorization", "Bearer " + token)),
                method + " " + path + " 必须拒绝跨空间写入");
    }

    /**
     * 被拒的读走<b>全局错误信封</b>（对照 Go 的 {@code c.Error()} → ErrorHandler），
     * 与 handler 直写的 {@code {"error":"..."}} 形态不同。
     */
    @Test
    void crossTenantKbDenialUsesGlobalErrorEnvelope() throws Exception {
        String token = loginOwner();
        String body = body(perform(get("/api/v1/knowledgebase/" + KB_FOREIGN + "/wiki/pages")
                .header("Authorization", "Bearer " + token)));

        assertTrue(body.startsWith("{\"error\":{\"code\":1002,\"details\":null,\"message\":\""),
                "跨空间拒绝必须是全局错误信封（键字母序）：" + body);
        assertTrue(body.endsWith("\"success\":false}"), body);
        assertTrue(body.contains("Permission denied to access this knowledge base"), body);
    }

    /** 对照 Go {@code kb_access.go} 的 {@code access.ErrNotFound} 分支：KB 不存在 → 404（不是 400）。 */
    @Test
    void unknownKbReturns404() throws Exception {
        String token = loginOwner();
        MvcResult r = perform(get("/api/v1/knowledgebase/" + KB_MISSING + "/wiki/pages")
                .header("Authorization", "Bearer " + token));

        assertEquals(404, r.getResponse().getStatus(), body(r));
        assertTrue(body(r).contains("\"code\":1003"), body(r));
        assertTrue(body(r).contains("knowledge base not found"), body(r));
    }

    /**
     * {@code OwnedWikiKBOrAdmin}：KB 的创建者本人或 Admin+ 才能写；非创建者的 Contributor
     * 写被拒（403），读仍然放行。文案与 Go 的
     * {@code "Forbidden: must own the resource or have the required role"} 同源。
     */
    @Test
    void nonOwnerContributorCanReadButNotWrite() throws Exception {
        String token = loginContributor();

        assertEquals(200, status(get("/api/v1/knowledgebase/" + KB_OTHER_CREATOR + "/wiki/pages")
                .header("Authorization", "Bearer " + token)), "读不要求所有权");

        MvcResult r = perform(post("/api/v1/knowledgebase/" + KB_OTHER_CREATOR + "/wiki/pages")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"slug\":\"a\",\"title\":\"A\"}"));
        assertEquals(403, r.getResponse().getStatus(), body(r));
        assertTrue(body(r).contains("must own the resource or have the required role"), body(r));
    }

    /** 非创建者的 Viewer 读放行、写一律 403。 */
    @Test
    void viewerCanReadButNotWrite() throws Exception {
        String token = loginViewer();
        assertEquals(200, status(get("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/pages")
                .header("Authorization", "Bearer " + token)));
        assertEquals(403, status(json("POST", "/api/v1/knowledgebase/" + KB_WIKI + "/wiki/pages", "{}")
                .header("Authorization", "Bearer " + token)));
    }

    /**
     * 对照 Go {@code TestWikiOperationLogRouteIsRemoved}：wiki 专用的操作日志端点已下线，
     * 不能再出现在路由表里（Go 由“没有注册该路由”保证，Java 由“没有该映射”保证）。
     */
    @Test
    void removedOperationLogRouteIsGone() throws Exception {
        String token = loginOwner();
        assertEquals(404, status(get("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/log")
                .header("Authorization", "Bearer " + token)));
    }

    /** KB 未启用 wiki → handler 直写的 400，文案带 AppError 的 {@code error code:} 前缀。 */
    @Test
    void wikiDisabledKbReturns400WithAppErrorText() throws Exception {
        String token = loginOwner();
        MvcResult r = perform(get("/api/v1/knowledgebase/" + KB_WIKI_DISABLED + "/wiki/pages")
                .header("Authorization", "Bearer " + token));

        assertEquals(400, r.getResponse().getStatus(), body(r));
        assertEquals("{\"error\":\"error code: 400, error message: Wiki feature is not enabled "
                + "for this knowledge base\"}", body(r));
    }

    // ══════════════════════════════ 页面 CRUD ══════════════════════════════

    /** ListPages：<b>结构体直出</b>（无 data 信封），字段序 = Go struct 声明序。 */
    @Test
    void listPagesReturnsStructOrderWithoutEnvelope() throws Exception {
        String token = loginOwner();
        createPage(token, "{\"slug\":\"entity/acme\",\"title\":\"Acme\",\"page_type\":\"entity\"}");

        String body = body(perform(get("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/pages")
                .header("Authorization", "Bearer " + token)));

        assertTrue(body.startsWith("{\"pages\":["), body);
        int iPages = body.indexOf("\"pages\"");
        int iTotal = body.indexOf("\"total\"");
        int iPage = body.indexOf("\"page\"");
        int iPageSize = body.indexOf("\"page_size\"");
        int iTotalPages = body.indexOf("\"total_pages\"");
        assertTrue(iPages < iTotal && iTotal < iPage && iPage < iPageSize && iPageSize < iTotalPages,
                "字段序必须是 pages,total,page,page_size,total_pages：" + body);
        assertFalse(body.contains("\"success\""), "不应该是 data 信封：" + body);
    }

    @Test
    void listPagesHonoursTypeFilter() throws Exception {
        String token = loginOwner();
        createPage(token, "{\"slug\":\"entity/acme\",\"title\":\"Acme\",\"page_type\":\"entity\"}");
        createPage(token, "{\"slug\":\"concept/rag\",\"title\":\"RAG\",\"page_type\":\"concept\"}");

        String body = body(perform(get("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/pages")
                .param("page_type", "entity")
                .header("Authorization", "Bearer " + token)));
        assertTrue(body.contains("\"total\":1"), body);
        assertTrue(body.contains("entity/acme"), body);
        assertFalse(body.contains("concept/rag"), body);
    }

    /** CreatePage → 201，响应是裸实体（键序 = Go 声明序）。 */
    @Test
    void createPageReturns201EntityInDeclarationOrder() throws Exception {
        String token = loginOwner();
        MvcResult r = perform(post("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/pages")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"slug\":\"entity/acme\",\"title\":\"Acme\",\"page_type\":\"entity\","
                        + "\"content\":\"Body\"}"));

        assertEquals(201, r.getResponse().getStatus(), body(r));
        String body = body(r);
        assertFalse(body.contains("\"success\""), "实体直出不得带 data/success 信封：" + body);
        int iId = body.indexOf("\"id\":\"");
        int iTenant = body.indexOf("\"tenant_id\":");
        int iKb = body.indexOf("\"knowledge_base_id\":");
        int iSlug = body.indexOf("\"slug\":");
        int iTitle = body.indexOf("\"title\":");
        int iPageType = body.indexOf("\"page_type\":");
        assertTrue(iId == 1 && iId < iTenant && iTenant < iKb && iKb < iSlug
                && iSlug < iTitle && iTitle < iPageType, "实体键序错误：" + body);
        // 服务侧补的默认值（对照 Go：status 空 → published，version 0 → 1）
        assertTrue(body.contains("\"status\":\"published\""), body);
        assertTrue(body.contains("\"version\":1"), body);
    }

    /** page_type 只在<b>非空</b>时校验（对照 Go L372）。 */
    @Test
    void createPageRejectsInvalidPageTypeButAllowsEmpty() throws Exception {
        String token = loginOwner();

        MvcResult bad = perform(post("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/pages")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"slug\":\"x\",\"page_type\":\"bogus\"}"));
        assertEquals(400, bad.getResponse().getStatus(), body(bad));
        assertEquals("{\"error\":\"Invalid page_type: bogus\"}", body(bad));

        MvcResult empty = perform(post("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/pages")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"slug\":\"y\",\"page_type\":\"\",\"status\":\"\"}"));
        assertEquals(201, empty.getResponse().getStatus(), body(empty));
    }

    @Test
    void createPageRejectsEmptyBodyWithGoEofText() throws Exception {
        String token = loginOwner();
        MvcResult r = perform(post("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/pages")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json"));

        assertEquals(400, r.getResponse().getStatus(), body(r));
        assertEquals("{\"error\":\"Invalid request body: EOF\"}", body(r));
    }

    /** GetPage 走 catch-all slug：多段 slug（{@code entity/acme}）必须原样解析。 */
    @Test
    void getPageResolvesMultiSegmentSlug() throws Exception {
        String token = loginOwner();
        createPage(token, "{\"slug\":\"entity/acme\",\"title\":\"Acme\",\"page_type\":\"entity\"}");

        String body = body(perform(get("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/pages/entity/acme")
                .header("Authorization", "Bearer " + token)));
        assertTrue(body.contains("\"slug\":\"entity/acme\""), body);
    }

    @Test
    void getUnknownPageReturns404WithHandlerText() throws Exception {
        String token = loginOwner();
        MvcResult r = perform(get("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/pages/nope")
                .header("Authorization", "Bearer " + token));

        assertEquals(404, r.getResponse().getStatus(), body(r));
        assertEquals("{\"error\":\"Wiki page not found\"}", body(r));
    }

    /**
     * UpdatePage 的乐观锁：{@code version} 与库中不符 → 409，
     * body 是 gin.H → <b>键字母序</b>（current_version 在 error 前）。
     */
    @Test
    void updatePageVersionConflictReturns409WithCurrentVersion() throws Exception {
        String token = loginOwner();
        createPage(token, "{\"slug\":\"entity/acme\",\"title\":\"Acme\",\"page_type\":\"entity\"}");

        MvcResult r = perform(put("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/pages/entity/acme")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"title\":\"Acme 2\",\"version\":99}"));

        assertEquals(409, r.getResponse().getStatus(), body(r));
        assertEquals("{\"current_version\":1,\"error\":\"Wiki page was modified by someone else\"}",
                body(r));
    }

    /** 部分更新：缺席字段保留库中值，命中版本号时递增。 */
    @Test
    void updatePageMergesAbsentFieldsAndBumpsVersion() throws Exception {
        String token = loginOwner();
        createPage(token, "{\"slug\":\"entity/acme\",\"title\":\"Acme\",\"page_type\":\"entity\","
                + "\"summary\":\"keep me\"}");

        MvcResult r = perform(put("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/pages/entity/acme")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"content\":\"new body\",\"version\":1}"));

        assertEquals(200, r.getResponse().getStatus(), body(r));
        assertTrue(body(r).contains("\"summary\":\"keep me\""), "缺席字段必须保留：" + body(r));
        assertTrue(body(r).contains("\"title\":\"Acme\""), body(r));
        assertTrue(body(r).contains("\"version\":2"), body(r));
    }

    @Test
    void updatePageRejectsInvalidStatus() throws Exception {
        String token = loginOwner();
        createPage(token, "{\"slug\":\"entity/acme\",\"title\":\"Acme\",\"page_type\":\"entity\"}");

        MvcResult r = perform(put("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/pages/entity/acme")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"status\":\"bogus\"}"));

        assertEquals(400, r.getResponse().getStatus(), body(r));
        assertEquals("{\"error\":\"Invalid status: bogus\"}", body(r));
    }

    @Test
    void deletePageReturns204ThenGetReturns404() throws Exception {
        String token = loginOwner();
        createPage(token, "{\"slug\":\"entity/acme\",\"title\":\"Acme\",\"page_type\":\"entity\"}");

        MvcResult del = perform(delete("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/pages/entity/acme")
                .header("Authorization", "Bearer " + token));
        assertEquals(204, del.getResponse().getStatus(), body(del));
        assertEquals("", body(del));

        assertEquals(404, status(get("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/pages/entity/acme")
                .header("Authorization", "Bearer " + token)));
    }

    // ══════════════════════════════ 修订历史 ══════════════════════════════

    @Test
    void listRevisionsReturnsCurrentVersionAndHistory() throws Exception {
        String token = loginOwner();
        createPage(token, "{\"slug\":\"entity/acme\",\"title\":\"Acme\",\"page_type\":\"entity\","
                + "\"content\":\"v1\"}");
        editPage(token, "v2");

        String body = body(perform(get("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/revisions/entity/acme")
                .header("Authorization", "Bearer " + token)));
        int iRev = body.indexOf("\"revisions\"");
        int iTotal = body.indexOf("\"total\"");
        int iCur = body.indexOf("\"current_version\"");
        assertTrue(iRev == 1 && iRev < iTotal && iTotal < iCur,
                "字段序必须是 revisions,total,current_version：" + body);
        assertTrue(body.contains("\"current_version\":2"), body);
        assertTrue(body.contains("\"total\":1"), body);
    }

    /** 单条模式带 content；version 非法（0/负/非数字）一律 400 "Invalid version"。 */
    @Test
    void singleRevisionIncludesContentAndRejectsBadVersion() throws Exception {
        String token = loginOwner();
        createPage(token, "{\"slug\":\"entity/acme\",\"title\":\"Acme\",\"page_type\":\"entity\","
                + "\"content\":\"v1\"}");
        editPage(token, "v2");

        MvcResult one = perform(get("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/revisions/entity/acme")
                .param("version", "1")
                .header("Authorization", "Bearer " + token));
        assertEquals(200, one.getResponse().getStatus(), body(one));
        assertTrue(body(one).contains("\"version\":1"), body(one));
        assertTrue(body(one).contains("\"content\":\"v1\""), body(one));

        for (String bad : new String[]{"0", "-1", "abc"}) {
            MvcResult r = perform(get("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/revisions/entity/acme")
                    .param("version", bad)
                    .header("Authorization", "Bearer " + token));
            assertEquals(400, r.getResponse().getStatus(), body(r));
            assertEquals("{\"error\":\"Invalid version\"}", body(r));
        }
    }

    @Test
    void listRevisionsUnknownPageReturns404() throws Exception {
        String token = loginOwner();
        MvcResult r = perform(get("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/revisions/nope")
                .header("Authorization", "Bearer " + token));
        assertEquals(404, r.getResponse().getStatus(), body(r));
        assertEquals("{\"error\":\"Wiki page not found\"}", body(r));
    }

    /** Revert 到当前版本 → <b>400</b>（不是 500），文案取 Go 的哨兵错误。 */
    @Test
    void revertToCurrentVersionReturns400() throws Exception {
        String token = loginOwner();
        createPage(token, "{\"slug\":\"entity/acme\",\"title\":\"Acme\",\"page_type\":\"entity\"}");

        MvcResult r = perform(post("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/revert")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"slug\":\"entity/acme\",\"version\":1}"));

        assertEquals(400, r.getResponse().getStatus(), body(r));
        assertEquals("{\"error\":\"cannot revert to the current version\"}", body(r));
    }

    @Test
    void revertToOlderRevisionAppliesAsNormalEdit() throws Exception {
        String token = loginOwner();
        createPage(token, "{\"slug\":\"entity/acme\",\"title\":\"Acme\",\"page_type\":\"entity\","
                + "\"content\":\"v1\"}");
        editPage(token, "v2");

        MvcResult r = perform(post("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/revert")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"slug\":\"entity/acme\",\"version\":1}"));

        assertEquals(200, r.getResponse().getStatus(), body(r));
        assertTrue(body(r).contains("\"content\":\"v1\""), body(r));
        assertTrue(body(r).contains("\"version\":3"), "回滚也是一次普通编辑，版本前进：" + body(r));
    }

    /** revert 的 {@code binding:"required"} 复刻：缺字段时的 Go validator 文案（多字段换行连接）。 */
    @Test
    void revertWithoutRequiredFieldsReturnsGoBindingText() throws Exception {
        String token = loginOwner();
        MvcResult r = perform(post("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/revert")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{}"));

        assertEquals(400, r.getResponse().getStatus(), body(r));
        assertEquals("{\"error\":\"Invalid request body: "
                + "Key: 'WikiPageRevertRequest.Slug' Error:Field validation for 'Slug' failed on the 'required' tag\\n"
                + "Key: 'WikiPageRevertRequest.Version' Error:Field validation for 'Version' failed on the 'required' tag\"}",
                body(r));
    }

    // ══════════════════════════════ 移动 / 文件夹 ══════════════════════════════

    @Test
    void movePageWithoutSlugReturnsGoBindingText() throws Exception {
        String token = loginOwner();
        MvcResult r = perform(put("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/move-page")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{}"));

        assertEquals(400, r.getResponse().getStatus(), body(r));
        assertEquals("{\"error\":\"Invalid request body: Key: 'WikiPageMoveRequest.Slug' "
                + "Error:Field validation for 'Slug' failed on the 'required' tag\"}", body(r));
    }

    @Test
    void moveUnknownPageReturns404WithSentinelText() throws Exception {
        String token = loginOwner();
        MvcResult r = perform(put("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/move-page")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"slug\":\"nope\"}"));
        assertEquals(404, r.getResponse().getStatus(), body(r));
        assertEquals("{\"error\":\"wiki page not found\"}", body(r));
    }

    /** 移动页面会重算缓存的 category_path（folder_id 是唯一真相来源）。 */
    @Test
    void movePageIntoFolderUpdatesCategoryPath() throws Exception {
        String token = loginOwner();
        createPage(token, "{\"slug\":\"entity/acme\",\"title\":\"Acme\",\"page_type\":\"entity\"}");
        String folderId = createFolder(token, "", "AI");

        MvcResult r = perform(put("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/move-page")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"slug\":\"entity/acme\",\"folder_id\":\"" + folderId + "\"}"));

        assertEquals(200, r.getResponse().getStatus(), body(r));
        assertTrue(body(r).contains("\"folder_id\":\"" + folderId + "\""), body(r));
        assertTrue(body(r).contains("\"category_path\":[\"AI\"]"), body(r));
    }

    /** 文件夹 CRUD + {@code writeWikiFolderError} 的三分支状态码。 */
    @Test
    void folderCrudAndErrorMapping() throws Exception {
        String token = loginOwner();

        String folderId = createFolder(token, "", "AI");

        // 同名兄弟 → 409
        MvcResult dup = perform(post("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/folders")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"parent_id\":\"\",\"name\":\"AI\"}"));
        assertEquals(409, dup.getResponse().getStatus(), body(dup));
        assertEquals("{\"error\":\"wiki folder name conflict\"}", body(dup));

        // 改名
        MvcResult renamed = perform(put("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/folders/" + folderId)
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"name\":\"人工智能\"}"));
        assertEquals(200, renamed.getResponse().getStatus(), body(renamed));
        assertTrue(body(renamed).contains("\"name\":\"人工智能\""), body(renamed));

        // 空文件夹可删 → 204
        MvcResult del = perform(delete("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/folders/" + folderId)
                .header("Authorization", "Bearer " + token));
        assertEquals(204, del.getResponse().getStatus(), body(del));

        // 未知文件夹 → 404
        MvcResult missing = perform(delete("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/folders/nope")
                .header("Authorization", "Bearer " + token));
        assertEquals(404, missing.getResponse().getStatus(), body(missing));
        assertEquals("{\"error\":\"wiki folder not found\"}", body(missing));
    }

    /** 非空（含子文件夹）的文件夹不可删 → 409，文案取 Go 的 sentinel。 */
    @Test
    void deletingNonEmptyFolderReturns409() throws Exception {
        String token = loginOwner();
        String parent = createFolder(token, "", "AI");
        createFolder(token, parent, "子目录");

        MvcResult r = perform(delete("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/folders/" + parent)
                .header("Authorization", "Bearer " + token));
        assertEquals(409, r.getResponse().getStatus(), body(r));
        assertEquals("{\"error\":\"wiki folder is not empty\"}", body(r));
    }

    /**
     * ListFolders：{@code WikiFolderNode} 把 {@code WikiFolder} 扁平展开（Go 的匿名嵌入），
     * 并附 page_count（<b>递归</b>子树计数）与 has_children。
     */
    @Test
    void listFoldersReturnsFlattenedNodesWithCounts() throws Exception {
        String token = loginOwner();
        String parent = createFolder(token, "", "AI");
        String child = createFolder(token, parent, "LLM");
        createPage(token, "{\"slug\":\"entity/acme\",\"title\":\"Acme\",\"page_type\":\"entity\"}");
        movePage(token, "entity/acme", child);

        String body = body(perform(get("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/folders")
                .header("Authorization", "Bearer " + token)));
        assertTrue(body.startsWith("{\"parent_id\":\"\",\"folders\":["), body);
        assertTrue(body.contains("\"name\":\"AI\""), body);
        assertTrue(body.contains("\"path\":\"AI\""), body);
        assertTrue(body.contains("\"page_count\":1"), "父节点的计数是递归的：" + body);
        assertTrue(body.contains("\"has_children\":true"), body);
        assertFalse(body.contains("LLM"), "根层级只列直接子节点：" + body);

        String children = body(perform(get("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/folders")
                .param("parent_id", parent)
                .header("Authorization", "Bearer " + token)));
        assertTrue(children.contains("\"name\":\"LLM\""), children);
        assertTrue(children.contains("\"has_children\":false"), children);
    }

    // ═══════════════════════════ 索引 / 图谱 / 统计 ═══════════════════════════

    @Test
    void indexReturnsIntroVersionGroups() throws Exception {
        String token = loginOwner();
        createPage(token, "{\"slug\":\"entity/acme\",\"title\":\"Acme\",\"page_type\":\"entity\","
                + "\"summary\":\"s\"}");

        String body = body(perform(get("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/index")
                .header("Authorization", "Bearer " + token)));
        int iIntro = body.indexOf("\"intro\"");
        int iVersion = body.indexOf("\"version\"");
        int iGroups = body.indexOf("\"groups\"");
        assertTrue(iIntro == 1 && iIntro < iVersion && iVersion < iGroups,
                "字段序必须是 intro,version,groups：" + body);
        assertTrue(body.contains("\"items\":[{\"slug\":\"entity/acme\""), body);
    }

    /** GetGraph 的参数校验分支必须逐一与 Go 对齐（400 文案逐字相同）。 */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "bad mode       | mode=top  | {\"error\":\"mode must be 'overview' or 'ego'\"}",
            "ego w/o center | mode=ego  | {\"error\":\"center is required when mode=ego\"}",
            "depth zero     | depth=0   | {\"error\":\"depth must be a positive integer\"}",
            "depth negative | depth=-2  | {\"error\":\"depth must be a positive integer\"}",
            "depth not int  | depth=abc | {\"error\":\"depth must be a positive integer\"}",
            "limit zero     | limit=0   | {\"error\":\"limit must be a positive integer\"}",
            "limit not int  | limit=x   | {\"error\":\"limit must be a positive integer\"}",
    })
    void graphRejectsBadParams(String name, String query, String expected) throws Exception {
        String token = loginOwner();
        MvcResult r = perform(get("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/graph?" + query)
                .header("Authorization", "Bearer " + token));
        assertEquals(400, r.getResponse().getStatus(), body(r));
        assertEquals(expected, body(r));
    }

    /** depth / limit 超上限是<b>静默夹紧</b>而不是报错（meta 里回显夹紧后的值）。 */
    @Test
    void graphClampsOversizedParams() throws Exception {
        String token = loginOwner();
        createPage(token, "{\"slug\":\"entity/acme\",\"title\":\"Acme\",\"page_type\":\"entity\"}");

        String body = body(perform(get("/api/v1/knowledgebase/" + KB_WIKI
                + "/wiki/graph?mode=ego&center=entity/acme&depth=99&limit=99999")
                .header("Authorization", "Bearer " + token)));

        assertTrue(body.contains("\"depth\":3"), "depth 夹到 3：" + body);
        assertTrue(body.contains("\"mode\":\"ego\""), body);
        assertTrue(body.contains("\"center\":\"entity/acme\""), body);
        assertTrue(body.contains("\"returned\":1"), body);
    }

    /** 默认模式是 overview；center 在非 ego 模式下被忽略。 */
    @Test
    void graphDefaultsToOverview() throws Exception {
        String token = loginOwner();
        createPage(token, "{\"slug\":\"entity/acme\",\"title\":\"Acme\",\"page_type\":\"entity\"}");

        String body = body(perform(get("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/graph")
                .header("Authorization", "Bearer " + token)));
        assertTrue(body.startsWith("{\"nodes\":["), body);
        assertTrue(body.contains("\"mode\":\"overview\""), body);
        assertTrue(body.contains("\"slug\":\"entity/acme\""), body);
        // depth 为 0（默认值）→ omitempty 省略；limit 夹在 500
        assertFalse(body.contains("\"depth\":0"), body);
    }

    @Test
    void statsReturnsAggregateInDeclarationOrder() throws Exception {
        String token = loginOwner();
        createPage(token, "{\"slug\":\"entity/acme\",\"title\":\"Acme\",\"page_type\":\"entity\"}");

        String body = body(perform(get("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/stats")
                .header("Authorization", "Bearer " + token)));
        assertTrue(body.startsWith("{\"total_pages\":1"), body);
        int iPages = body.indexOf("\"total_pages\"");
        int iByType = body.indexOf("\"pages_by_type\"");
        int iLinks = body.indexOf("\"total_links\"");
        int iOrphan = body.indexOf("\"orphan_count\"");
        assertTrue(iPages < iByType && iByType < iLinks && iLinks < iOrphan, body);
        assertTrue(body.contains("\"is_active\":false"), body);
    }

    // ═══════════════════════════ 检索 / 维护 / 问题 ═══════════════════════════

    @Test
    void searchRequiresQueryAndWrapsResultInPagesKey() throws Exception {
        String token = loginOwner();
        createPage(token, "{\"slug\":\"entity/acme\",\"title\":\"Acme\",\"page_type\":\"entity\"}");

        MvcResult missing = perform(get("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/search")
                .header("Authorization", "Bearer " + token));
        assertEquals(400, missing.getResponse().getStatus(), body(missing));
        assertEquals("{\"error\":\"Search query 'q' is required\"}", body(missing));

        MvcResult hit = perform(get("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/search?q=Acme")
                .header("Authorization", "Bearer " + token));
        assertEquals(200, hit.getResponse().getStatus(), body(hit));
        assertTrue(body(hit).startsWith("{\"pages\":["),
                "SearchPages 是 gin.H{\"pages\":...}：" + body(hit));
    }

    @Test
    void rebuildLinksReturnsMessageOnly() throws Exception {
        String token = loginOwner();
        MvcResult r = perform(post("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/rebuild-links")
                .header("Authorization", "Bearer " + token));
        assertEquals(200, r.getResponse().getStatus(), body(r));
        assertEquals("{\"message\":\"Links rebuilt successfully\"}", body(r));
    }

    @Test
    void lintReturnsReportInDeclarationOrderAndAutoFixReturnsFixedCount() throws Exception {
        String token = loginOwner();
        createPage(token, "{\"slug\":\"entity/acme\",\"title\":\"Acme\",\"page_type\":\"entity\"}");

        String report = body(perform(get("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/lint")
                .header("Authorization", "Bearer " + token)));
        int iKb = report.indexOf("\"knowledge_base_id\"");
        int iIssues = report.indexOf("\"issues\"");
        int iScore = report.indexOf("\"health_score\"");
        int iStats = report.indexOf("\"stats\"");
        int iSummary = report.indexOf("\"summary\"");
        assertTrue(iKb == 1 && iKb < iIssues && iIssues < iScore && iScore < iStats && iStats < iSummary,
                "字段序必须是 knowledge_base_id,issues,health_score,stats,summary：" + report);

        String fix = body(perform(post("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/auto-fix")
                .header("Authorization", "Bearer " + token)));
        // gin.H 字母序：fixed < message
        assertTrue(fix.startsWith("{\"fixed\":"), fix);
        assertTrue(fix.contains("\"message\":\"Auto-fixed "), fix);
    }

    /** ListIssues 是<b>裸数组</b>（不是 data 信封、也不是 {pages:...}）。 */
    @Test
    void listIssuesReturnsBareArray() throws Exception {
        String token = loginOwner();
        MvcResult r = perform(get("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/issues")
                .header("Authorization", "Bearer " + token));
        assertEquals(200, r.getResponse().getStatus(), body(r));
        assertTrue(body(r).startsWith("["), body(r));
    }

    @Test
    void updateIssueStatusValidatesBodyAndReturnsMessage() throws Exception {
        String token = loginOwner();

        MvcResult bad = perform(put("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/issues/i-1/status")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"status\":\"nope\"}"));
        assertEquals(400, bad.getResponse().getStatus(), body(bad));
        assertEquals("{\"error\":\"Invalid status. Must be pending, ignored, or resolved\"}", body(bad));

        // 匿名 struct 的 required 报错不带结构体前缀（对照 go-playground/validator）
        MvcResult missing = perform(put("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/issues/i-1/status")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{}"));
        assertEquals(400, missing.getResponse().getStatus(), body(missing));
        assertEquals("{\"error\":\"Invalid request body: Key: 'Status' Error:Field validation for "
                + "'Status' failed on the 'required' tag\"}", body(missing));

        MvcResult ok = perform(put("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/issues/i-1/status")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"status\":\"ignored\"}"));
        assertEquals(200, ok.getResponse().getStatus(), body(ok));
        assertEquals("{\"message\":\"Issue status updated successfully\"}", body(ok));
    }

    // ══════════════════════════════ 测试工具 ══════════════════════════════

    private String loginOwner() throws Exception {
        return login("wiki-owner@weknora.test");
    }

    private String loginContributor() throws Exception {
        return login("wiki-contrib@weknora.test");
    }

    private String loginViewer() throws Exception {
        return login("wiki-viewer@weknora.test");
    }

    private String login(String email) throws Exception {
        String body = body(perform(post("/api/v1/auth/login")
                .contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"Passw0rd!\"}")));
        Matcher m = TOKEN.matcher(body);
        assertTrue(m.find(), "登录响应应含 token：" + body);
        return m.group(1);
    }

    private String createPage(String token, String json) throws Exception {
        MvcResult r = perform(post("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/pages")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(json));
        assertEquals(201, r.getResponse().getStatus(), "建页应成功：" + body(r));
        return body(r);
    }

    private String createFolder(String token, String parentId, String name) throws Exception {
        MvcResult r = perform(post("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/folders")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"parent_id\":\"" + parentId + "\",\"name\":\"" + name + "\"}"));
        assertEquals(201, r.getResponse().getStatus(), "建目录应成功：" + body(r));
        return jsonString(body(r), "id");
    }

    private void movePage(String token, String slug, String folderId) throws Exception {
        MvcResult r = perform(put("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/move-page")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"slug\":\"" + slug + "\",\"folder_id\":\"" + folderId + "\"}"));
        assertEquals(200, r.getResponse().getStatus(), body(r));
    }

    /** 改一次正文，让页面进入 version 2（并为 version 1 落一份快照）。 */
    private void editPage(String token, String content) throws Exception {
        MvcResult r = perform(put("/api/v1/knowledgebase/" + KB_WIKI + "/wiki/pages/entity/acme")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"content\":\"" + content + "\"}"));
        assertEquals(200, r.getResponse().getStatus(), body(r));
    }

    private int status(RequestBuilder rb) throws Exception {
        return mockMvc.perform(rb).andReturn().getResponse().getStatus();
    }

    private MvcResult perform(RequestBuilder rb) throws Exception {
        return mockMvc.perform(rb).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String jsonString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"([^\"]*)\"").matcher(json);
        assertTrue(m.find(), "响应里缺少键 " + key + "：" + json);
        return m.group(1);
    }

    private static MockHttpServletRequestBuilder json(String method, String path, String payload) {
        return switch (method) {
            case "POST" -> post(path).contentType("application/json").content(payload);
            case "PUT" -> put(path).contentType("application/json").content(payload);
            case "DELETE" -> delete(path).contentType("application/json").content(payload);
            default -> get(path);
        };
    }
}
