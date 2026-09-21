package com.ragagent.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ragagent.TestSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * W5α2 QA 共享 agent 批契约测试：knowledge-chat 的 resolveAgent 共享分支 pre-SSE
 * 错误面（对照 golden 逐字节/掩码比对）。
 *
 * <p>golden 来源：Go dev server（localhost:8080，2026-09-21 录制，
 * scripts/record-w5q-golden.sh，3 条 w5q-*.json）。SSE 正路径（共享解析通过 +
 * 执行租户切换 + stub LLM 全链路）由 ab-w5q.sh 双端对拍，不进本测试。</p>
 *
 * <p>拓扑：复用 w5s 的 10003（UB=viewer 调用方）/10005（源 UD）+ ORG；AG_QA 共享到
 * ORG；会话 SID 属于调用方（resolveAgent 在会话严格 owner 校验之后跑，会话必须先存在）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class W5qSharedAgentQaContractTest {

    private static final String BCRYPT = "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK";
    private static final String ORG = "77777777-1111-4444-8888-0000000000a5";
    private static final String UB = "11111111-2222-3333-4444-555555555021";
    private static final String UD = "11111111-2222-3333-4444-555555555025";
    private static final String AG_QA = "44444444-4444-4444-4444-44444444a511";
    private static final String W5Q_MODEL = "aaaa0000-0000-4000-8000-00000000a501";
    private static final String SID = "22222222-2222-4222-8222-22222222a501";
    private static final String MISSING = "99999999-9999-9999-9999-999999999999";

    private static final Pattern TS_PATTERN = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        jdbc.execute("INSERT INTO tenants (id, name, description, business, status) VALUES "
                + "(10003,'w5s-caller-tenant','','','active'),(10005,'w5s-source-tenant','','','active')");
        jdbc.update("INSERT INTO users (id, username, email, password_hash, tenant_id, is_active) VALUES "
                + "(?, 'w5sb','w5s-b@weknora.test',?,10003,true),(?, 'w5sd','w5s-d@weknora.test',?,10005,true)",
                UB, BCRYPT, UD, BCRYPT);
        jdbc.update("INSERT INTO tenant_members (user_id, tenant_id, role, status) VALUES "
                + "(?,10003,'owner','active'),(?,10005,'owner','active')", UB, UD);
        jdbc.update("INSERT INTO organizations (id, name, description, owner_id, owner_tenant_id, invite_code, "
                + "invite_code_validity_days, avatar, require_approval, searchable, member_limit, "
                + "created_at, updated_at) VALUES "
                + "(?, 'w5s-org', 'w5s share org', ?, 10005, 'w5sfixedcode0001', 7, '', false, false, 200, "
                + "'2026-09-01 09:00:00+00', '2026-09-01 09:00:00+00')", ORG, UD);
        jdbc.update("INSERT INTO organization_tenant_members (id, organization_id, tenant_id, role, "
                + "representative_user_id, joined_at, created_at, updated_at) VALUES "
                + "('66666666-6666-4444-8888-0000000000a5', ?, 10005, 'admin', ?, "
                + "'2026-09-01 09:00:00+00', '2026-09-01 09:00:00+00', '2026-09-01 09:00:00+00'),"
                + "('66666666-6666-4444-8888-0000000000a6', ?, 10003, 'viewer', ?, "
                + "'2026-09-01 09:00:00+00', '2026-09-01 09:00:00+00', '2026-09-01 09:00:00+00')",
                ORG, UD, ORG, UB);
        jdbc.update("INSERT INTO models (id, tenant_id, name, display_name, type, source, description, "
                + "parameters, is_default, status, created_at, updated_at) VALUES "
                + "(?, 10005, 'w5q-stub-llm', 'w5q stub llm', 'KnowledgeQA', 'remote', '', "
                + "'{\"base_url\":\"http://127.0.0.1:8181/v1\",\"provider\":\"openai\",\"api_key\":\"stub\"}', "
                + "false, 'active', '2026-09-01 08:20:00+00', '2026-09-01 08:20:00+00')", W5Q_MODEL);
        jdbc.update("INSERT INTO custom_agents (id, name, description, avatar, is_builtin, tenant_id, "
                + "created_by, config, created_at, updated_at) VALUES "
                + "(?, 'w5q-agent-shared', 'shared qa agent', '', false, 10005, ?, "
                + "'{\"agent_mode\":\"quick-answer\",\"model_id\":\"" + W5Q_MODEL + "\","
                + "\"kb_selection_mode\":\"none\",\"web_search_enabled\":false}', "
                + "'2026-09-01 08:10:00+00', '2026-09-01 08:10:00+00')", AG_QA, UD);
        jdbc.update("INSERT INTO agent_shares (id, agent_id, organization_id, shared_by_user_id, "
                + "source_tenant_id, permission, created_at, updated_at) VALUES "
                + "('88888888-8888-4444-8888-0000000000b1', ?, ?, ?, 10005, 'viewer', "
                + "'2026-09-01 09:10:00+00', '2026-09-01 09:10:00+00')", AG_QA, ORG, UD);
        jdbc.update("INSERT INTO sessions (id, tenant_id, title, description, user_id, created_at, updated_at) "
                + "VALUES (?, 10003, 'w5q-session', '', ?, "
                + "'2026-09-01 10:00:00+00', '2026-09-01 10:00:00+00')", SID, UB);
    }

    @Test
    void knowledgeChatSharedAgentErrorFace() throws Exception {
        String B = "Bearer " + login("w5s-b@weknora.test");
        expect(404, post("/api/v1/knowledge-chat/" + SID,
                "{\"query\":\"w5q 探针\",\"agent_id\":\"" + MISSING
                        + "\",\"agent_source_tenant_id\":10005,\"disable_title\":true}", B),
                "w5q-kch-source-missing.json");
        expect(404, post("/api/v1/knowledge-chat/" + SID,
                "{\"query\":\"w5q 探针\",\"agent_id\":\"" + AG_QA
                        + "\",\"agent_source_tenant_id\":10004,\"disable_title\":true}", B),
                "w5q-kch-source-wrong.json");
        expect(400, post("/api/v1/knowledge-chat/" + SID,
                "{\"query\":\"w5q 探针\",\"agent_id\":\"" + AG_QA
                        + "\",\"agent_source_tenant_id\":\"abc\",\"disable_title\":true}", B),
                "w5q-kch-badsource.json");
    }

    // ── 工具（与 W5sSharedAgentContractTest 同款） ──

    private String login(String email) throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Passw0rd!\"}"))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(), raw(result));
        Matcher m = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(raw(result));
        assertTrue(m.find(), "login 响应应含 token: " + raw(result));
        return m.group(1);
    }

    private void expect(int status, MockHttpServletRequestBuilder req, String goldenName) throws Exception {
        MvcResult r = mockMvc.perform(req).andReturn();
        assertEquals(status, r.getResponse().getStatus(), goldenName + " 状态码不符: " + raw(r));
        assertEquals(mask(golden(goldenName)), mask(raw(r)), goldenName);
    }

    private static MockHttpServletRequestBuilder post(String url, String body, String bearer) throws Exception {
        return MockMvcRequestBuilders.post(url)
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8));
    }

    private static String mask(String s) {
        return TS_PATTERN.matcher(s).replaceAll("<ts>");
    }

    private static String raw(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String golden(String name) throws Exception {
        return new String(new ClassPathResource("contracts/" + name).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
    }
}
