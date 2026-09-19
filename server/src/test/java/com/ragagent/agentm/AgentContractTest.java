package com.ragagent.agentm;

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

/**
 * 波 3 agents 批契约测试：agents CRUD 家族（8 条路由）+ initialization 三条
 * （对照 golden 逐字节/掩码比对）。
 *
 * golden 来源：Go dev server（localhost:8080，2026-09-20 录制，
 * scripts/record-ag-golden.sh，43 条 ag-*.json + 17 条 init-*.json）。
 *
 * 场景顺序严格复刻录制脚本（同请求序列有状态依赖）：鉴权 → 静态面 → 空列表 →
 * 内建 get → CRUD → creator 筛选 → update → delete → copy → 内建 PUT（落 DB 行）→
 * suggested-questions → initialization。
 *
 * 掩码面：uuid + 时间戳（create/copy/initialize 的动态 id、kb/model 行时间戳、
 * 内建 PUT 落库行的 created_at）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AgentContractTest {

    private static final String BCRYPT = "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK"; // Passw0rd!
    private static final String AGU = "a0000000-0000-0000-0000-000000000001";
    private static final String AGV = "a0000000-0000-0000-0000-000000000002";
    private static final String KB_INIT = "a0000000-0000-0000-0000-000000000101";
    private static final String KB_FAQ = "a0000000-0000-0000-0000-000000000102";
    private static final String KN_FAQ = "a0000000-0000-0000-0000-000000000201";
    private static final String CH_FAQ = "a0000000-0000-0000-0000-000000000301";
    private static final String MD_LLM = "a0000000-0000-0000-0000-000000000401";
    private static final String MISSING = "99999999-9999-9999-9999-999999999999";

    private static final Pattern TS_PATTERN = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})");
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private String token;
    private String bearer;
    private String agEmpty;
    private String agFull;
    private String agKbref;
    private String agCopy;

    private static final String CT = MediaType.APPLICATION_JSON_VALUE;

    @BeforeEach
    void seed() throws Exception {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        jdbc.execute("INSERT INTO tenants (id, name, description, business, status) VALUES "
                + "(10005, 'ag-batch-tenant', '', '', 'active')");
        jdbc.update("INSERT INTO users (id, username, email, password_hash, tenant_id, is_active) VALUES "
                + "(?, 'agbatch', 'ag-batch@weknora.test', ?, 10005, true),"
                + "(?, 'agviewer', 'ag-batch-viewer@weknora.test', ?, 10005, true)",
                AGU, BCRYPT, AGV, BCRYPT);
        jdbc.update("INSERT INTO tenant_members (user_id, tenant_id, role, status) VALUES "
                + "(?, 10005, 'owner', 'active'), (?, 10005, 'viewer', 'active')", AGU, AGV);
        jdbc.update("INSERT INTO knowledge_bases (id, name, tenant_id, type, description, creator_id, "
                + "chunking_config, embedding_model_id, summary_model_id, created_at, updated_at) VALUES "
                + "(?, 'ag-init-kb', 10005, 'document', 'kb for initialization batch', ?, '{}', '', '', "
                + "'2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'),"
                + "(?, 'ag-faq-kb', 10005, 'faq', 'kb for suggested questions', ?, '{}', '', '', "
                + "'2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00')",
                KB_INIT, AGU, KB_FAQ, AGU);
        jdbc.update("INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, "
                + "description, source, parse_status, enable_status) VALUES "
                + "(?, 10005, ?, 'faq', 'faq-knowledge', '', 'faq', 'completed', 'enabled')",
                KN_FAQ, KB_FAQ);
        String faqMeta = "{\"standard_question\":\"怎么 绑定 手机？\",\"answers\":"
                + "[\"进入设置，选择设备，点击绑定。\"],\"answer_strategy\":\"all\",\"version\":1,"
                + "\"source\":\"faq\"}";
        jdbc.update("INSERT INTO chunks (id, seq_id, tenant_id, knowledge_id, knowledge_base_id, "
                + "content, chunk_type, is_enabled, flags, status, chunk_index, start_at, end_at, "
                + "metadata, created_at, updated_at) VALUES "
                + "(?, 980001, 10005, ?, ?, 'Q: 怎么 绑定 手机？\n', 'faq', TRUE, 1, 2, 0, 0, 0, ?, "
                + "'2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00')",
                CH_FAQ, KN_FAQ, KB_FAQ, faqMeta);
        jdbc.update("INSERT INTO models (id, tenant_id, type, name, source, description, parameters, "
                + "is_default, status, created_at, updated_at) VALUES "
                + "(?, 10005, 'KnowledgeQA', 'ag-fixed-llm', 'remote', 'LLM Model for Knowledge QA', "
                + "'{}', FALSE, 'active', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00')",
                MD_LLM);
        token = login("ag-batch@weknora.test");
        bearer = "Bearer " + token;
    }

    @Test
    void unauthorized() throws Exception {
        assertGolden(getH("/api/v1/agents", null), 401, "ag-noauth.json");
        assertGolden(getH("/api/v1/agents", "Bearer garbage.token.here"), 401, "ag-badtoken.json");
    }

    @Test
    void agentAndInitializationFlow() throws Exception {
        // ── 1) 静态面 ──
        assertGolden(getH("/api/v1/agents/placeholders", bearer), 200, "ag-placeholders.json");
        assertGolden(getH("/api/v1/agents/type-presets", bearer), 200, "ag-type-presets.json");

        // ── 2) 空列表（只含内建 agent；disabled_own_agent_ids = []）──
        assertGolden(getH("/api/v1/agents", bearer), 200, "ag-list-empty.json");

        // ── 3) 内建 get + 404 家族 ──
        assertGolden(getH("/api/v1/agents/builtin-quick-answer", bearer), 200,
                "ag-get-builtin-qa.json");
        assertGolden(getH("/api/v1/agents/builtin-nope", bearer), 404, "ag-get-builtin-nope.json");
        assertGolden(getH("/api/v1/agents/" + MISSING, bearer), 404, "ag-get-missing.json");

        // ── 4) create 家族 ──
        MvcResult r = expect(201, postH("/api/v1/agents", bearer,
                "{\"name\":\"ag-empty\",\"description\":\"empty config agent\",\"config\":{}}"),
                "ag-create-empty.json");
        agEmpty = jsonPath(r, "data.id");
        assertGolden(getH("/api/v1/agents/" + agEmpty, bearer), 200, "ag-get-created.json");

        r = expect(201, postH("/api/v1/agents", bearer, FULL_CONFIG), "ag-create-full.json");
        agFull = jsonPath(r, "data.id");

        r = expect(201, postH("/api/v1/agents", bearer, "{\"name\":\"ag-kbref\","
                + "\"description\":\"kb reference agent\",\"config\":{\"agent_mode\":\"quick-answer\","
                + "\"kb_selection_mode\":\"selected\",\"knowledge_bases\":[\"" + KB_FAQ + "\"]}}"),
                "ag-create-kbref.json");
        agKbref = jsonPath(r, "data.id");

        assertGolden(postH("/api/v1/agents", bearer, "{\"description\":\"no name\"}"),
                400, "ag-create-missing-name.json");
        assertGolden(postH("/api/v1/agents", bearer, "not-json"), 400, "ag-create-badbody.json");
        assertGolden(postH("/api/v1/agents", bearer, "{\"name\":\"   \"}"),
                400, "ag-create-blank-name.json");
        assertGolden(postH("/api/v1/agents", bearer,
                "{\"name\":\"ag-bad-count\",\"config\":{\"question_suggestions\":{\"starters\":"
                + "{\"enabled\":true,\"mode\":\"curated\",\"count\":9},\"follow_ups\":{\"enabled\":false}}}}"),
                400, "ag-create-bad-starters-count.json");
        assertGolden(postH("/api/v1/agents", bearer,
                "{\"name\":\"ag-bad-mode\",\"config\":{\"question_suggestions\":{\"starters\":"
                + "{\"enabled\":true,\"mode\":\"nonsense\",\"count\":2},\"follow_ups\":{\"enabled\":false}}}}"),
                400, "ag-create-bad-starter-mode.json");
        assertGolden(postH("/api/v1/agents", bearer,
                "{\"name\":\"ag-bad-item\",\"config\":{\"question_suggestions\":{\"starters\":"
                + "{\"enabled\":true,\"mode\":\"curated\",\"items\":[\"  \"],\"count\":1},"
                + "\"follow_ups\":{\"enabled\":false}}}}"),
                400, "ag-create-empty-starter-item.json");
        assertGolden(postH("/api/v1/agents", bearer,
                "{\"name\":\"ag-bad-sandbox\",\"config\":{\"sandbox_config_id\":\"no-such-sandbox\"}}"),
                400, "ag-create-bad-sandbox.json");

        // ── 5) creator 筛选 ──
        assertGolden(getH("/api/v1/agents?creator=mine", bearer), 200, "ag-list-mine.json");
        assertGolden(getH("/api/v1/agents?creator=others", bearer), 200, "ag-list-others.json");
        assertGolden(getH("/api/v1/agents?creator=nonsense", bearer), 200, "ag-list-bogus.json");

        // ── 6) update 家族 ──
        assertGolden(putH("/api/v1/agents/" + agEmpty, bearer,
                "{\"name\":\"ag-empty-renamed\",\"description\":\"renamed\",\"config\":"
                + "{\"agent_mode\":\"quick-answer\",\"faq_priority_enabled\":true}}"),
                200, "ag-update.json");
        assertGolden(putH("/api/v1/agents/" + agEmpty, bearer, "{\"name\":\"\"}"),
                400, "ag-update-blank-name.json");
        assertGolden(putH("/api/v1/agents/" + MISSING, bearer, "{\"name\":\"ghost\"}"),
                404, "ag-update-missing.json");

        // ── 7) delete 家族 ──
        assertGolden(delH("/api/v1/agents/" + agKbref, bearer), 200, "ag-delete.json");
        assertGolden(delH("/api/v1/agents/" + agKbref, bearer), 404, "ag-delete-again.json");
        assertGolden(delH("/api/v1/agents/builtin-quick-answer", bearer), 403,
                "ag-delete-builtin.json");

        // ── 8) copy 家族 ──
        r = expect(201, postH("/api/v1/agents/" + agFull + "/copy", bearer, null), "ag-copy.json");
        agCopy = jsonPath(r, "data.id");
        assertGolden(getH("/api/v1/agents/" + agCopy, bearer), 200, "ag-get-copy.json");
        assertGolden(postH("/api/v1/agents/" + MISSING + "/copy", bearer, null), 404,
                "ag-copy-missing.json");

        // ── 9) 内建 PUT（落 DB 行）+ 回读 + 终态列表 ──
        assertGolden(putH("/api/v1/agents/builtin-quick-answer", bearer,
                "{\"name\":\"ignored\",\"description\":\"ignored\",\"config\":{\"agent_mode\":"
                + "\"quick-answer\",\"system_prompt_id\":\"default_kb\",\"temperature\":0.3}}"),
                200, "ag-put-builtin.json");
        assertGolden(getH("/api/v1/agents/builtin-quick-answer", bearer), 200,
                "ag-get-builtin-qa-after.json");
        assertGolden(getH("/api/v1/agents", bearer), 200, "ag-list-final.json");

        // ── 10) suggested-questions 家族 ──
        assertGolden(getH("/api/v1/agents/" + agFull + "/suggested-questions", null), 401,
                "ag-sq-noauth.json");
        assertGolden(getH("/api/v1/agents/" + MISSING + "/suggested-questions", bearer), 404,
                "ag-sq-missing.json");
        assertGolden(getH("/api/v1/agents/" + agFull + "/suggested-questions", bearer), 200,
                "ag-sq-curated.json");
        assertGolden(getH("/api/v1/agents/" + agFull + "/suggested-questions?limit=1", bearer),
                200, "ag-sq-curated-limit1.json");
        assertGolden(getH("/api/v1/agents/" + agFull + "/suggested-questions?limit=1000", bearer),
                200, "ag-sq-curated-cap.json");
        assertGolden(getH("/api/v1/agents/" + agEmpty + "/suggested-questions", bearer), 200,
                "ag-sq-default.json");
        assertGolden(getH("/api/v1/agents/" + agFull + "/suggested-questions?tag_scopes=not-json",
                bearer), 400, "ag-sq-badtagscopes.json");
        assertGolden(getH("/api/v1/agents/" + agEmpty + "/suggested-questions?knowledge_base_ids="
                + KB_FAQ, bearer), 200, "ag-sq-faq.json");
        assertGolden(getH("/api/v1/agents/" + agEmpty + "/suggested-questions?knowledge_ids="
                + KN_FAQ, bearer), 200, "ag-sq-faq-knowledge.json");

        // ── 11) initialization ──
        assertGolden(getH("/api/v1/initialization/config/" + KB_INIT, bearer), 200,
                "init-get-config.json");
        assertGolden(getH("/api/v1/initialization/config/" + MISSING, bearer), 404,
                "init-get-config-404.json");
        assertGolden(getH("/api/v1/initialization/config/" + KB_INIT, null), 401,
                "init-get-config-noauth.json");

        assertGolden(postH("/api/v1/initialization/initialize/" + KB_INIT, bearer,
                INIT_REQUEST), 200, "init-post-initialize.json");
        assertGolden(postH("/api/v1/initialization/initialize/" + MISSING, bearer,
                INIT_REQUEST), 404, "init-post-initialize-404.json");
        assertGolden(postH("/api/v1/initialization/initialize/" + KB_INIT, bearer, null),
                400, "init-post-initialize-empty.json");
        assertGolden(postH("/api/v1/initialization/initialize/" + KB_INIT, bearer,
                "{\"llm\":{\"source\":\"remote\",\"modelName\":\"m\"},"
                + "\"embedding\":{\"source\":\"remote\",\"modelName\":\"e\"},"
                + "\"documentSplitting\":{\"chunkSize\":50,\"chunkOverlap\":50,\"separators\":[\"\\n\\n\"]}}"),
                400, "init-post-initialize-chunksize.json");
        assertGolden(postH("/api/v1/initialization/initialize/" + KB_INIT, bearer,
                "{\"llm\":{\"source\":\"remote\",\"modelName\":\"m\",\"baseUrl\":\"http://127.0.0.1:11434\"},"
                + "\"embedding\":{\"source\":\"remote\",\"modelName\":\"e\"},"
                + "\"documentSplitting\":{\"chunkSize\":500,\"chunkOverlap\":50,\"separators\":[\"\\n\\n\"]}}"),
                400, "init-post-initialize-ssrf.json");
        assertGolden(postH("/api/v1/initialization/initialize/" + KB_INIT, bearer,
                "{\"llm\":{\"source\":\"remote\",\"modelName\":\"m\"},"
                + "\"embedding\":{\"source\":\"remote\",\"modelName\":\"e\"},"
                + "\"rerank\":{\"enabled\":true,\"modelName\":\"rr\"},"
                + "\"documentSplitting\":{\"chunkSize\":500,\"chunkOverlap\":50,\"separators\":[\"\\n\\n\"]}}"),
                400, "init-post-initialize-rerank.json");

        assertGolden(getH("/api/v1/initialization/config/" + KB_INIT, bearer), 200,
                "init-get-config-after.json");

        // 种文档行（hasFiles=true）
        jdbc.update("INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, "
                + "description, source, parse_status, enable_status) VALUES "
                + "(?, 10005, ?, 'document', 'has-files-doc', '', 'manual', 'completed', 'enabled')",
                "a0000000-0000-0000-0000-000000000202", KB_INIT);
        assertGolden(getH("/api/v1/initialization/config/" + KB_INIT, bearer), 200,
                "init-get-config-hasfiles.json");

        assertGolden(putH("/api/v1/initialization/config/" + KB_INIT, bearer,
                "{\"llmModelId\":\"" + MD_LLM + "\",\"documentSplitting\":{\"chunkSize\":800,"
                + "\"chunkOverlap\":100,\"separators\":[\"\\n\\n\",\"。\"]},\"questionGeneration\":"
                + "{\"enabled\":true,\"questionCount\":99,\"customInstructions\":\"  gen  \"}}"),
                200, "init-put-config.json");
        assertGolden(putH("/api/v1/initialization/config/" + KB_INIT, bearer,
                "{\"llmModelId\":\"" + MD_LLM + "\",\"embeddingModelId\":"
                + "\"a0000000-0000-0000-0000-000000000402\"}"),
                400, "init-put-config-embedding-change.json");
        assertGolden(putH("/api/v1/initialization/config/" + KB_INIT, bearer,
                "{\"llmModelId\":\"a0000000-0000-0000-0000-0000000004ff\"}"),
                400, "init-put-config-llm-missing.json");
        assertGolden(putH("/api/v1/initialization/config/" + MISSING, bearer,
                "{\"llmModelId\":\"" + MD_LLM + "\"}"),
                404, "init-put-config-404.json");
        assertGolden(putH("/api/v1/initialization/config/" + KB_INIT, bearer, null),
                400, "init-put-config-empty.json");
        assertGolden(putH("/api/v1/initialization/config/" + KB_INIT, bearer,
                "{\"llmModelId\":\"" + MD_LLM + "\",\"storageProvider\":\"ftp\"}"),
                400, "init-put-config-badprovider.json");
    }

    private static final String FULL_CONFIG = "{"
        + "\"name\":\"ag-full\",\"description\":\"full config agent\",\"avatar\":\"robot\","
        + "\"config\":{"
        + "\"agent_mode\":\"smart-reasoning\",\"agent_type\":\"rag-qa\","
        + "\"system_prompt\":\"you are full\",\"context_template\":\"ctx {{query}}\","
        + "\"model_id\":\"shr-model-1\",\"rerank_model_id\":\"shr-rerank-1\","
        + "\"temperature\":0.5,\"max_completion_tokens\":4096,"
        + "\"thinking\":true,\"citation_enabled\":false,\"max_iterations\":-1,"
        + "\"llm_call_timeout\":120,"
        + "\"allowed_tools\":[\"knowledge_search\",\"web_search\"],"
        + "\"mcp_selection_mode\":\"selected\",\"mcp_services\":[\"mcp-1\",\"mcp-2\"],"
        + "\"mcp_auth_wait_timeout\":30,"
        + "\"skills_selection_mode\":\"selected\",\"selected_skills\":[\"skill-a\"],"
        + "\"kb_selection_mode\":\"selected\",\"knowledge_bases\":[\"" + KB_FAQ + "\"],"
        + "\"retrieve_kb_only_when_mentioned\":true,\"retain_retrieval_history\":true,"
        + "\"image_upload_enabled\":true,\"vlm_model_id\":\"vlm-1\","
        + "\"audio_upload_enabled\":true,\"asr_model_id\":\"asr-1\","
        + "\"image_storage_provider\":\"local\","
        + "\"supported_file_types\":[\"csv\",\"xlsx\"],"
        + "\"attachment_image_understanding\":true,"
        + "\"attachment_ocr_max_pages\":5,\"attachment_parse_wait_timeout_sec\":30,"
        + "\"data_analysis_enabled\":true,"
        + "\"faq_priority_enabled\":true,\"faq_direct_answer_threshold\":0.8,\"faq_score_boost\":1.5,"
        + "\"web_search_enabled\":true,\"web_search_max_results\":8,"
        + "\"web_search_provider_id\":\"wsp-1\",\"web_fetch_enabled\":true,\"web_fetch_top_n\":4,"
        + "\"history_turns\":3,"
        + "\"memory_enabled\":true,"
        + "\"embedding_top_k\":8,\"keyword_threshold\":0.2,\"vector_threshold\":0.4,"
        + "\"rerank_top_k\":3,\"rerank_threshold\":0.1,"
        + "\"enable_query_expansion\":true,\"enable_rewrite\":true,"
        + "\"rewrite_prompt_system\":\"rsys\",\"rewrite_prompt_user\":\"ruser {{conversation}}\","
        + "\"query_understand_model_id\":\"qu-model\","
        + "\"fallback_strategy\":\"fixed\",\"fallback_response\":\"sorry\",\"fallback_prompt\":\"fp\","
        + "\"intent_prompts\":{\"greeting\":\"hi there\"},"
        + "\"question_suggestions\":{"
        + "\"starters\":{\"enabled\":true,\"mode\":\"curated\",\"items\":[\"问题A\",\"问题B\"],\"count\":2},"
        + "\"follow_ups\":{\"enabled\":true,\"mode\":\"hybrid\",\"count\":2,\"model_id\":\"fu-model\","
        + "\"additional_instruction\":\"be nice\",\"categories\":[\"clarify\",\"deepen\"],"
        + "\"max_context_turns\":3,\"suppress_on_fallback\":true,"
        + "\"suppress_when_answer_asks_question\":true,\"knowledge_fallback\":true,"
        + "\"allow_regenerate\":true}}}}";

    private static final String INIT_REQUEST = "{"
        + "\"llm\":{\"source\":\"remote\",\"modelName\":\"ag-init-llm\",\"baseUrl\":\"\",\"apiKey\":\"sk-init\"},"
        + "\"embedding\":{\"source\":\"remote\",\"modelName\":\"ag-init-embed\",\"baseUrl\":\"\",\"dimension\":1024},"
        + "\"documentSplitting\":{\"chunkSize\":500,\"chunkOverlap\":50,\"separators\":[\"\\n\\n\"]}}";

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

    private void assertGolden(MockHttpServletRequestBuilder req, int status, String goldenName)
            throws Exception {
        expect(status, req, goldenName);
    }

    private MvcResult expect(int status, MockHttpServletRequestBuilder req, String goldenName)
            throws Exception {
        MvcResult r = mockMvc.perform(req).andReturn();
        assertEquals(status, r.getResponse().getStatus(), goldenName + " 状态码不符: " + raw(r));
        String actual = raw(r);
        String golden = golden(goldenName);
        assertEquals(mask(golden), mask(actual), goldenName);
        return r;
    }

    private static MockHttpServletRequestBuilder getH(String url, String bearer) {
        MockHttpServletRequestBuilder b = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url);
        return bearer == null ? b : b.header("Authorization", bearer);
    }

    private static MockHttpServletRequestBuilder postH(String url, String bearer, String body) {
        MockHttpServletRequestBuilder b = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url);
        if (bearer != null) {
            b = b.header("Authorization", bearer);
        }
        return body == null ? b : b.contentType(MediaType.valueOf(CT)).content(body);
    }

    private static MockHttpServletRequestBuilder putH(String url, String bearer, String body) {
        MockHttpServletRequestBuilder b = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put(url).header("Authorization", bearer);
        return body == null ? b : b.contentType(MediaType.valueOf(CT)).content(body);
    }

    private static MockHttpServletRequestBuilder delH(String url, String bearer) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete(url).header("Authorization", bearer);
    }

    private static String mask(String s) {
        s = TS_PATTERN.matcher(s).replaceAll("<ts>");
        return UUID_PATTERN.matcher(s).replaceAll("\"<uuid>\"");
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

    private static String raw(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String golden(String name) throws Exception {
        return new String(new ClassPathResource("contracts/" + name).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
    }
}
