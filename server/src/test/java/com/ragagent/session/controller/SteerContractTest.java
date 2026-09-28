package com.ragagent.session.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.Map;
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
import com.ragagent.stream.StreamEvent;
import com.ragagent.stream.StreamManager;
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
 * steer 的契约测试（波 1 G4，对照 Go steer.go L461-810）。
 *
 * golden 分支（无活轮 + 校验错误）来自 scripts/record-steer-golden.sh；
 * 排队/注入路径 HTTP 层造不出活轮（live run 只能由 agent 引擎设置，波 4/5），
 * 用直种 streamManager 的单测覆盖（响应形态对照 Go handler 的 gin.H 字母序），
 * 引擎接线后补 e2e。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SteerContractTest {

    private static final String BCRYPT =
            "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK";
    private static final long TENANT = 10002L;
    private static final String USER_ID = "11111111-2222-3333-4444-555555555501";
    private static final String USER_EMAIL = "steer-contract@weknora.test";
    private static final String UNKNOWN_ID = "11111111-2222-3333-4444-999999999999";
    private static final String ASSISTANT_ID = "aaa00009-0000-0000-0000-000000000001";

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
    @Autowired
    private StreamManager streamManager;

    private String bearer;
    private String sid;

    @BeforeEach
    void seed() throws Exception {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        jdbc.update("DELETE FROM sessions");
        jdbc.update("DELETE FROM messages");

        Tenant tenant = new Tenant();
        tenant.setId(TENANT);
        tenant.setName("steer-contract-tenant");
        tenant.setStatus("active");
        tenantMapper.insert(tenant);

        User user = new User();
        user.setId(USER_ID);
        user.setUsername("steerowner");
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
        MvcResult r = perform(jsonBody(post("/api/v1/sessions"), "{\"title\":\"\"}")
                .header("Authorization", bearer));
        assertEquals(201, r.getResponse().getStatus(), raw(r));
        Matcher m = Pattern.compile(
                "\"id\":\"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\"")
                .matcher(raw(r));
        assertThat(m.find()).isTrue();
        sid = m.group(1);
    }

    private void seedLiveRun() throws Exception {
        jdbc.update("INSERT INTO messages (id, request_id, session_id, role, content, "
                        + "is_completed, created_at, updated_at) VALUES (?, 'req-1', ?, 'assistant', "
                        + "'生成中', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                ASSISTANT_ID, sid);
        streamManager.setLiveRun(sid, ASSISTANT_ID, "req-1");
    }

    private String steerIdOf(MvcResult queued) throws Exception {
        Matcher m = Pattern.compile("\"steer_id\":\"([^\"]+)\"").matcher(raw(queued));
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    // ════════ golden：无活轮分支 ════════

    @Test
    void listEmptyMatchesGo() throws Exception {
        MvcResult r = perform(get("/api/v1/sessions/" + sid + "/steer")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("st-list-empty.json"), raw(r));
    }

    @Test
    void deleteWithoutLiveRunIsGone() throws Exception {
        MvcResult r = perform(delete("/api/v1/sessions/" + sid + "/steer/"
                + "eee00001-0000-0000-0000-000000000001").header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("st-delete-gone.json"), raw(r));
    }

    @Test
    void steerWithoutLiveRunIsNewRun() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + sid + "/steer"),
                "{\"query\":\"第一问\"}").header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("st-post-new-run.json"), raw(r));
    }

    @Test
    void promoteWithoutLiveRunIsNewRun() throws Exception {
        MvcResult r = perform(post("/api/v1/sessions/" + sid + "/steer/"
                + "eee00001-0000-0000-0000-000000000001/inject")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("st-promote-new-run.json"), raw(r));
    }

    // ════════ golden：校验错误 ════════

    @Test
    void steerEmptyObjectFailsBinding() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + sid + "/steer"), "{}")
                .header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("st-post-empty.json"), raw(r));
    }

    @Test
    void steerEmptyQueryFailsBinding() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + sid + "/steer"), "{\"query\":\"\"}")
                .header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("st-post-empty-query.json"), raw(r));
    }

    @Test
    void steerBadJsonMatchesGoMessage() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + sid + "/steer"), "not-json")
                .header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("st-post-bad-json.json"), raw(r));
    }

    @Test
    void steerBadDeliveryMatchesGo() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + sid + "/steer"),
                "{\"query\":\"x\",\"delivery\":\"bogus\"}").header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("st-post-bad-delivery.json"), raw(r));
    }

    @Test
    void steerUnknownSessionMatchesGo() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + UNKNOWN_ID + "/steer"),
                "{\"query\":\"x\"}").header("Authorization", bearer));
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("st-post-unknown-session.json"), raw(r));
    }

    @Test
    void steerTooLongQueryMatchesGo() throws Exception {
        StringBuilder query = new StringBuilder();
        for (int i = 0; i < 10001; i++) {
            query.append("长");
        }
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + sid + "/steer"),
                "{\"query\":\"" + query + "\"}").header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("st-post-too-long.json"), raw(r));
    }

    // ════════ 排队路径（直种 live run；引擎接线后补 e2e） ════════

    /** 有活轮：排队成功，返回 assistant_message_id + delivery=after。 */
    @Test
    void steerWithLiveRunQueues() throws Exception {
        seedLiveRun();
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + sid + "/steer"),
                "{\"query\":\"第二问\"}").header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        String body = raw(r);
        assertThat(body).contains("\"status\":\"queued\"")
                .contains("\"delivery\":\"after\"")
                .contains("\"assistant_message_id\":\"" + ASSISTANT_ID + "\"")
                .contains("\"success\":true");
    }

    /** 列表返回未消费事件（overlay 恢复载荷）。 */
    @Test
    void listWithLiveRunReturnsPendingItems() throws Exception {
        seedLiveRun();
        perform(jsonBody(post("/api/v1/sessions/" + sid + "/steer"),
                "{\"query\":\"第二问\"}").header("Authorization", bearer));
        MvcResult r = perform(get("/api/v1/sessions/" + sid + "/steer")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        String body = raw(r);
        assertThat(body).contains("\"assistant_message_id\":\"" + ASSISTANT_ID + "\"")
                .contains("\"content\":\"第二问\"")
                .contains("\"delivery\":\"after\"")
                .contains("\"items\":");
    }

    /** 删除：未消费的事件被移除（removed=true）。 */
    @Test
    void deleteRemovesPendingEvent() throws Exception {
        seedLiveRun();
        MvcResult queued = perform(jsonBody(post("/api/v1/sessions/" + sid + "/steer"),
                "{\"query\":\"第二问\"}").header("Authorization", bearer));
        String steerId = steerIdOf(queued);

        MvcResult r = perform(delete("/api/v1/sessions/" + sid + "/steer/" + steerId)
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertThat(raw(r)).contains("\"removed\":true")
                .contains("\"status\":\"deleted\"")
                .contains("\"steer_id\":\"" + steerId + "\"");
    }

    /** promote：delivery 从 after 翻成 inject。 */
    @Test
    void promoteFlipsDeliveryToInject() throws Exception {
        seedLiveRun();
        MvcResult queued = perform(jsonBody(post("/api/v1/sessions/" + sid + "/steer"),
                "{\"query\":\"第二问\"}").header("Authorization", bearer));
        String steerId = steerIdOf(queued);

        MvcResult r = perform(post("/api/v1/sessions/" + sid + "/steer/" + steerId + "/inject")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertThat(raw(r)).contains("\"delivery\":\"inject\"")
                .contains("\"status\":\"queued\"")
                .contains("\"steer_id\":\"" + steerId + "\"");
    }

    /** 已被引擎注入（consumed=true）的事件：删除回 already_injected + removed=false。 */
    @Test
    void deleteConsumedEventReportsAlreadyInjected() throws Exception {
        seedLiveRun();
        StreamEvent evt = new StreamEvent("steer-1",
                com.ragagent.llm.domain.ResponseType.STEER, "已被注入", true);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("consumed", true);
        evt.setData(data);
        streamManager.appendSteerEvents(sid, ASSISTANT_ID, List.of(evt));

        MvcResult r = perform(delete("/api/v1/sessions/" + sid + "/steer/steer-1")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertThat(raw(r)).contains("\"removed\":false")
                .contains("\"status\":\"already_injected\"");
    }

    /** 深度守卫：10 条未消费后第 11 条被拒（400 固定文案）。 */
    @Test
    void steerQueueDepthGuardMatchesGo() throws Exception {
        seedLiveRun();
        for (int i = 0; i < 10; i++) {
            MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + sid + "/steer"),
                    "{\"query\":\"第" + i + "问\"}").header("Authorization", bearer));
            assertEquals(200, r.getResponse().getStatus(), raw(r));
        }
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + sid + "/steer"),
                "{\"query\":\"第十一问\"}").header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertThat(raw(r)).contains("too many queued messages for the running turn");
    }

    // ════════ 工具 ════════

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
}
