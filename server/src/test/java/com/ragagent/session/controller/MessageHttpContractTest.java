package com.ragagent.session.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ragagent.TestSchema;
import com.ragagent.apikey.filter.APIKeyRouteAuthorizer;
import com.ragagent.apikey.filter.APIKeyRoutePolicies;
import com.ragagent.apikey.filter.APIKeyRoutePolicy;
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
 * 消息 HTTP 层的契约测试（波 1 G2：load / search / chat-history-stats /
 * DELETE /messages + ClearSessionMessages，对照 Go {@code internal/handler/message.go}
 * 与 routes_chat.go RegisterMessageRoutes L28-31）。
 *
 * <h2>期望值来源：Go 实录</h2>
 * <p>golden 录制脚本 {@code scripts/record-message-golden.sh}：会话走 API 创建
 * （拿到真实 owner 范围），消息用 psql 直接插 dev PG（消息没有 HTTP 创建端点，
 * 由聊天管线产生——波 4/5）。H2 侧用同一组 id / 内容 / 时间戳播种。</p>
 *
 * <h2>golden 实测钉住的契约</h2>
 * <ul>
 *   <li><b>search 的 match_type 全是 "hybrid"</b>：关键词只命中 assistant 一侧时，
 *       partner 补对的 matchType 是空串，Go 的合并分支 {@code "keyword" != ""} →
 *       直接升 "hybrid"——不排除空串（§9 波 1 G2）；</li>
 *   <li>RRF 分值 1/61 = {@code 0.01639344262295082}（Go float64 最短表示，
 *       GoDoubleSerializer 逐字段）；keyword 模式单结果分值是 {@code 1} 不是 {@code 1.0}；</li>
 *   <li>{@code limit} 非整数**容错**回落 20；{@code before_time} 边界是严格小于；</li>
 *   <li>两个 404 文案不同：会话 "session not found" / 消息 "record not found"；</li>
 *   <li>清空空会话也是 200（幂等）；content 里的 {@code & < >} 走 HTML 转义。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class MessageHttpContractTest {

    private static final String BCRYPT =
            "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK"; // Passw0rd!
    private static final long TENANT = 10002L;
    private static final String USER_ID = "11111111-2222-3333-4444-555555555501";
    private static final String USER_EMAIL = "message-contract@weknora.test";
    private static final String UNKNOWN_ID = "11111111-2222-3333-4444-999999999999";

    /** 与录制脚本一致的固定 id（掩码后不比对，保持语义可读）。 */
    private static final String M1 = "aaa00001-0000-0000-0000-000000000001";
    private static final String M2 = "aaa00001-0000-0000-0000-000000000002";
    private static final String M3 = "aaa00001-0000-0000-0000-000000000003";
    private static final String M4 = "aaa00001-0000-0000-0000-000000000000";
    private static final String R1 = "bbb00001-0000-0000-0000-000000000001";
    private static final String R2 = "bbb00001-0000-0000-0000-000000000002";

    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern UUID_VALUE = Pattern.compile(
            "\"([a-z_]+)\":\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"");
    private static final Pattern TS_VALUE = Pattern.compile(
            "\"([a-z_]+)\":\"[2-9]\\d{3}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})\"");

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

    private String bearer;
    private String sid;

    // ══════════════════════════ 夹具 ══════════════════════════

    @BeforeEach
    void seed() throws Exception {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        jdbc.update("DELETE FROM sessions");
        jdbc.update("DELETE FROM messages");
        jdbc.update("DELETE FROM message_suggestion_sets");

        Tenant tenant = new Tenant();
        tenant.setId(TENANT);
        tenant.setName("message-contract-tenant");
        tenant.setStatus("active");
        tenantMapper.insert(tenant);

        User user = new User();
        user.setId(USER_ID);
        user.setUsername("msgowner");
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

        bearer = "Bearer " + login(USER_EMAIL);

        // 会话走真实 API（owner 范围 / user_id 由上下文推导——与录制一致）
        MvcResult r = perform(jsonBody(post("/api/v1/sessions"),
                "{\"title\":\"msg-golden-session\",\"description\":\"message golden\"}")
                .header("Authorization", bearer));
        assertEquals(201, r.getResponse().getStatus(), raw(r));
        Matcher m = Pattern.compile(
                "\"id\":\"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\"")
                .matcher(raw(r));
        assertThat(m.find()).isTrue();
        sid = m.group(1);

        // 4 条消息（M1/M2 同 request_id 且同 created_at，测 user-first 平序）
        seedMessage(M1, R1, "user", "如何泡好一杯茶？", "2026-09-18 17:30:00+08");
        seedMessage(M2, R1, "assistant", "泡茶要先温杯。a&b<c>d", "2026-09-18 17:30:00+08");
        seedMessage(M3, R2, "user", "咖啡和茶哪个提神？", "2026-09-18 17:31:00+08");
        seedMessage(M4, R2, "assistant", "咖啡因含量更高的是咖啡。", "2026-09-18 17:31:00+08");
    }

    private void seedMessage(String id, String requestId, String role, String content, String ts) {
        jdbc.update("INSERT INTO messages (id, request_id, session_id, role, content, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, "
                        + "CAST(? AS TIMESTAMP WITH TIME ZONE), CAST(? AS TIMESTAMP WITH TIME ZONE))",
                id, requestId, sid, role, content, ts, ts);
    }

    // ══════════════════════════ 1. load ══════════════════════════

    @Test
    void loadMatchesGo() throws Exception {
        MvcResult r = perform(get("/api/v1/messages/" + sid + "/load")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("msg-load.json")), mask(raw(r)));
        // 裸数组（无信封）：列表端点直接返回 JSON 数组
        assertThat(raw(r)).startsWith("[{").doesNotContain("\"success\"");
    }

    @Test
    void loadLimit2MatchesGo() throws Exception {
        MvcResult r = perform(get("/api/v1/messages/" + sid + "/load?limit=2")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("msg-load-limit2.json")), mask(raw(r)));
    }

    /** {@code limit=abc} 容错回落 20（Go 的 Atoi 失败 → 默认值），返回全部 4 条。 */
    @Test
    void loadLimitAbcIsTolerant() throws Exception {
        MvcResult r = perform(get("/api/v1/messages/" + sid + "/load?limit=abc")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("msg-load-limit-abc.json")), mask(raw(r)));
    }

    @Test
    void loadBeforeTimeMatchesGo() throws Exception {
        MvcResult r = perform(get("/api/v1/messages/" + sid + "/load")
                .queryParam("before_time", "2026-09-18T17:30:30+08:00")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("msg-load-before.json")), mask(raw(r)));
    }

    /** {@code before_time} 是严格小于：恰好等于边界时一条都不返回。 */
    @Test
    void loadBeforeTimeBoundaryIsEmpty() throws Exception {
        MvcResult r = perform(get("/api/v1/messages/" + sid + "/load")
                .queryParam("before_time", "2026-09-18T17:30:00+08:00")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("msg-load-before-empty.json"), raw(r));
    }

    @Test
    void loadBadBeforeTimeIsBadRequest() throws Exception {
        MvcResult r = perform(get("/api/v1/messages/" + sid + "/load?before_time=notatime")
                .header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("msg-load-before-bad.json"), raw(r));
    }

    @Test
    void loadBogusResourceUrlsIsBadRequest() throws Exception {
        MvcResult r = perform(get("/api/v1/messages/" + sid + "/load?resource_urls=bogus")
                .header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("msg-load-urls-bogus.json"), raw(r));
    }

    /** public 模式：本组数据没有 resource:// 引用，重写是 no-op（200 原样）。 */
    @Test
    void loadPublicModeMatchesGo() throws Exception {
        MvcResult r = perform(get("/api/v1/messages/" + sid + "/load?resource_urls=public")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("msg-load-urls-public.json")), mask(raw(r)));
    }

    @Test
    void loadUnknownSessionIsNotFound() throws Exception {
        MvcResult r = perform(get("/api/v1/messages/" + UNKNOWN_ID + "/load")
                .header("Authorization", bearer));
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("msg-load-unknown.json"), raw(r));
    }

    // ══════════════════════════ 2. search ══════════════════════════

    /**
     * hybrid：关键词只命中 M2（assistant），partner 补对 M1（user）后
     * matchType "keyword" != "" → 升 "hybrid"；RRF 分值 1/61。
     */
    @Test
    void searchHybridMatchesGo() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/messages/search"), "{\"query\":\"泡茶\"}")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("msg-search-hybrid.json")), mask(raw(r)));
    }

    @Test
    void searchHybridSecondPairMatchesGo() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/messages/search"), "{\"query\":\"提神\"}")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("msg-search-hybrid-two.json")), mask(raw(r)));
    }

    /** keyword 模式：线性分值（单结果 = 1，不是 1.0——GoDoubleSerializer）。 */
    @Test
    void searchKeywordMatchesGo() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/messages/search"),
                "{\"query\":\"泡茶\",\"mode\":\"keyword\"}").header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("msg-search-keyword.json")), mask(raw(r)));
    }

    @Test
    void searchNoHitMatchesGo() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/messages/search"),
                "{\"query\":\"no-such-content-xyz\"}").header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("msg-search-nohit.json")), mask(raw(r)));
    }

    @Test
    void searchSessionFilterMatchesGo() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/messages/search"),
                "{\"query\":\"泡茶\",\"session_ids\":[\"" + sid + "\"]}")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("msg-search-session-filter.json")), mask(raw(r)));
    }

    /** 空 query 在 binding 层被拒（required tag 的 validator 原文）。 */
    @Test
    void searchEmptyQueryIsValidatorMessage() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/messages/search"), "{\"query\":\"\"}")
                .header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("msg-search-empty-query.json"), raw(r));
    }

    @Test
    void searchWithoutBodyIsEof() throws Exception {
        MvcResult r = perform(post("/api/v1/messages/search")
                .contentType("application/json").header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("msg-search-no-body.json"), raw(r));
    }

    @Test
    void searchInvalidJsonMatchesGoMessage() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/messages/search"), "not-json")
                .header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("msg-search-bad-json.json"), raw(r));
    }

    // ══════════════════════════ 3. stats ══════════════════════════

    /** 未配置聊天历史 KB：全零统计（恒输出的三个键，无 omitempty）。 */
    @Test
    void chatHistoryStatsUnconfiguredMatchesGo() throws Exception {
        MvcResult r = perform(get("/api/v1/messages/chat-history-stats")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("msg-stats.json"), raw(r));
    }

    // ══════════════════════════ 4. 删除消息 ══════════════════════════

    @Test
    void deleteMessageMatchesGo() throws Exception {
        MvcResult r = perform(delete("/api/v1/messages/" + sid + "/" + M3)
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("msg-delete.json"), raw(r));

        MvcResult load = perform(get("/api/v1/messages/" + sid + "/load")
                .header("Authorization", bearer));
        assertEquals(mask(golden("msg-load-after-delete.json")), mask(raw(load)));
    }

    /** 删第二次：消息不存在 → gorm 原文 "record not found"（不是 "message not found"）。 */
    @Test
    void deleteTwiceIsRecordNotFound() throws Exception {
        perform(delete("/api/v1/messages/" + sid + "/" + M3).header("Authorization", bearer));
        MvcResult r = perform(delete("/api/v1/messages/" + sid + "/" + M3)
                .header("Authorization", bearer));
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("msg-delete-again.json"), raw(r));
    }

    @Test
    void deleteWithUnknownSessionIsSessionNotFound() throws Exception {
        MvcResult r = perform(delete("/api/v1/messages/" + UNKNOWN_ID + "/" + M1)
                .header("Authorization", bearer));
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("msg-delete-unknown-session.json"), raw(r));
    }

    // ══════════════════════════ 5. 清空 ══════════════════════════

    @Test
    void clearSessionMessagesMatchesGoAndIsIdempotent() throws Exception {
        MvcResult r = perform(delete("/api/v1/sessions/" + sid + "/messages")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("msg-clear.json"), raw(r));

        MvcResult load = perform(get("/api/v1/messages/" + sid + "/load")
                .header("Authorization", bearer));
        assertEquals(golden("msg-load-after-clear.json"), raw(load));

        // 清空空会话也是 200（幂等）
        MvcResult again = perform(delete("/api/v1/sessions/" + sid + "/messages")
                .header("Authorization", bearer));
        assertEquals(200, again.getResponse().getStatus(), raw(again));
        assertEquals(golden("msg-clear-again.json"), raw(again));

        // 软删：行还在
        assertEquals(4, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE session_id = ?", Integer.class, sid));
    }

    // ══════════════════════════ 6. API-Key 策略登记 ══════════════════════════

    /**
     * 4 条消息路由 + ClearSessionMessages 的策略：search/stats 要
     * {@code message_history(fullAccess())}，load/DELETE 与清空要
     * {@code chat(fullAccess())}（对照 Go 的 {@code messages.With(...)} 两组）。
     */
    @Test
    void messageRoutesAreRegisteredWithTheirPolicies() {
        APIKeyRouteAuthorizer a = new APIKeyRouteAuthorizer();
        APIKeyRoutePolicies.registerAll(a);

        APIKeyRoutePolicy history = APIKeyRoutePolicy.messageHistory(APIKeyRoutePolicy.fullAccess());
        APIKeyRoutePolicy chat = APIKeyRoutePolicy.chat(APIKeyRoutePolicy.fullAccess());

        assertEquals(history, a.lookup("POST", "/api/v1/messages/search"), "search");
        assertEquals(history, a.lookup("GET", "/api/v1/messages/chat-history-stats"), "stats");
        assertEquals(chat, a.lookup("GET", "/api/v1/messages/{session_id}/load"), "load");
        assertEquals(chat, a.lookup("DELETE", "/api/v1/messages/{session_id}/{id}"), "delete");
        assertEquals(chat, a.lookup("DELETE", "/api/v1/sessions/{id}/messages"), "clear");
    }

    // ══════════════════════════ 工具 ══════════════════════════

    private String login(String email) throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/auth/login"),
                "{\"email\":\"" + email + "\",\"password\":\"Passw0rd!\"}"));
        Matcher m = TOKEN.matcher(raw(r));
        assertThat(m.find()).as("login 响应应含 token: " + raw(r)).isTrue();
        return m.group(1);
    }

    private MvcResult perform(MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder).andReturn();
    }

    private static MockHttpServletRequestBuilder jsonBody(MockHttpServletRequestBuilder builder,
                                                          String body) {
        return builder.contentType("application/json").content(body);
    }

    /** 按**原始字节**取响应体（MockMvc 默认 ISO-8859-1 会让中文变成 mojibake，§9）。 */
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

    /** 两侧同掩码：UUID 值（消息/会话/request_id/user_id）+ 真实时间戳。 */
    private static String mask(String s) {
        String out = UUID_VALUE.matcher(s).replaceAll("\"$1\":\"<uuid>\"");
        return TS_VALUE.matcher(out).replaceAll("\"$1\":\"<ts>\"");
    }
}
