package com.ragagent.memory.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ragagent.TestSchema;
import com.ragagent.auth.apikey.domain.TenantAPIKeyScope;
import com.ragagent.auth.apikey.filter.APIKeyGateInterceptor;
import com.ragagent.auth.apikey.filter.APIKeyRouteAuthorizer;
import com.ragagent.auth.apikey.filter.APIKeyRoutePolicies;
import com.ragagent.auth.apikey.filter.APIKeyRoutePolicy;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 长期记忆 HTTP 层的契约测试（对照 Go {@code internal/handler/memory.go} 的 16 个端点，
 * 路由见 {@code internal/router/routes_memory.go}）。
 *
 * <h2>期望值来源</h2>
 * <p>错误面（{@code memory-not-found.json} 等）仍是 2026-09-18 对<b>运行中的 Go dev
 * server</b>（:8080，db=localhost:15432）打真实请求、{@code curl -o} 落盘录下来的实录；
 * 成功面在 <b>2026-10-01 契约换锚</b>（§14.9k M1）后重录：信封退役、键名 camelCase、
 * 列表改 {@code {items,page,pageSize,total}}、创建 201 / 删除类 204。文件都在
 * {@code server/src/test/resources/contracts/memory-*.json}。</p>
 *
 * <p>录制序（顺序会影响响应内容——列表顺序、计数）：</p>
 * <pre>
 *   GET settings → PUT settings {}（400）→ PUT settings 非法 JSON（400）
 *   → GET items（空）→ GET items?status=bogus（400）
 *   → POST items（建）→ PUT items/{id}（改）→ POST items/{id}/confirm
 *   → POST items/{id}/reject → POST items 空内容（500）→ POST items 凭据（400）
 *   → DELETE items/{未知 id}（404）→ GET export（空）→ GET topics / documents（空）
 *   → POST consolidate → 种 topic/doc 行 → GET topics / documents
 *   → POST topics/{id}/promote → DELETE documents/{id} → GET export（有数据）
 *   → DELETE items（清空）
 * </pre>
 *
 * <h2>掩码</h2>
 * <p>UUID、时间戳两侧同掩码后逐字节比对（中文按原始字节，
 * 见 §9「中文 golden 比较必须按原始字节」）。</p>
 *
 * <h2>换锚后仍要盯住的形态</h2>
 * <ol>
 *   <li>{@code GET /memory/items} 空仓库是 {@code "items":[]}，
 *       而 {@code GET /memory/export} 空仓库是 {@code "items":null}——
 *       同一个 service 方法，两条响应路径的 nil 语义不同（GORM {@code Find} 会把
 *       nil slice 初始化成空切片，Export 的 {@code var items} 不会）；换锚保留了它。</li>
 *   <li>清空（{@code DELETE /memory/items}）是同步删除 → <b>204</b>，
 *       旧 Go 的 {@code {"removed":N}} 计数不再下发。</li>
 *   <li>{@code Export} 的 Content-Type 仍是 {@code application/json; charset=utf-8}
 *       （Go 是 {@code c.Header("Content-Disposition", …)} + {@code c.JSON}，
 *       不是 {@code application/octet-stream}）。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class MemoryHttpContractTest {

    private static final String BCRYPT =
            "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK"; // Passw0rd!

    private static final long TENANT = 10002L;
    private static final String USER_ID = "11111111-2222-3333-4444-555555555501";
    private static final String USER_EMAIL = "memory-contract@weknora.test";
    /** Go 的 {@code Principal.StorageID()}：web 主体是 {@code web_user:<user_id>}。 */
    private static final String SUBJECT_ID = "web_user:" + USER_ID;

    private static final String UNKNOWN_ID = "11111111-2222-3333-4444-999999999999";
    private static final String TOPIC_ID = "aaaaaaaa-1111-2222-3333-444444444401";
    private static final String DOC_ID = "bbbbbbbb-1111-2222-3333-444444444401";

    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");

    /** 与 golden 比对前的统一掩码：UUID（任意键）+ 时间戳。 */
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "\"[A-Za-z_]+?\":\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"");
    private static final Pattern TS_PATTERN = Pattern.compile(
            "\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})\"");

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
        // memory 的 7 张表不在 TestSchema.resetData 的清理列表里（那文件不归本模块改），
        // 所以自己清。这七张表之间没有任何外键，顺序无所谓。
        for (String table : List.of("memory_item_embeddings", "memory_extraction_sessions",
                "memory_items", "memory_tombstones", "memory_topic_stats", "memory_doc_affinity",
                "memory_subjects")) {
            jdbc.execute("DELETE FROM " + table);
        }

        Tenant tenant = new Tenant();
        tenant.setId(TENANT);
        tenant.setName("memory-contract-tenant");
        tenant.setStatus("active");
        // golden 是记忆**开着**的租户录的（Go 的 tenants.memory_config）
        tenant.setMemoryConfig(
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                        .put("enabled", true));
        tenantMapper.insert(tenant);

        User user = new User();
        user.setId(USER_ID);
        user.setUsername("memoryowner");
        user.setEmail(USER_EMAIL);
        user.setPasswordHash(BCRYPT);
        user.setTenantId(TENANT);
        user.setIsActive(true);
        user.setPreferences(new UserPreferences());
        userMapper.insert(user);

        TenantMember member = new TenantMember();
        member.setUserId(USER_ID);
        member.setTenantId(TENANT);
        member.setRole("owner");
        member.setStatus("active");
        memberMapper.insert(member);
    }

    // ══════════════════════════ 设置 ══════════════════════════

    @Test
    void settingsMatchesGo() throws Exception {
        MvcResult r = perform(get("/api/v1/memory/settings").header("Authorization", bearer()));

        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("memory-settings.json"), raw(r));
    }

    @Test
    void settingsWithoutEnabledIsBadRequest() throws Exception {
        MvcResult r = perform(jsonBody(put("/api/v1/memory/settings"), "{}")
                .header("Authorization", bearer()));

        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("memory-settings-required.json"), raw(r));
    }

    /**
     * 非法 JSON → 400「请求参数不合法 / 请求体格式不正确」。
     *
     * <p>M3 起走标准请求绑定（不再是手写的 {@code rawBody} 解析）：details 由全局处理器
     * 给中文文案，不再逐字节复刻 Go 的 {@code encoding/json} 消息。</p>
     */
    @Test
    void settingsInvalidJsonIsBadRequest() throws Exception {
        MvcResult r = perform(jsonBody(put("/api/v1/memory/settings"), "not-json")
                .header("Authorization", bearer()));

        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("memory-body-malformed.json"), raw(r));
    }

    /** 空体与字面量 {@code null} 落同一条 400「请求体不能为空」（标准绑定，M3）。 */
    @Test
    void settingsEmptyOrNullBodyIsBadRequest() throws Exception {
        MvcResult empty = perform(put("/api/v1/memory/settings")
                .contentType("application/json")
                .header("Authorization", bearer()));
        assertEquals(400, empty.getResponse().getStatus(), raw(empty));
        assertEquals(golden("memory-body-empty.json"), raw(empty));

        MvcResult nullBody = perform(jsonBody(put("/api/v1/memory/settings"), "null")
                .header("Authorization", bearer()));
        assertEquals(400, nullBody.getResponse().getStatus(), raw(nullBody));
        assertEquals(golden("memory-body-empty.json"), raw(nullBody));
    }

    /** 未知字段被**忽略**（与 Go 的 {@code encoding/json} 同款）：多带一个字段不该整条 400。 */
    @Test
    void settingsToleratesUnknownFields() throws Exception {
        MvcResult r = perform(jsonBody(put("/api/v1/memory/settings"),
                "{\"enabled\":true,\"whatever\":1}").header("Authorization", bearer()));

        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("memory-settings.json"), raw(r));
    }

    /** 关掉自己的开关：响应是**合并视图**（实测 workspaceEnabled 仍 true、effective 翻 false）。 */
    @Test
    void updateSettingsFlipsUserSwitch() throws Exception {
        MvcResult off = perform(jsonBody(put("/api/v1/memory/settings"), "{\"enabled\":false}")
                .header("Authorization", bearer()));

        assertEquals(200, off.getResponse().getStatus(), raw(off));
        assertEquals(golden("memory-settings-user-off.json"), raw(off));

        MvcResult on = perform(jsonBody(put("/api/v1/memory/settings"), "{\"enabled\":true}")
                .header("Authorization", bearer()));
        assertEquals(golden("memory-settings.json"), raw(on));
    }

    /** {@code {"enabled":null}} 与"根本没给 enabled"落同一条 400（Go 的 *bool 判 nil）。 */
    @Test
    void updateSettingsWithNullEnabledIsBadRequest() throws Exception {
        MvcResult r = perform(jsonBody(put("/api/v1/memory/settings"), "{\"enabled\":null}")
                .header("Authorization", bearer()));

        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("memory-settings-required.json"), raw(r));
    }

    // ══════════════════════════ 条目 ══════════════════════════

    @Test
    void listItemsEmptyMatchesGo() throws Exception {
        MvcResult r = perform(get("/api/v1/memory/items").header("Authorization", bearer()));

        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("memory-items-empty.json"), raw(r));
    }

    @Test
    void listItemsRejectsUnsupportedStatus() throws Exception {
        MvcResult r = perform(get("/api/v1/memory/items?status=bogus")
                .header("Authorization", bearer()));

        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("memory-status-unsupported.json"), raw(r));
    }

    /** 分页参数是**容错**的：{@code limit=abc&offset=-5} 返回 200 的空页（实测）。 */
    @Test
    void listItemsToleratesGarbagePaging() throws Exception {
        MvcResult r = perform(get("/api/v1/memory/items?limit=abc&offset=-5")
                .header("Authorization", bearer()));

        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("memory-items-empty.json"), raw(r));
    }

    /** 四个合法 status 都放行（白名单来自 Go 的 types.MemoryStatus* 常量）。 */
    @Test
    void listItemsAcceptsEachSupportedStatus() throws Exception {
        for (String status : List.of("active", "superseded", "archived", "pending")) {
            MvcResult r = perform(get("/api/v1/memory/items?status=" + status)
                    .header("Authorization", bearer()));
            assertEquals(200, r.getResponse().getStatus(), status + " → " + raw(r));
            assertEquals(golden("memory-items-empty.json"), raw(r));
        }
    }

    @Test
    void createItemMatchesGo() throws Exception {
        MvcResult r = createItem("{\"kind\":\"fact\",\"content\":\"我偏好用 PostgreSQL\","
                + "\"importance\":4}");

        assertEquals(201, r.getResponse().getStatus(), raw(r));
        assertEquals(goldenMasked("memory-item-create.json"), mask(raw(r)));
    }

    @Test
    void updateItemMatchesGo() throws Exception {
        String id = createdId(createItem("{\"kind\":\"fact\",\"content\":\"我偏好用 PostgreSQL\","
                + "\"importance\":4}"));

        MvcResult r = perform(jsonBody(put("/api/v1/memory/items/" + id),
                "{\"content\":\"我偏好用 MySQL\",\"importance\":2}")
                .header("Authorization", bearer()));

        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(goldenMasked("memory-item-update.json"), mask(raw(r)));
    }

    @Test
    void confirmItemMatchesGo() throws Exception {
        String id = createdId(createItem("{\"kind\":\"fact\",\"content\":\"我偏好用 PostgreSQL\","
                + "\"importance\":4}"));
        perform(jsonBody(put("/api/v1/memory/items/" + id),
                "{\"content\":\"我偏好用 MySQL\",\"importance\":2}")
                .header("Authorization", bearer()));

        MvcResult r = perform(post("/api/v1/memory/items/" + id + "/confirm")
                .header("Authorization", bearer()));

        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(goldenMasked("memory-item-confirm.json"), mask(raw(r)));
    }

    @Test
    void rejectItemMatchesGo() throws Exception {
        String id = createdId(createItem("{\"kind\":\"fact\",\"content\":\"我偏好用 PostgreSQL\","
                + "\"importance\":4}"));

        MvcResult r = perform(post("/api/v1/memory/items/" + id + "/reject")
                .header("Authorization", bearer()));

        // 拒绝就是删除（Go 的 service 侧如此）：换锚后是 204，无响应体
        assertEquals(204, r.getResponse().getStatus(), raw(r));
        assertEquals("", raw(r), "204 必须无响应体");
    }

    @Test
    void deleteUnknownItemIsNotFound() throws Exception {
        MvcResult r = perform(delete("/api/v1/memory/items/" + UNKNOWN_ID)
                .header("Authorization", bearer()));

        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("memory-not-found.json"), raw(r));
    }

    /** 空内容落 Go 的 default 分支：<b>500</b> + details（不参与白名单映射）。 */
    @Test
    void emptyContentIsInternalServerError() throws Exception {
        MvcResult r = createItem("{\"kind\":\"fact\",\"content\":\"   \",\"importance\":1}");

        assertEquals(500, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("memory-empty-content.json"), raw(r));
    }

    /**
     * 条目字段缺省与显式 {@code null} **同义**（都按零值，M3 的 {@code orEmpty}/{@code orZero}）：
     * {@code content:null} 与空串走同一条 500 空内容，而不是绑定期 400。
     */
    @Test
    void createItemTreatsExplicitNullFieldsAsZeroValues() throws Exception {
        MvcResult r = createItem("{\"kind\":\"fact\",\"content\":null,\"importance\":null}");

        assertEquals(500, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("memory-empty-content.json"), raw(r));
    }

    /** 只给 {@code content} 就够：{@code kind}/{@code importance} 缺省即零值。 */
    @Test
    void createItemAcceptsOmittedOptionalFields() throws Exception {
        MvcResult r = createItem("{\"content\":\"只给内容\"}");

        assertEquals(201, r.getResponse().getStatus(), raw(r));
        assertTrue(raw(r).contains("\"content\":\"只给内容\""), raw(r));
    }

    /** 全是凭据的陈述被拒：400 + err.Error()（details 为 null）。 */
    @Test
    void sensitiveContentIsBadRequest() throws Exception {
        MvcResult r = createItem("{\"kind\":\"fact\",\"content\":"
                + "\"sk-abcdefghijklmnopqrstuvwxyz1234567890ABCDEF\",\"importance\":1}");

        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("memory-sensitive.json"), raw(r));
    }

    /**
     * 清空是<b>同步删除</b>：换锚后按 §1.13 返 204，旧 Go 的 {@code {"removed":N}}
     * 计数不再下发（前端也不再展示条数）。
     */
    @Test
    void clearIsSynchronousDeleteWithNoContent() throws Exception {
        createItem("{\"kind\":\"fact\",\"content\":\"我偏好用 PostgreSQL\",\"importance\":4}");

        MvcResult r = perform(delete("/api/v1/memory/items").header("Authorization", bearer()));

        assertEquals(204, r.getResponse().getStatus(), raw(r));
        assertEquals("", raw(r), "204 必须无响应体");

        // 真的清掉了：列表回到空
        MvcResult after = perform(get("/api/v1/memory/items").header("Authorization", bearer()));
        assertEquals(golden("memory-items-empty.json"), raw(after));
    }

    // ══════════════════════════ 主题 / 文档 ══════════════════════════

    @Test
    void listTopicsEmptyMatchesGo() throws Exception {
        MvcResult r = perform(get("/api/v1/memory/topics").header("Authorization", bearer()));

        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("memory-topics-empty.json"), raw(r));
    }

    @Test
    void listDocumentsEmptyMatchesGo() throws Exception {
        MvcResult r = perform(get("/api/v1/memory/documents").header("Authorization", bearer()));

        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("memory-documents-empty.json"), raw(r));
    }

    /** 种一行 topic + 一行 doc：视图的字段序、aliases 的投影、threshold 的注入都要钉住。 */
    @Test
    void listTopicsMatchesGo() throws Exception {
        seedTopic();

        MvcResult r = perform(get("/api/v1/memory/topics").header("Authorization", bearer()));

        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(goldenMasked("memory-topics.json"), mask(raw(r)));
    }

    @Test
    void listDocumentsMatchesGo() throws Exception {
        seedDocAffinity();

        MvcResult r = perform(get("/api/v1/memory/documents").header("Authorization", bearer()));

        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(goldenMasked("memory-documents.json"), mask(raw(r)));
    }

    /**
     * 提升一个主题：返回**新建的记忆**（kind=interest、origin=manual、importance=3），
     * 而且响应里**不含** topic 行本身。
     */
    @Test
    void promoteTopicMatchesGo() throws Exception {
        seedTopic();

        MvcResult r = perform(post("/api/v1/memory/topics/" + TOPIC_ID + "/promote")
                .header("Authorization", bearer()));

        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(goldenMasked("memory-topic-promote.json"), mask(raw(r)));

        // 提升过的主题不再出现在"正在观察"的列表里
        MvcResult after = perform(get("/api/v1/memory/topics").header("Authorization", bearer()));
        assertEquals(golden("memory-topics-empty.json"), raw(after));
    }

    @Test
    void deleteDocumentMatchesGo() throws Exception {
        seedDocAffinity();

        MvcResult r = perform(delete("/api/v1/memory/documents/" + DOC_ID)
                .header("Authorization", bearer()));

        assertEquals(204, r.getResponse().getStatus(), raw(r));
        assertEquals("", raw(r), "204 必须无响应体");
    }

    @Test
    void deleteUnknownTopicIsNotFound() throws Exception {
        MvcResult r = perform(delete("/api/v1/memory/topics/" + UNKNOWN_ID)
                .header("Authorization", bearer()));

        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("memory-not-found.json"), raw(r));
    }

    @Test
    void promoteUnknownTopicIsNotFound() throws Exception {
        MvcResult r = perform(post("/api/v1/memory/topics/" + UNKNOWN_ID + "/promote")
                .header("Authorization", bearer()));

        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("memory-not-found.json"), raw(r));
    }

    @Test
    void deleteUnknownDocumentIsNotFound() throws Exception {
        MvcResult r = perform(delete("/api/v1/memory/documents/" + UNKNOWN_ID)
                .header("Authorization", bearer()));

        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("memory-not-found.json"), raw(r));
    }

    // ══════════════════════════ 导出 / 整理 ══════════════════════════

    /**
     * 空仓库导出：{@code "data":null}（<b>不是</b> {@code []}）+ 两个响应头。
     *
     * <h2>Content-Disposition</h2>
     * <p>逐字节对照 Go：{@code attachment; filename="weknora-memories.json"}。
     * 它是这条端点唯一真正的"下载"信号——<b>文件本体仍然是普通 JSON</b>，
     * 不是 {@code application/octet-stream}（实测，别照直觉改）。</p>
     *
     * <h2>Content-Type</h2>
     * <p>Go 的 {@code c.JSON} 恒写 {@code application/json; charset=utf-8}。
     * Java 侧这条端点<b>显式</b>带上 charset，是全局 JSON 端点里最接近 Go 的一条
     * （其余 Java 端点是裸的 {@code application/json}，那是既有的全局差异）。</p>
     *
     * <p>⚠️ <b>已知的容器层差异（一个空格）</b>：MockMvc 原样保留
     * {@code application/json; charset=utf-8}（分隔符后有 OWS），但真容器上
     * Tomcat 的 {@code setContentType} 会把它规范化成
     * {@code application/json;charset=utf-8}——已对运行中的 Java :8082 实测。
     * 两者按 RFC 7231 语义等价（OWS 可选），但字节差一个空格。与既有的
     * "status line 无 reason phrase / CORS 多三个 Vary / X-Request-ID 大小写"
     * 属同一族容器固有差异（§9 阶段 5.2 步 4），<b>正文不受影响</b>。
     * 这里两条都钉住：解析后的 MediaType 必须相等（语义），MockMvc 这一层保留原样（字节）。</p>
     */
    @Test
    void exportEmptyMatchesGo() throws Exception {
        MvcResult r = perform(get("/api/v1/memory/export").header("Authorization", bearer()));

        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("memory-export-empty.json"), raw(r));
        assertEquals("attachment; filename=\"weknora-memories.json\"",
                r.getResponse().getHeader("Content-Disposition"));

        String contentType = r.getResponse().getHeader("Content-Type");
        assertEquals(org.springframework.http.MediaType.parseMediaType("application/json;charset=utf-8"),
                org.springframework.http.MediaType.parseMediaType(contentType),
                "语义：必须是带 charset 的 JSON（Go 的 c.JSON 恒带）");
        assertEquals("application/json; charset=utf-8", contentType,
                "MockMvc 这一层按原样保留（线上 Tomcat 会去掉这个空格，见方法注释）");
    }

    /** 有数据时 total/truncated 一起出现，且条目形状与 /items 完全一致。 */
    @Test
    void exportWithItemsMatchesGo() throws Exception {
        seedTopic();
        perform(post("/api/v1/memory/topics/" + TOPIC_ID + "/promote")
                .header("Authorization", bearer()));

        MvcResult r = perform(get("/api/v1/memory/export").header("Authorization", bearer()));

        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(goldenMasked("memory-export.json"), mask(raw(r)));
    }

    @Test
    void consolidateMatchesGo() throws Exception {
        MvcResult r = perform(post("/api/v1/memory/consolidate").header("Authorization", bearer()));

        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("memory-consolidate.json"), raw(r));
    }

    /** 工作区把记忆关掉之后：读路径照常（空仓库），写/整理落 400 {@code memory is disabled}。 */
    @Test
    void disabledWorkspaceRejectsWritesButAllowsReads() throws Exception {
        jdbc.update("UPDATE tenants SET memory_config = ? WHERE id = ?",
                "{\"enabled\":false}", TENANT);

        assertEquals(200, status(get("/api/v1/memory/items").header("Authorization", bearer())));

        MvcResult created = createItem("{\"kind\":\"fact\",\"content\":\"x\",\"importance\":1}");
        assertEquals(400, created.getResponse().getStatus(), raw(created));
        assertEquals("{\"error\":{\"code\":1000,\"details\":null,\"message\":\"memory is disabled\"}}",
                raw(created));

        MvcResult consolidated =
                perform(post("/api/v1/memory/consolidate").header("Authorization", bearer()));
        assertEquals(400, consolidated.getResponse().getStatus(), raw(consolidated));
        assertEquals("{\"error\":{\"code\":1000,\"details\":null,\"message\":\"memory is disabled\"}}",
                raw(consolidated));
    }

    // ══════════════════════════ 路由 / API-Key 策略 ══════════════════════════

    /**
     * 记忆整组要求 <b>full-access</b> Key——带 {@code chat} 的 scoped Key 也进不来。
     *
     * <p>理由在 Go 的 {@code routes_memory.go} 注释里：记忆空间属于<b>一个人</b>，
     * 而 scoped 集成 key 代表的是一个系统，不该继承某个人（或"系统合成用户"）的记忆。
     * 与 {@code /sessions/continue-stream} 的 {@code chat(fullAccess())} 是<b>有意</b>的差别。</p>
     */
    @Test
    void scopedApiKeyIsDeniedByFullAccessPolicy() throws Exception {
        String scoped = createApiKey("{\"name\":\"mem-scoped\",\"fullAccess\":false,"
                + "\"capabilities\":[\"chat\"]}");

        MvcResult r = perform(get("/api/v1/memory/settings").header("X-API-Key", scoped));
        assertEquals(403, r.getResponse().getStatus(), raw(r));
        assertEquals(com.ragagent.support.ContractJson.semantic("{\"error\":\"Forbidden: API key scope does not allow this operation\"}"), raw(r));

        String full = createApiKey("{\"name\":\"mem-full\",\"fullAccess\":true}");
        MvcResult ok = perform(get("/api/v1/memory/settings").header("X-API-Key", full));
        assertEquals(200, ok.getResponse().getStatus(), raw(ok));
    }

    /**
     * 16 条路由在策略表里的登记形态，逐条对照 Go 的
     * {@code g.apiKeyGroup(r.Group("/memory", g.Viewer()), apiKeyFullAccess())}：
     * 每一条都是 {@code {RequireFullAccess: true}} 且<b>不带任何能力</b>。
     */
    @Test
    void allMemoryRoutesAreRegisteredAsFullAccessOnly() {
        APIKeyRouteAuthorizer a = new APIKeyRouteAuthorizer();
        APIKeyRoutePolicies.registerAll(a);

        List<String[]> routes = List.of(
                new String[] {"GET", "/api/v1/memory/settings"},
                new String[] {"PUT", "/api/v1/memory/settings"},
                new String[] {"GET", "/api/v1/memory/items"},
                new String[] {"POST", "/api/v1/memory/items"},
                new String[] {"DELETE", "/api/v1/memory/items"},
                new String[] {"PUT", "/api/v1/memory/items/{id}"},
                new String[] {"DELETE", "/api/v1/memory/items/{id}"},
                new String[] {"POST", "/api/v1/memory/items/{id}/confirm"},
                new String[] {"POST", "/api/v1/memory/items/{id}/reject"},
                new String[] {"GET", "/api/v1/memory/topics"},
                new String[] {"DELETE", "/api/v1/memory/topics/{id}"},
                new String[] {"POST", "/api/v1/memory/topics/{id}/promote"},
                new String[] {"GET", "/api/v1/memory/documents"},
                new String[] {"DELETE", "/api/v1/memory/documents/{id}"},
                new String[] {"GET", "/api/v1/memory/export"},
                new String[] {"POST", "/api/v1/memory/consolidate"});

        for (String[] route : routes) {
            APIKeyRoutePolicy policy = a.lookup(route[0], route[1]);
            assertEquals(APIKeyRoutePolicy.fullAccess(), policy,
                    route[0] + " " + route[1] + " 必须是纯 full-access（无能力清单）");
        }
        assertEquals(16, routes.size(), "routes_memory.go 注册的路由数");
    }

    /** 上面那张表的**行为**验证：任何 scoped Key（含 chat / retrieve / ingest）都被门禁挡住。 */
    @Test
    void gateDeniesEveryScopedKeyOnEveryMemoryRoute() throws Exception {
        APIKeyRouteAuthorizer a = new APIKeyRouteAuthorizer();
        APIKeyRoutePolicies.registerAll(a);

        List<String> scopedCapabilities = List.of("chat", "retrieve", "ingest", "manage_kbs");
        for (String capability : scopedCapabilities) {
            TenantAPIKeyScope scoped =
                    new TenantAPIKeyScope(0L, "tenant", false, null, List.of(capability));
            assertTrue(!gateAllows(a, scoped, "GET", "/api/v1/memory/settings"),
                    capability + " 不该能读记忆设置");
            assertTrue(!gateAllows(a, scoped, "POST", "/api/v1/memory/consolidate"),
                    capability + " 不该能触发整理");
        }
        TenantAPIKeyScope full = new TenantAPIKeyScope(0L, "tenant", true, null, null);
        assertTrue(gateAllows(a, full, "GET", "/api/v1/memory/settings"), "full-access 必须放行");
        assertTrue(gateAllows(a, full, "POST", "/api/v1/memory/consolidate"),
                "full-access 必须放行");
    }

    /** 对照 Go 的 {@code runGate}：直接驱动 {@link APIKeyGateInterceptor}。 */
    private static boolean gateAllows(APIKeyRouteAuthorizer authorizer, TenantAPIKeyScope scope,
                                      String method, String pattern) throws Exception {
        com.ragagent.auth.apikey.domain.APIKeyScopeContext.set(scope);
        try {
            MockHttpServletRequest request = new MockHttpServletRequest(method, pattern);
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
            MockHttpServletResponse response = new MockHttpServletResponse();
            boolean allowed =
                    new APIKeyGateInterceptor(authorizer).preHandle(request, response, new Object());
            return allowed && response.getStatus() == 200;
        } finally {
            com.ragagent.auth.apikey.domain.APIKeyScopeContext.clear();
        }
    }

    // ══════════════════════════ 工具方法 ══════════════════════════

    private MvcResult createItem(String body) throws Exception {
        return perform(jsonBody(post("/api/v1/memory/items"), body)
                .header("Authorization", bearer()));
    }

    private String createdId(MvcResult result) throws Exception {
        String body = raw(result);
        // 创建条目是 201（§1.15）
        assertEquals(201, result.getResponse().getStatus(), body);
        Matcher m = Pattern.compile(
                "\"id\":\"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\"")
                .matcher(body);
        assertTrue(m.find(), "创建响应应含 id: " + body);
        return m.group(1);
    }

    /** 对照录制序里手工种进去的那一行（id 与 golden 里的一字不差）。 */
    private void seedTopic() {
        jdbc.update("INSERT INTO memory_topic_stats (id, tenant_id, subject_id, normalized_key, "
                        + "topic, aliases, hits, last_seen_at) "
                        + "VALUES (?, ?, ?, 'k8s-部署', 'K8s 部署', ?, 1, ?)",
                TOPIC_ID, TENANT, SUBJECT_ID,
                "[\"Kubernetes 部署\",\"k8s 部署\"]", "2026-09-18 05:22:46.690463+00");
    }

    private void seedDocAffinity() {
        jdbc.update("INSERT INTO memory_doc_affinity (id, tenant_id, subject_id, knowledge_id, "
                        + "knowledge_base_id, title, hits, last_used_at) "
                        + "VALUES (?, ?, ?, 'cccccccc-1111-2222-3333-444444444401', "
                        + "'dddddddd-1111-2222-3333-444444444401', '架构设计文档', 5, ?)",
                DOC_ID, TENANT, SUBJECT_ID, "2026-09-18 05:22:46.71525+00");
    }

    /** 建一把 API Key 并返回**明文**（响应里 {@code data.token} 只此一次）。 */
    private String createApiKey(String body) throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/tenants/" + TENANT + "/api-keys"), body)
                .header("Authorization", bearer()));
        assertEquals(201, r.getResponse().getStatus(), raw(r));
        Matcher m = TOKEN.matcher(raw(r));
        assertTrue(m.find(), "创建 API Key 的响应应含明文 token: " + raw(r));
        return m.group(1);
    }

    private MvcResult perform(MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder).andReturn();
    }

    private static MockHttpServletRequestBuilder jsonBody(MockHttpServletRequestBuilder builder,
                                                          String body) {
        return builder.contentType("application/json").content(body);
    }

    private int status(MockHttpServletRequestBuilder builder) throws Exception {
        return perform(builder).getResponse().getStatus();
    }

    private String bearer() throws Exception {
        MvcResult result = perform(jsonBody(post("/api/v1/auth/login"),
                "{\"email\":\"" + USER_EMAIL + "\",\"password\":\"Passw0rd!\"}"));
        Matcher m = TOKEN.matcher(raw(result));
        assertTrue(m.find(), "login 响应应含 token: " + raw(result));
        return "Bearer " + m.group(1);
    }

    /**
     * 按<b>原始字节</b>取响应体。
     *
     * <p>MockMvc 默认按 ISO-8859-1 解码，中文会出 mojibake——本项目所有含中文的
     * golden 比较都必须走这条（§9「中文 golden 比较必须按原始字节」）。</p>
     */
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

    private static String goldenMasked(String name) throws Exception {
        return mask(golden(name));
    }

    /** 两侧同掩码：UUID 值、时间戳。 */
    private static String mask(String s) {
        // PR4 语义比较入口：键序/转义归一后再掩码
        s = com.ragagent.support.ContractJson.semantic(s);
        String out = UUID_PATTERN.matcher(s).replaceAll("\"<uuid>\"");
        return TS_PATTERN.matcher(out).replaceAll("\"<ts>\"");
    }
}
