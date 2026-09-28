package com.ragagent.knowledge.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * W5α 共享 agent 收口批契约测试：KB list / knowledge batch / knowledge search 的
 * agent_id 分支（对照 golden 逐字节/掩码比对）。
 *
 * <p>golden 来源：Go dev server（localhost:8080，2026-09-21 录制，
 * scripts/record-w5s-golden.sh，21 条 w5s-*.json）。</p>
 *
 * <p>拓扑：租户 10005（源，UD=owner）把 AG_SEL/AG_ALL/AG_NONE 共享到 ORG；租户 10003
 * （UB=viewer）是组织成员，以 B 身份走 agent_id 分支。KB_B 是调用方自有库（own-grant
 * 后的 scope 拒绝文案）。掩码面：仅时间戳（id 全部固定种子）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class W5sSharedAgentContractTest {

    private static final String BCRYPT = "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK";
    private static final String ORG = "77777777-1111-4444-8888-0000000000a5";
    private static final String UB = "11111111-2222-3333-4444-555555555021";
    private static final String UD = "11111111-2222-3333-4444-555555555025";
    private static final String KB_S1 = "33333333-3333-3333-3333-33333333a501";
    private static final String KB_S2 = "33333333-3333-3333-3333-33333333a502";
    private static final String KB_S3 = "33333333-3333-3333-3333-33333333a503";
    private static final String KB_B = "33333333-3333-3333-3333-33333333a504";
    private static final String KB_NO = "33333333-3333-3333-3333-33333333a599";
    private static final String AG_SEL = "44444444-4444-4444-4444-44444444a501";
    private static final String AG_ALL = "44444444-4444-4444-4444-44444444a502";
    private static final String AG_NONE = "44444444-4444-4444-4444-44444444a503";
    private static final String K1 = "55555555-5555-5555-5555-55555555a511";
    private static final String K2 = "55555555-5555-5555-5555-55555555a512";
    private static final String K3 = "55555555-5555-5555-5555-55555555a513";
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
        jdbc.update("INSERT INTO knowledge_bases (id, name, tenant_id, type, description, creator_id, "
                + "chunking_config, embedding_model_id, summary_model_id, created_at, updated_at) VALUES "
                + "(?, 'w5s-kb-selected', 10005, 'document', 'selected kb', ?, '{}', '', '', "
                + "'2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'),"
                + "(?, 'w5s-kb-other', 10005, 'document', 'other kb', ?, '{}', '', '', "
                + "'2026-09-01 08:01:00+00', '2026-09-01 08:01:00+00'),"
                + "(?, 'w5s-kb-faq', 10005, 'faq', 'faq kb', ?, '{}', '', '', "
                + "'2026-09-01 08:02:00+00', '2026-09-01 08:02:00+00'),"
                + "(?, 'w5s-kb-caller', 10003, 'document', 'caller own kb', ?, '{}', '', '', "
                + "'2026-09-01 08:03:00+00', '2026-09-01 08:03:00+00')",
                KB_S1, UD, KB_S2, UD, KB_S3, UD, KB_B, UB);
        jdbc.update("INSERT INTO custom_agents (id, name, description, avatar, is_builtin, tenant_id, "
                + "created_by, config, created_at, updated_at) VALUES "
                + "(?, 'w5s-agent-sel', 'selected scope', '', false, 10005, ?, "
                + "'{\"agent_mode\":\"quick-answer\",\"model_id\":\"w5s-model\",\"rerank_model_id\":\"w5s-rerank\","
                + "\"kb_selection_mode\":\"selected\",\"knowledge_bases\":[\"" + KB_S1 + "\"],"
                + "\"web_search_enabled\":false}', '2026-09-01 08:10:00+00', '2026-09-01 08:10:00+00'),"
                + "(?, 'w5s-agent-all', 'all scope', '', false, 10005, ?, "
                + "'{\"agent_mode\":\"smart\",\"model_id\":\"w5s-model\",\"kb_selection_mode\":\"all\","
                + "\"web_search_enabled\":false}', '2026-09-01 08:11:00+00', '2026-09-01 08:11:00+00'),"
                + "(?, 'w5s-agent-none', 'empty scope', '', false, 10005, ?, "
                + "'{\"agent_mode\":\"quick-answer\",\"model_id\":\"w5s-model\",\"kb_selection_mode\":\"none\","
                + "\"web_search_enabled\":false}', '2026-09-01 08:12:00+00', '2026-09-01 08:12:00+00')",
                AG_SEL, UD, AG_ALL, UD, AG_NONE, UD);
        jdbc.update("INSERT INTO agent_shares (id, agent_id, organization_id, shared_by_user_id, "
                + "source_tenant_id, permission, created_at, updated_at) VALUES "
                + "('88888888-8888-4444-8888-0000000000a1', ?, ?, ?, 10005, 'viewer', "
                + "'2026-09-01 09:10:00+00', '2026-09-01 09:10:00+00'),"
                + "('88888888-8888-4444-8888-0000000000a2', ?, ?, ?, 10005, 'viewer', "
                + "'2026-09-01 09:11:00+00', '2026-09-01 09:11:00+00'),"
                + "('88888888-8888-4444-8888-0000000000a3', ?, ?, ?, 10005, 'viewer', "
                + "'2026-09-01 09:12:00+00', '2026-09-01 09:12:00+00')",
                AG_SEL, ORG, UD, AG_ALL, ORG, UD, AG_NONE, ORG, UD);
        jdbc.update("INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, source, "
                + "parse_status, summary_status, enable_status, file_name, file_type, file_size, file_hash, "
                + "custom_metadata, created_at, updated_at) VALUES "
                + "(?, 10005, ?, 'document', 'w5sdoc alpha 指南', 'manual', 'completed', 'none', 'enabled', "
                + "'w5s-alpha.txt', 'txt', 10, '0000000000000000000000000000a511', '{}', "
                + "'2026-09-01 10:01:00+00', '2026-09-01 10:01:00+00'),"
                + "(?, 10005, ?, 'document', 'w5sdoc beta 报表', 'manual', 'completed', 'none', 'enabled', "
                + "'w5s-beta.txt', 'txt', 10, '0000000000000000000000000000a512', '{}', "
                + "'2026-09-01 10:02:00+00', '2026-09-01 10:02:00+00'),"
                + "(?, 10005, ?, 'document', 'w5sdoc gamma 手册', 'manual', 'completed', 'none', 'enabled', "
                + "'w5s-gamma.txt', 'txt', 10, '0000000000000000000000000000a513', '{}', "
                + "'2026-09-01 10:03:00+00', '2026-09-01 10:03:00+00')",
                K1, KB_S1, K2, KB_S1, K3, KB_S2);
    }

    @Test
    void kbListAgentBranch() throws Exception {
        String B = "Bearer " + login("w5s-b@weknora.test");
        expect(200, getH("/api/v1/knowledge-bases?agent_id=" + AG_SEL, B), "w5s-kblist-sel.json");
        expect(200, getH("/api/v1/knowledge-bases?agent_id=" + AG_ALL, B), "w5s-kblist-all.json");
        expect(200, getH("/api/v1/knowledge-bases?agent_id=" + AG_NONE, B), "w5s-kblist-none.json");
        expect(403, getH("/api/v1/knowledge-bases?agent_id=" + MISSING, B), "w5s-kblist-missing.json");
        expect(400, getH("/api/v1/knowledge-bases?agent_id=" + AG_SEL + "&agent_source_tenant_id=abc", B),
                "w5s-kblist-badsource.json");
        expect(403, getH("/api/v1/knowledge-bases?agent_id=" + AG_SEL + "&agent_source_tenant_id=10003", B),
                "w5s-kblist-sourceself.json");
        expect(200, getH("/api/v1/knowledge-bases?agent_id=" + AG_SEL + "&agent_source_tenant_id=10005", B),
                "w5s-kblist-source-ok.json");
        expect(403, getH("/api/v1/knowledge-bases?agent_id=" + AG_SEL + "&agent_source_tenant_id=10004", B),
                "w5s-kblist-source-wrong.json");
    }

    @Test
    void knowledgeBatchAgentBranch() throws Exception {
        String B = "Bearer " + login("w5s-b@weknora.test");
        expect(200, getH("/api/v1/knowledge/batch?ids=" + K1 + "&ids=" + K2 + "&ids=" + K3
                + "&agent_id=" + AG_SEL, B), "w5s-batch-agent-ok.json");
        expect(200, getH("/api/v1/knowledge/batch?ids=" + K1 + "&agent_id=" + AG_NONE, B),
                "w5s-batch-agent-none.json");
        expect(403, getH("/api/v1/knowledge/batch?ids=" + K1 + "&agent_id=" + MISSING, B),
                "w5s-batch-agent-missing.json");
        expect(200, getH("/api/v1/knowledge/batch?ids=" + K1 + "&ids=" + K2 + "&ids=" + K3
                + "&agent_id=" + AG_ALL, B), "w5s-batch-agent-all.json");
        expect(200, getH("/api/v1/knowledge/batch?ids=" + K1 + "&kb_id=" + KB_S1 + "&agent_id=" + AG_SEL, B),
                "w5s-batch-agent-kb-ok.json");
        expect(403, getH("/api/v1/knowledge/batch?ids=" + K3 + "&kb_id=" + KB_S2 + "&agent_id=" + AG_SEL, B),
                "w5s-batch-agent-kb-denied.json");
        expect(403, getH("/api/v1/knowledge/batch?ids=" + K1 + "&kb_id=" + KB_B + "&agent_id=" + AG_SEL, B),
                "w5s-batch-agent-kb-own.json");
        expect(404, getH("/api/v1/knowledge/batch?ids=" + K1 + "&kb_id=" + KB_NO + "&agent_id=" + AG_SEL, B),
                "w5s-batch-agent-kb-missing.json");
    }

    @Test
    void knowledgeSearchAgentBranch() throws Exception {
        String B = "Bearer " + login("w5s-b@weknora.test");
        expect(200, getH("/api/v1/knowledge/search?keyword=w5sdoc&agent_id=" + AG_SEL, B),
                "w5s-search-agent-sel.json");
        expect(200, getH("/api/v1/knowledge/search?keyword=w5sdoc&agent_id=" + AG_ALL, B),
                "w5s-search-agent-all.json");
        expect(200, getH("/api/v1/knowledge/search?keyword=w5sdoc&agent_id=" + AG_NONE, B),
                "w5s-search-agent-none.json");
        expect(403, getH("/api/v1/knowledge/search?keyword=w5sdoc&agent_id=" + MISSING, B),
                "w5s-search-agent-missing.json");
        expect(200, getH("/api/v1/knowledge/search?keyword=w5sdoc&agent_id=" + AG_SEL
                + "&agent_source_tenant_id=10005", B), "w5s-search-agent-source.json");
        expect(200, getH("/api/v1/knowledge/search?keyword=zzzznope&agent_id=" + AG_SEL, B),
                "w5s-search-agent-nohit.json");
    }

    // ── 工具（与 OrganizationContractTest 同款） ──

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

    private void expect(int status, MockHttpServletRequestBuilder req, String goldenName) throws Exception {
        MvcResult r = mockMvc.perform(req).andReturn();
        assertEquals(status, r.getResponse().getStatus(), goldenName + " 状态码不符: " + raw(r));
        assertEquals(mask(golden(goldenName)), mask(raw(r)), goldenName);
    }

    private static MockHttpServletRequestBuilder getH(String url, String bearer) {
        MockHttpServletRequestBuilder b = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(url);
        return bearer == null ? b : b.header("Authorization", bearer);
    }

    private static String mask(String s) {
        return TS_PATTERN.matcher(s).replaceAll("<ts>");
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
