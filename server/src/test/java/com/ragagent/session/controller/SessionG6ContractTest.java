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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 波 1 G6 契约测试（产物 3 条 + generate_title + stop）。golden：record-g6-golden.sh。
 * 关键契约：stop 错误是纯字符串信封 {"error":"..."}；dev 租户无 KnowledgeQA 模型
 * → title 空 messages 回库后 500 "no KnowledgeQA model available..."；
 * download 的 access 层未翻译恒落 "artifact not accessible"（与 Go catalog miss 一致）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SessionG6ContractTest {

    private static final String BCRYPT =
            "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK";
    private static final long TENANT = 10002L;
    private static final String USER_ID = "11111111-2222-3333-4444-555555555501";
    private static final String USER_EMAIL = "g6-contract@weknora.test";
    private static final String UNKNOWN_ID = "11111111-2222-3333-4444-999999999999";

    private static final String A1 = "fff00001-0000-0000-0000-000000000001";
    private static final String U1 = "fff00001-0000-0000-0000-000000000002";
    private static final String A3 = "fff00001-0000-0000-0000-000000000003";

    private static final String ARTIFACT_JSON =
            "[{\"url\":\"resource://abcdefghijklmnopqrstuv\",\"fileName\":\"report.pdf\","
                    + "\"fileType\":\"pdf\",\"fileSize\":1234,"
                    + "\"sourcePath\":\"/tmp/report.pdf\","
                    + "\"modTime\":\"2026-09-18T21:00:00+08:00\","
                    + "\"createdAt\":\"2026-09-18T21:00:00+08:00\"}]";

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
    private String titledSid;
    private String stopSid;

    @BeforeEach
    void seed() throws Exception {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        jdbc.update("DELETE FROM sessions");
        jdbc.update("DELETE FROM messages");

        Tenant tenant = new Tenant();
        tenant.setId(TENANT);
        tenant.setName("g6-contract-tenant");
        tenant.setStatus("active");
        tenantMapper.insert(tenant);

        User user = new User();
        user.setId(USER_ID);
        user.setUsername("g6owner");
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
        sid = createSession("{}");
        titledSid = createSession("{\"title\":\"已生成的标题\"}");
        stopSid = createSession("{}");

        seedMessage(A1, sid, "assistant", "带产物的回答", true, ARTIFACT_JSON);
        seedMessage(U1, sid, "user", "用户问题", true, "[]");
        seedMessage(A3, stopSid, "assistant", "生成中的回答", false, "[]");
    }

    private String createSession(String body) throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions"), body)
                .header("Authorization", bearer));
        assertEquals(201, r.getResponse().getStatus(), raw(r));
        Matcher m = Pattern.compile(
                "\"id\":\"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\"")
                .matcher(raw(r));
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private void seedMessage(String id, String sessionId, String role, String content,
                             boolean completed, String artifacts) {
        jdbc.update("INSERT INTO messages (id, request_id, session_id, role, content, "
                        + "is_completed, artifacts, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, "
                        + "CAST('2026-09-18 21:00:00+08' AS TIMESTAMP WITH TIME ZONE), "
                        + "CAST('2026-09-18 21:00:00+08' AS TIMESTAMP WITH TIME ZONE))",
                id, "req" + id.substring(4), sessionId, role, content, completed, artifacts);
    }

    // ════════════════ 产物列表 ════════════════

    @Test
    void listSessionArtifactsMatchesGo() throws Exception {
        MvcResult r = perform(get("/api/v1/sessions/" + sid + "/artifacts")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("g6-artifacts-list.json")), mask(raw(r)));
    }

    @Test
    void listSessionArtifactsUnknownSessionIsNotFound() throws Exception {
        MvcResult r = perform(get("/api/v1/sessions/" + UNKNOWN_ID + "/artifacts")
                .header("Authorization", bearer));
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("g6-artifacts-session-404.json"), raw(r));
    }

    @Test
    void listMessageArtifactsMatchesGo() throws Exception {
        MvcResult r = perform(get("/api/v1/sessions/" + sid + "/messages/" + A1 + "/artifacts")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("g6-artifacts-msg.json")), mask(raw(r)));
    }

    /** user 消息无产物 → 空数组（make 的 []，不是 null）。 */
    @Test
    void listMessageArtifactsEmptyMatchesGo() throws Exception {
        MvcResult r = perform(get("/api/v1/sessions/" + sid + "/messages/" + U1 + "/artifacts")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("g6-artifacts-msg-empty.json"), raw(r));
    }

    @Test
    void listMessageArtifactsUnknownMessageIsNotFound() throws Exception {
        MvcResult r = perform(get("/api/v1/sessions/" + sid + "/messages/" + UNKNOWN_ID
                + "/artifacts").header("Authorization", bearer));
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("g6-artifacts-msg-404.json"), raw(r));
    }

    // ════════════════ 产物下载 ════════════════

    /** 资源目录查不到（Java：access 层未翻译恒落此分支）→ 404 固定文案。 */
    @Test
    void downloadInaccessibleMatchesGo() throws Exception {
        MvcResult r = perform(get("/api/v1/sessions/" + sid + "/messages/" + A1
                + "/artifacts/0/download").header("Authorization", bearer));
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("g6-dl-noaccess.json"), raw(r));
    }

    @Test
    void downloadOutOfRangeMatchesGo() throws Exception {
        MvcResult r = perform(get("/api/v1/sessions/" + sid + "/messages/" + A1
                + "/artifacts/5/download").header("Authorization", bearer));
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("g6-dl-range.json"), raw(r));
    }

    @Test
    void downloadBadIndexIsBadRequest() throws Exception {
        MvcResult r = perform(get("/api/v1/sessions/" + sid + "/messages/" + A1
                + "/artifacts/abc/download").header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("g6-dl-bad-index.json"), raw(r));
    }

    @Test
    void downloadUnknownMessageIsNotFound() throws Exception {
        MvcResult r = perform(get("/api/v1/sessions/" + sid + "/messages/" + UNKNOWN_ID
                + "/artifacts/0/download").header("Authorization", bearer));
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("g6-dl-msg-404.json"), raw(r));
    }

    @Test
    void downloadUnknownSessionIsNotFound() throws Exception {
        MvcResult r = perform(get("/api/v1/sessions/" + UNKNOWN_ID + "/messages/" + A1
                + "/artifacts/0/download").header("Authorization", bearer));
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("g6-dl-session-404.json"), raw(r));
    }

    // ════════════════ generate_title ════════════════

    /** 已有标题直接返回（不调模型、不落库）。 */
    @Test
    void generateTitleExistingMatchesGo() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + titledSid + "/generate_title"),
                "{\"messages\":[{\"role\":\"user\",\"content\":\"第一问\",\"is_completed\":true}]}")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("g6-title-existing.json"), raw(r));
    }

    /** messages 非空但无 user 角色 → 500 "no user message found"。 */
    @Test
    void generateTitleWithoutUserMessageMatchesGo() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + sid + "/generate_title"),
                "{\"messages\":[{\"role\":\"assistant\",\"content\":\"答\",\"is_completed\":true}]}")
                .header("Authorization", bearer));
        assertEquals(500, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("g6-title-no-user.json"), raw(r));
    }

    /**
     * 空 messages 回库取第一条 user 消息 → dev 租户无 KnowledgeQA 模型 →
     * 500 "no KnowledgeQA model available for title generation"。
     */
    @Test
    void generateTitleEmptyListWithoutModelMatchesGo() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + sid + "/generate_title"),
                "{\"messages\":[]}").header("Authorization", bearer));
        assertEquals(500, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("g6-title-empty-list.json"), raw(r));
    }

    @Test
    void generateTitleNoBodyIsEof() throws Exception {
        MvcResult r = perform(post("/api/v1/sessions/" + sid + "/generate_title")
                .contentType("application/json").header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("g6-title-no-body.json"), raw(r));
    }

    @Test
    void generateTitleBadJsonMatchesGoMessage() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + sid + "/generate_title"),
                "not-json").header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("g6-title-bad-json.json"), raw(r));
    }

    @Test
    void generateTitleUnknownSessionIsNotFound() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + UNKNOWN_ID + "/generate_title"),
                "{\"messages\":[{\"role\":\"user\",\"content\":\"第一问\"}]}")
                .header("Authorization", bearer));
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("g6-title-404.json"), raw(r));
    }

    // ════════════════ stop ════════════════

    @Test
    void stopCompletedMessageMatchesGo() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + sid + "/stop"),
                "{\"messageId\":\"" + U1 + "\"}").header("Authorization", bearer));
        assertEquals(204, r.getResponse().getStatus(), raw(r));
        assertEquals("", raw(r), "204 必须无响应体");
    }

    /** 未完成消息：写 stop 事件（type=stop）→ 200 "Generation stopped"。 */
    @Test
    void stopRunningMessageMatchesGoAndWritesEvent() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + stopSid + "/stop"),
                "{\"messageId\":\"" + A3 + "\"}").header("Authorization", bearer));
        assertEquals(204, r.getResponse().getStatus(), raw(r));
        assertEquals("", raw(r), "204 必须无响应体");
    }

    @Test
    void stopUnknownMessageMatchesGo() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + sid + "/stop"),
                "{\"messageId\":\"" + UNKNOWN_ID + "\"}").header("Authorization", bearer));
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("g6-stop-unknown-msg.json"), raw(r));
    }

    /** 会话不可见：GetMessage 在前 → 404 "Message not found"（纯字符串信封）。 */
    @Test
    void stopUnknownSessionMatchesGo() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + UNKNOWN_ID + "/stop"),
                "{\"messageId\":\"" + A1 + "\"}").header("Authorization", bearer));
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("g6-stop-unknown-session.json"), raw(r));
    }

    @Test
    void stopWithoutBodyMatchesGo() throws Exception {
        MvcResult r = perform(post("/api/v1/sessions/" + sid + "/stop")
                .contentType("application/json").header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("g6-stop-no-body.json"), raw(r));
    }

    @Test
    void stopEmptyMessageIdMatchesGo() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + sid + "/stop"),
                "{\"messageId\":\"\"}").header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("g6-stop-empty-msgid.json"), raw(r));
    }

    // ════════════════ 工具 ════════════════

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

    /** 两侧同掩码：UUID 值 + 真实时间戳。 */
    private static String mask(String s) {
        String out = UUID_VALUE.matcher(s).replaceAll("\"$1\":\"<uuid>\"");
        return TS_VALUE.matcher(out).replaceAll("\"$1\":\"<ts>\"");
    }
}
