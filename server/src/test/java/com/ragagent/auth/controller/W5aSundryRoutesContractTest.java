package com.ragagent.auth.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.UnaryOperator;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * W5a 收尾批契约测试（56 条 w5a-* golden 逐条掩码比对；golden 来源：
 * scripts/record-w5a-golden.sh 对 Go dev server 的实录）。
 *
 * <p>覆盖 13 条小散路由：</p>
 * <ul>
 *   <li>auth 补 3：POST /auth/logout、/auth/refresh、/auth/switch-tenant</li>
 *   <li>tenants CRUD 4：GET /tenants、GET/PUT/DELETE /tenants/:id</li>
 *   <li>KB 标签 4：GET|POST /knowledge-bases/:id/tags、PUT|DELETE .../tags/:tag_id</li>
 *   <li>IM 回调 2：GET|POST /im/callback/:channel_id（engine 级无鉴权）</li>
 * </ul>
 *
 * <p>场景顺序严格复刻录制脚本（顺序敏感：logout 放最后——它吊销 java-phase1 的
 * 全部 token；refresh 的 sleep 是为了让轮换出的 refresh_token 与旧值不同，
 * 与录制脚本一致——同秒 JWT 会逐字节相同，Go 的 auth_tokens 撤销检查会变成
 * 堆序掷硬币，见约定 §9「W5a 补充」）。</p>
 *
 * <p>掩码面：uuid / 时间戳 / JWT（access_token/refresh_token/token）/
 * 数字 "id"（tenant-create 的 PG 序列值）/ "seq_id"（tag 序列）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class W5aSundryRoutesContractTest {

    private static final String BCRYPT = "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK"; // Passw0rd!
    private static final String OWNER = "11111111-2222-3333-4444-555555555501";
    private static final String VIEWER = "11111111-2222-3333-4444-555555555502";
    private static final String REF_KG = "5a5a0000000000000000000000000001";
    private static final String MISSING_UUID = "00000000-0000-0000-0000-100000000000";

    private static final Pattern TS_PATTERN = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})");
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"");
    private static final Pattern JWT_PATTERN = Pattern.compile(
            "\"(access_token|refresh_token|token)\":\"[^\"]*\"");
    private static final Pattern SEQ_PATTERN = Pattern.compile("\"seq_id\":\\d+");
    private static final Pattern NUMERIC_ID_PATTERN = Pattern.compile("\"id\":\\d+");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private String owner;
    private String viewer;

    @BeforeEach
    void seed() throws Exception {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        // 与 dev 库同形：phase1-test-tenant / 10GB 配额（golden 字面量）
        jdbc.execute("INSERT INTO tenants (id, name, description, business, status, storage_quota) VALUES "
                + "(10002, 'phase1-test-tenant', '', '', 'active', 10737418240)");
        jdbc.update("INSERT INTO users (id, username, email, password_hash, tenant_id, is_active) VALUES "
                + "(?, 'phase1test', 'java-phase1@weknora.test', ?, 10002, true),"
                + "(?, 'phase1viewer', 'java-phase1-viewer@weknora.test', ?, 10002, true)",
                OWNER, BCRYPT, VIEWER, BCRYPT);
        jdbc.update("INSERT INTO tenant_members (user_id, tenant_id, role, status) VALUES "
                + "(?, 10002, 'owner', 'active'), (?, 10002, 'viewer', 'active')", OWNER, VIEWER);
        // dev 库 10002 有默认 LOCAL 后端（KB 响应的 storage_backend_id 依赖它）
        jdbc.update("INSERT INTO storage_backends (id, tenant_id, name, provider, config, source, "
                + "status, legacy_alias) VALUES (?, ?, 'System LOCAL', 'local', '{}', 'env', "
                + "'active', TRUE)", "5b5b0000-0000-0000-0000-000000000001", 10002);
        owner = "Bearer " + login("java-phase1@weknora.test");
        viewer = "Bearer " + login("java-phase1-viewer@weknora.test");
    }

    @Test
    void w5aSundryRoutesFace() throws Exception {
        authFace();
        tenantsFace();
        String kb = tagsFace();
        imFace(kb);
        logoutFace();
        cleanup(kb);
    }

    // ── 1) auth 三兄弟（logout 放到本方法最后，见类注释） ─────────────────────

    private void authFace() throws Exception {
        // refresh：noAuthAPI 白名单路径（无鉴权可达）
        assertGolden(postJson("/api/v1/auth/refresh", null, null), 400,
                "w5a-auth-refresh-missing-body.json");
        assertGolden(postJson("/api/v1/auth/refresh", null, "{}"), 400,
                "w5a-auth-refresh-empty-obj.json");
        assertGolden(postJson("/api/v1/auth/refresh", null,
                "{\"refreshToken\":\"garbage.token.value\"}"), 401,
                "w5a-auth-refresh-garbage.json");

        String refreshToken = loginForRefresh("java-phase1@weknora.test");
        // 录制脚本同款：同秒 JWT 会逐字节相同（iat 秒级），sleep 保证轮换值不同
        Thread.sleep(2100);
        assertGolden(postJson("/api/v1/auth/refresh", null,
                "{\"refreshToken\":\"" + refreshToken + "\"}"), 200,
                "w5a-auth-refresh-ok.json");
        assertGolden(postJson("/api/v1/auth/refresh", null,
                "{\"refreshToken\":\"" + refreshToken + "\"}"), 401,
                "w5a-auth-refresh-revoked.json");

        // switch：tenant-optional（tenantless 可达；这里走 owner 常规链）
        assertGolden(postJson("/api/v1/auth/switch-tenant", owner, null), 400,
                "w5a-auth-switch-missing-body.json");
        assertGolden(postJson("/api/v1/auth/switch-tenant", owner, "{}"), 400,
                "w5a-auth-switch-empty.json");
        assertGolden(postJson("/api/v1/auth/switch-tenant", owner,
                "{\"tenant_id\":\"abc\"}"), 400, "w5a-auth-switch-badtype.json");
        assertGolden(postJson("/api/v1/auth/switch-tenant", owner,
                "{\"tenant_id\":424242}"), 403, "w5a-auth-switch-not-member.json");
        assertGolden(postJson("/api/v1/auth/switch-tenant", owner,
                "{\"tenant_id\":10002}"), 200, "w5a-auth-switch-ok.json");

        // logout：无头 → Auth 中间件 401（handler 里的 400 是死代码，照录）
        assertGolden(postJson("/api/v1/auth/logout", null, null), 401,
                "w5a-auth-logout-noheader.json");
    }

    /** logout 场景（单独方法：吊销 owner 全部 token，必须最后跑）。 */
    private void logoutFace() throws Exception {
        String fresh = login("java-phase1@weknora.test");
        assertGolden(postJson("/api/v1/auth/logout", "Bearer " + fresh, null),
                200, "w5a-auth-logout-ok.json");
        assertGolden(get("/api/v1/auth/validate", "Bearer " + fresh), 401,
                "w5a-auth-logout-revoked.json");
    }

    // ── 2) tenants CRUD ─────────────────────────────────────────────────────

    private void tenantsFace() throws Exception {
        assertGolden(get("/api/v1/tenants", owner), 200, "w5a-tenant-list.json");
        assertGolden(get("/api/v1/tenants", viewer), 200, "w5a-tenant-list-viewer.json");
        assertGolden(get("/api/v1/tenants/10002", owner), 200, "w5a-tenant-get.json");
        assertGolden(get("/api/v1/tenants/10002", viewer), 200, "w5a-tenant-get-viewer.json");
        // URL 租户 ≠ 活动租户 → PathTenantMatch 403（handler 的 500 "record not found"
        // 因中间件先行而不可达——与 audit-log 路由同款死代码）
        assertGolden(get("/api/v1/tenants/424242", owner), 403,
                "w5a-tenant-get-cross.json");
        assertGolden(putJson("/api/v1/tenants/10002", owner,
                "{\"name\":\"w5a 工作空间\",\"description\":\"w5a put 描述\"}"), 200,
                "w5a-tenant-put.json");
        // 录制脚本随即用 psql 还原 10002 的持久状态；这里同样还原
        jdbc.update("UPDATE tenants SET name='phase1-test-tenant', description='' WHERE id=10002");
        assertGolden(putJson("/api/v1/tenants/10002", owner,
                "{\"name\":\"   \"}"), 400, "w5a-tenant-put-blank.json");
        assertGolden(putJson("/api/v1/tenants/10002", owner,
                "{\"name\":\"" + "a".repeat(129) + "\"}"), 400, "w5a-tenant-put-longname.json");
        assertGolden(putJson("/api/v1/tenants/10002", owner,
                "{\"name\": 123}"), 400, "w5a-tenant-put-badjson.json");
        // Viewer PUT → OWNER 角色门（W5a 拦截器规则的直击场景）
        assertGolden(putJson("/api/v1/tenants/10002", viewer,
                "{\"name\":\"nope\"}"), 403, "w5a-tenant-put-nonowner.json");
        // 自助创建 → 删除。URL 里的新租户 id ≠ 活动租户 → PathTenantMatch 403
        //（Go 录制同形：self-serve 后 ctx 租户仍是 10002，DELETE 必走 403）
        assertGolden(postJson("/api/v1/tenants", owner,
                "{\"name\":\"w5a-tmp-租户\"}"), 201, "w5a-tenant-create.json",
                s -> NUMERIC_ID_PATTERN.matcher(s).replaceAll("\"id\":<id>"));
        Long tmpTenant = jdbc.queryForObject("SELECT id FROM tenants WHERE name='w5a-tmp-租户'", Long.class);
        assertGolden(delete("/api/v1/tenants/" + tmpTenant, owner), 403,
                "w5a-tenant-delete.json");
        assertGolden(delete("/api/v1/tenants/" + tmpTenant, owner), 403,
                "w5a-tenant-delete-again.json");
    }

    // ── 3) KB 标签 ──────────────────────────────────────────────────────────

    private String tagsFace() throws Exception {
        // 新契约（2026-09-29）：KB 创建响应为裸对象，向量库视图内联在 vectorStore 中，
        // 不再需要"剥 vector_store_engine_type"的部署态补丁
        MvcResult r = expect(201, postJson("/api/v1/knowledge-bases", owner,
                "{\"name\":\"w5a-tag-kb\",\"description\":\"w5a 标签批\",\"type\":\"document\"}"),
                "w5a-tag-kb-create.json");
        String kb = jsonPath(r, "id");
        jdbc.update("INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, source, "
                + "parse_status, summary_status, enable_status, file_name, file_type, file_size, "
                + "file_hash, file_path) VALUES "
                + "(?, 10002, ?, 'document', 'w5a 引用文档', 'manual', 'completed', 'none', "
                + "'enabled', 'w5a-ref.txt', 'txt', 10, '0000000000000000000000000000005a', '')", REF_KG, kb);

        assertGolden(get("/api/v1/knowledge-bases/" + kb + "/tags", owner), 200,
                "w5a-tag-list-empty.json");
        r = expect(200, postJson("/api/v1/knowledge-bases/" + kb + "/tags", owner,
                "{\"name\":\"w5a-标签A\",\"color\":\"#ff0000\",\"sort_order\":3}"),
                "w5a-tag-create.json");
        String tagA = jsonPath(r, "data.id");
        r = expect(200, postJson("/api/v1/knowledge-bases/" + kb + "/tags", owner,
                "{\"name\":\"w5a-标签B\"}"), "w5a-tag-create-b.json");
        String tagB = jsonPath(r, "data.id");
        r = expect(200, postJson("/api/v1/knowledge-bases/" + kb + "/tags", owner,
                "{\"name\":\"w5a-标签C\"}"), "w5a-tag-create-c.json");
        String tagC = jsonPath(r, "data.id");
        assertGolden(postJson("/api/v1/knowledge-bases/" + kb + "/tags", owner,
                "{\"name\":\"w5a-标签A\"}"), 409, "w5a-tag-create-dup.json");
        assertGolden(postJson("/api/v1/knowledge-bases/" + kb + "/tags", owner,
                "{\"color\":\"#000000\"}"), 400, "w5a-tag-create-missing-name.json");
        assertGolden(postJson("/api/v1/knowledge-bases/" + kb + "/tags", viewer,
                "{\"name\":\"nope\"}"), 403, "w5a-tag-create-nonowner.json");

        assertGolden(get("/api/v1/knowledge-bases/" + kb + "/tags", owner), 200, "w5a-tag-list.json");
        // 中文 query 参数必须走 queryParam（URL 手拼会经 ISO-8859-1 解码变 mojibake）
        assertGolden(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/knowledge-bases/" + kb + "/tags")
                .queryParam("keyword", "w5a-标签A")
                .header("Authorization", owner), 200, "w5a-tag-list-keyword.json");
        assertGolden(get("/api/v1/knowledge-bases/" + kb + "/tags?page=1&page_size=1", owner), 200,
                "w5a-tag-list-page.json");
        assertGolden(get("/api/v1/knowledge-bases/" + kb + "/tags?page=abc", owner), 400,
                "w5a-tag-list-badpage.json");
        // page=0 被 omitempty 视为空 → 归一到 1（200，非 400——golden 纠正直觉）
        assertGolden(get("/api/v1/knowledge-bases/" + kb + "/tags?page=0", owner), 200,
                "w5a-tag-list-zeropage.json");

        assertGolden(putJson("/api/v1/knowledge-bases/" + kb + "/tags/" + tagA, owner,
                "{\"name\":\"w5a-标签A2\",\"sort_order\":1}"), 200, "w5a-tag-update.json");
        Long seqA = jdbc.queryForObject("SELECT seq_id FROM knowledge_tags WHERE id=?", Long.class, tagA);
        assertGolden(putJson("/api/v1/knowledge-bases/" + kb + "/tags/" + seqA, owner,
                "{\"color\":\"#00ff00\"}"), 200, "w5a-tag-update-seqid.json");
        // UUID 指向不存在的标签 → service GetByID 的普通 error → plain-500 无 details 键
        assertGolden(putJson("/api/v1/knowledge-bases/" + kb + "/tags/" + MISSING_UUID, owner,
                "{\"name\":\"x\"}"), 500, "w5a-tag-update-missing.json");
        assertGolden(putJson("/api/v1/knowledge-bases/" + kb + "/tags/999999", owner,
                "{\"name\":\"x\"}"), 404, "w5a-tag-update-missing-seq.json");
        assertGolden(putJson("/api/v1/knowledge-bases/" + kb + "/tags/" + tagA, owner,
                "{\"name\":\"   \"}"), 400, "w5a-tag-update-blank.json");

        // updates 键是 **knowledge_id**、值是 tag uuid 列表（knowledge.go L955-968）
        assertGolden(putJson("/api/v1/knowledge/tags", owner,
                "{\"kb_id\":\"" + kb + "\",\"updates\":{\"" + REF_KG + "\":[\"" + tagC + "\"]}}"),
                200, "w5a-tag-ref-assign.json");
        assertGolden(delete("/api/v1/knowledge-bases/" + kb + "/tags/" + tagC, owner), 400,
                "w5a-tag-delete-referenced.json");
        assertGolden(delete("/api/v1/knowledge-bases/" + kb + "/tags/" + tagC + "?content_only=true",
                owner), 200, "w5a-tag-delete-content-only.json");
        assertGolden(delete("/api/v1/knowledge-bases/" + kb + "/tags/" + tagB, owner), 200,
                "w5a-tag-delete-ok.json");
        return kb;
    }

    // ── 4) IM 回调（engine 级、无鉴权；AuthFilter 让路） ─────────────────────

    private void imFace(String kb) throws Exception {
        MvcResult r = expect(201, postJson("/api/v1/agents", owner,
                "{\"name\":\"w5a-im-agent\",\"description\":\"w5a im agent\",\"config\":{}}"),
                "w5a-agent-create.json");
        String agent = jsonPath(r, "data.id");
        assertGolden(get("/api/v1/im/callback/00000000-0000-0000-0000-000000000000"), 404,
                "w5a-im-callback-unknown-get.json");
        assertGolden(postJson("/api/v1/im/callback/00000000-0000-0000-0000-000000000000", null, "{}"),
                404, "w5a-im-callback-unknown-post.json");
        r = expect(200, postJson("/api/v1/agents/" + agent + "/im-channels", owner,
                "{\"platform\":\"mattermost\",\"name\":\"w5a-im\"}"), "w5a-im-channel-create.json");
        String channel = jsonPath(r, "data.id");
        // enabled 渠道：Go dev 的 mattermost webhook 工厂建适配器失败 → 503 not available
        //（Java 无 adapter factory → 同形 503，MATCH 非 XDEP）
        assertGolden(get("/api/v1/im/callback/" + channel), 503,
                "w5a-im-callback-enabled-get.json");
        assertGolden(postJson("/api/v1/im/callback/" + channel, null,
                "text=hello&user_id=u1&channel_id=c1&post_id=p1"), 503,
                "w5a-im-callback-enabled-post.json");
        assertGolden(postJson("/api/v1/im-channels/" + channel + "/toggle", owner, null), 200,
                "w5a-im-channel-toggle.json");
        assertGolden(postJson("/api/v1/im/callback/" + channel, null, "{}"), 503,
                "w5a-im-callback-disabled.json");
        jdbc.update("DELETE FROM custom_agents WHERE id=?", agent);
    }

    private void cleanup(String kb) {
        jdbc.update("DELETE FROM knowledge_tag_relations WHERE knowledge_id=?", REF_KG);
        jdbc.update("DELETE FROM knowledges WHERE id=?", REF_KG);
        jdbc.update("DELETE FROM knowledge_tags WHERE knowledge_base_id=?", kb);
        jdbc.update("DELETE FROM knowledge_bases WHERE id=?", kb);
        jdbc.update("DELETE FROM tenants WHERE name='w5a-tmp-租户'");
    }

    // ═══════════════════ 基建 ═══════════════════

    private String login(String email) throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Passw0rd!\"}"))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(), raw(result));
        Matcher m = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(raw(result));
        assertTrue(m.find(), "login 响应应含 token: " + raw(result));
        return m.group(1);
    }

    private String loginForRefresh(String email) throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Passw0rd!\"}"))
                .andReturn();
        Matcher m = Pattern.compile("\"refresh_token\":\"([^\"]+)\"").matcher(raw(result));
        assertTrue(m.find(), "login 响应应含 refresh_token: " + raw(result));
        return m.group(1);
    }

    private void assertGolden(MockHttpServletRequestBuilder req, int status, String goldenName)
            throws Exception {
        expect(status, req, goldenName, s -> s);
    }

    private void assertGolden(MockHttpServletRequestBuilder req, int status, String goldenName,
                              UnaryOperator<String> extraMask) throws Exception {
        expect(status, req, goldenName, extraMask);
    }

    private MvcResult expect(int status, MockHttpServletRequestBuilder req, String goldenName)
            throws Exception {
        return expect(status, req, goldenName, s -> s);
    }

    private MvcResult expect(int status, MockHttpServletRequestBuilder req, String goldenName,
                             UnaryOperator<String> extraMask) throws Exception {
        MvcResult r = mockMvc.perform(req).andReturn();
        assertEquals(status, r.getResponse().getStatus(), goldenName + " 状态码不符: " + raw(r));
        String actual = raw(r);
        String golden = golden(goldenName);
        assertEquals(mask(extraMask.apply(golden)), mask(extraMask.apply(actual)), goldenName);
        return r;
    }

    private static MockHttpServletRequestBuilder get(String url) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url);
    }

    private static MockHttpServletRequestBuilder get(String url, String bearer) {
        MockHttpServletRequestBuilder b = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url);
        return bearer == null ? b : b.header("Authorization", bearer);
    }

    private static MockHttpServletRequestBuilder postJson(String url, String bearer, String body) {
        MockHttpServletRequestBuilder b = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post(url).contentType(sanitizeContentType(body));
        if (bearer != null) {
            b.header("Authorization", bearer);
        }
        return body == null ? b : b.content(body);
    }

    private static MockHttpServletRequestBuilder postJson(String url, String body) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post(url).contentType(sanitizeContentType(body))
                .content(body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
    }

    private static MediaType sanitizeContentType(String body) {
        return body != null && !body.startsWith("{") && !body.startsWith("[")
                ? MediaType.APPLICATION_FORM_URLENCODED : MediaType.APPLICATION_JSON;
    }

    private static MockHttpServletRequestBuilder putJson(String url, String bearer, String body) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put(url).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder delete(String url, String bearer) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete(url).header("Authorization", bearer);
    }

    private static String mask(String s) {
        s = TS_PATTERN.matcher(s).replaceAll("<ts>");
        s = JWT_PATTERN.matcher(s).replaceAll("\"$1\":\"<jwt>\"");
        s = UUID_PATTERN.matcher(s).replaceAll("\"<uuid>\"");
        s = SEQ_PATTERN.matcher(s).replaceAll("\"seq_id\":<seq>");
        return s;
    }

    private static String jsonPath(MvcResult r, String path) throws Exception {
        com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw(r));
        for (String seg : path.split("\\.")) {
            if (node.isArray() && seg.matches("\\d+")) {
                node = node.get(Integer.parseInt(seg));
            } else {
                node = node.get(seg);
            }
        }
        return node.asText();
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
}
