package com.ragagent.session.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 追问建议 HTTP 层的契约测试（波 1 G3：Ensure / Get / RecordEvent，对照 Go
 * {@code internal/handler/message_suggestion.go} 与 routes_chat.go L89-91）。
 *
 * <h2>期望值来源：Go 实录</h2>
 * <p>golden 脚本 {@code scripts/record-suggestion-golden.sh}：会话与消息经 API/psql 造，
 * 一条 ready 集合用 SQL 直插（生成路径依赖 LLM，非确定性——降级说明见
 * {@code MessageSuggestionService} 类注释）。</p>
 *
 * <h2>golden 钉住的契约</h2>
 * <ul>
 *   <li>writeError 的子串分派：gorm 404 → "suggestions not found"（消息不存在也落这）、
 *       会话 404 → "session not found"、业务 400 靠子串、其余 500 固定文案；</li>
 *   <li>Ensure 空 body 合法（Go 只看 ContentLength），畸形 JSON → 400 固定文案
 *       "invalid request body"；</li>
 *   <li>未配置 follow-ups → suppress("disabled")，questions=[]、generated_at 有值、
 *       error_code/model_id/agent_id 的空值省略形态与 Go 一致；</li>
 *   <li>RecordEvent 成功 204 无体；各失败分支的固定文案。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class MessageSuggestionContractTest {

    private static final String BCRYPT =
            "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK"; // Passw0rd!
    private static final long TENANT = 10002L;
    private static final String USER_ID = "11111111-2222-3333-4444-555555555501";
    private static final String USER_EMAIL = "suggestion-contract@weknora.test";
    private static final String UNKNOWN_ID = "11111111-2222-3333-4444-999999999999";

    private static final String U1 = "ccc00001-0000-0000-0000-000000000001";
    private static final String A1 = "ccc00001-0000-0000-0000-000000000002";
    private static final String A2 = "ccc00001-0000-0000-0000-000000000004";
    private static final String SET2 = "ddd00001-0000-0000-0000-000000000001";

    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern UUID_VALUE = Pattern.compile(
            "\"([A-Za-z_][A-Za-z0-9_]*)\":\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"");
    private static final Pattern TS_VALUE = Pattern.compile(
            "\"([A-Za-z_][A-Za-z0-9_]*)\":\"[2-9]\\d{3}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})\"");

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

    @BeforeEach
    void seed() throws Exception {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        jdbc.update("DELETE FROM sessions");
        jdbc.update("DELETE FROM messages");
        jdbc.update("DELETE FROM message_suggestion_sets");
        jdbc.update("DELETE FROM message_suggestion_events");

        Tenant tenant = new Tenant();
        tenant.setId(TENANT);
        tenant.setName("suggestion-contract-tenant");
        tenant.setStatus("active");
        tenantMapper.insert(tenant);

        User user = new User();
        user.setId(USER_ID);
        user.setUsername("sugowner");
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

        MvcResult r = perform(jsonBody(post("/api/v1/sessions"),
                "{\"title\":\"sug-golden-session\"}").header("Authorization", bearer));
        assertEquals(201, r.getResponse().getStatus(), raw(r));
        Matcher m = Pattern.compile(
                "\"id\":\"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\"")
                .matcher(raw(r));
        assertThat(m.find()).isTrue();
        sid = m.group(1);

        seedMessage(U1, "user", "如何泡好一杯茶？", "2026-09-18 19:30:00+08");
        seedMessage(A1, "assistant", "泡茶要先温杯。", "2026-09-18 19:30:05+08");
        seedMessage("ccc00001-0000-0000-0000-000000000003", "user", "第二问", "2026-09-18 19:31:00+08");
        seedMessage(A2, "assistant", "第二答", "2026-09-18 19:31:05+08");

        // ready 集合（config_hash/locale 与服务层推导对齐才能被 Get 命中）
        jdbc.update("INSERT INTO message_suggestion_sets (id, tenant_id, session_id, "
                + "assistant_message_id, agent_id, agent_tenant_id, placement, config_hash, "
                + "locale, status, allow_regenerate, suppression_reason, questions, "
                + "prompt_tokens, completion_tokens, latency_ms, error_code, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, '', 0, 'after_answer', 'no-agent-config', 'zh-CN', "
                + "'ready', false, '', "
                + "'[{\"id\":\"q1\",\"text\":\"冷泡茶要泡多久？\",\"source\":\"generated\"}]', "
                + "0, 0, 0, '', "
                + "CAST('2026-09-18 19:32:00+08' AS TIMESTAMP WITH TIME ZONE), "
                + "CAST('2026-09-18 19:32:00+08' AS TIMESTAMP WITH TIME ZONE))",
                SET2, TENANT, sid, A2);
    }

    private void seedMessage(String id, String role, String content, String ts) {
        // request_id 列宽 36：用 id 前 32 位拼前缀，避免超长
        jdbc.update("INSERT INTO messages (id, request_id, session_id, role, content, "
                        + "is_completed, created_at, updated_at) VALUES (?, ?, ?, ?, ?, true, "
                        + "CAST(? AS TIMESTAMP WITH TIME ZONE), CAST(? AS TIMESTAMP WITH TIME ZONE))",
                id, "req" + id.substring(4), sid, role, content, ts, ts);
    }

    // ══════════════════════════ Get ══════════════════════════

    @Test
    void getBeforeEnsureIsSuggestionsNotFound() throws Exception {
        MvcResult r = perform(get("/api/v1/sessions/" + sid + "/messages/" + A1 + "/suggestions")
                .header("Authorization", bearer));
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("sug-get-404.json"), raw(r));
    }

    @Test
    void getReadySetMatchesGo() throws Exception {
        MvcResult r = perform(get("/api/v1/sessions/" + sid + "/messages/" + A2 + "/suggestions")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("sug-get-ready.json")), mask(raw(r)));
    }

    // ══════════════════════════ Ensure ══════════════════════════

    /** 未配置 follow-ups → suppress("disabled")；再次 ensure 复用（不重新生成）。 */
    @Test
    void ensureSuppressesWhenDisabledAndReuses() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + sid + "/messages/" + A1
                + "/suggestions"), "").header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("sug-ensure.json")), mask(raw(r)));

        MvcResult again = perform(jsonBody(post("/api/v1/sessions/" + sid + "/messages/" + A1
                + "/suggestions"), "").header("Authorization", bearer));
        assertEquals(mask(golden("sug-ensure-again.json")), mask(raw(again)));
    }

    /** 空 JSON {} 与空 body 语义相同（Go 只看 ContentLength）。 */
    @Test
    void ensureWithEmptyObjectMatchesGo() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + sid + "/messages/" + A1
                + "/suggestions"), "{}").header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("sug-ensure-empty-obj.json")), mask(raw(r)));
    }

    @Test
    void ensureRegenerateNotAllowed() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + sid + "/messages/" + A1
                + "/suggestions"), "{\"regenerate\":true}").header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("sug-ensure-regen.json"), raw(r));
    }

    @Test
    void ensureOnUserMessageIsBadRequest() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + sid + "/messages/" + U1
                + "/suggestions"), "").header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("sug-ensure-user-msg.json"), raw(r));
    }

    /** 消息不存在：gorm NotFound 分支 → "suggestions not found"。 */
    @Test
    void ensureUnknownMessageIsSuggestionsNotFound() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + sid + "/messages/"
                + UNKNOWN_ID + "/suggestions"), "").header("Authorization", bearer));
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("sug-ensure-unknown-msg.json"), raw(r));
    }

    @Test
    void ensureUnknownSessionIsSessionNotFound() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + UNKNOWN_ID + "/messages/"
                + A1 + "/suggestions"), "").header("Authorization", bearer));
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("sug-ensure-unknown-session.json"), raw(r));
    }

    /** 畸形 JSON → 400 固定文案（不是解析器原文——与其他端点不同）。 */
    @Test
    void ensureBadJsonIsFixedMessage() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + sid + "/messages/" + A1
                + "/suggestions"), "not-json").header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("sug-ensure-bad-json.json"), raw(r));
    }

    /** ready 集合复用：ensure 直接返回既有结果（acquired=false）。 */
    @Test
    void ensureOnReadySetReuses() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + sid + "/messages/" + A2
                + "/suggestions"), "").header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("sug-ensure-a2.json")), mask(raw(r)));
    }

    // ══════════════════════════ RecordEvent ══════════════════════════

    @Test
    void impressionClickDismissAre204AndPersisted() throws Exception {
        MvcResult impression = perform(jsonBody(post("/api/v1/sessions/" + sid
                + "/suggestion-events"),
                "{\"suggestion_set_id\":\"" + SET2 + "\",\"event_type\":\"impression\"}")
                .header("Authorization", bearer));
        assertEquals(HttpStatus.NO_CONTENT.value(), impression.getResponse().getStatus(),
                raw(impression));
        assertEquals("", raw(impression));

        MvcResult click = perform(jsonBody(post("/api/v1/sessions/" + sid + "/suggestion-events"),
                "{\"suggestion_set_id\":\"" + SET2 + "\",\"question_id\":\"q1\",\"event_type\":\"click\"}")
                .header("Authorization", bearer));
        assertEquals(HttpStatus.NO_CONTENT.value(), click.getResponse().getStatus(), raw(click));

        MvcResult dismiss = perform(jsonBody(post("/api/v1/sessions/" + sid + "/suggestion-events"),
                "{\"suggestion_set_id\":\"" + SET2 + "\",\"event_type\":\"dismiss\"}")
                .header("Authorization", bearer));
        assertEquals(HttpStatus.NO_CONTENT.value(), dismiss.getResponse().getStatus(), raw(dismiss));

        assertEquals(3, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM message_suggestion_events WHERE session_id = ?",
                Integer.class, sid));
    }

    @Test
    void clickWithoutQuestionIdIsBadRequest() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + sid + "/suggestion-events"),
                "{\"suggestion_set_id\":\"" + SET2 + "\",\"event_type\":\"click\"}")
                .header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("sug-event-click-no-qid.json"), raw(r));
    }

    @Test
    void clickWithForeignQuestionIdIsBadRequest() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + sid + "/suggestion-events"),
                "{\"suggestion_set_id\":\"" + SET2 + "\",\"question_id\":\"nope\",\"event_type\":\"click\"}")
                .header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("sug-event-bad-qid.json"), raw(r));
    }

    @Test
    void invalidEventTypeIsBadRequest() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + sid + "/suggestion-events"),
                "{\"suggestion_set_id\":\"" + SET2 + "\",\"event_type\":\"hover\"}")
                .header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("sug-event-bad-type.json"), raw(r));
    }

    @Test
    void eventOnUnknownSetIsSuggestionsNotFound() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + sid + "/suggestion-events"),
                "{\"suggestion_set_id\":\"" + UNKNOWN_ID + "\",\"event_type\":\"impression\"}")
                .header("Authorization", bearer));
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("sug-event-unknown-set.json"), raw(r));
    }

    @Test
    void eventWithoutBodyIsInvalidRequest() throws Exception {
        MvcResult r = perform(post("/api/v1/sessions/" + sid + "/suggestion-events")
                .contentType("application/json").header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("sug-event-no-body.json"), raw(r));
    }

    @Test
    void eventWithMissingTypeIsInvalidRequest() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + sid + "/suggestion-events"),
                "{\"suggestion_set_id\":\"" + SET2 + "\"}").header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("sug-event-missing-type.json"), raw(r));
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

    /** 两侧同掩码：UUID 值（集合/消息/会话 id）+ 真实时间戳。 */
    private static String mask(String s) {
        String out = UUID_VALUE.matcher(s).replaceAll("\"$1\":\"<uuid>\"");
        return TS_VALUE.matcher(out).replaceAll("\"$1\":\"<ts>\"");
    }
}
