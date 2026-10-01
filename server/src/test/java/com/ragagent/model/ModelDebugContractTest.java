package com.ragagent.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.TestSchema;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.TenantMember;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.domain.UserPreferences;
import com.ragagent.auth.mapper.TenantMapper;
import com.ragagent.auth.mapper.TenantMemberMapper;
import com.ragagent.auth.mapper.UserMapper;
import com.ragagent.common.security.SsrfGuard;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * 阶段 7 契约测试：POST /api/v1/models/{id}/debug（模型调试），对照 golden 逐字节比对。
 *
 * <p>golden 来源：Go dev server（2026-09-22 录制，scripts/record-modeldebug-golden.sh），
 * 上游是脚本化 stub LLM（scripts/stub-llm-server.py）；本测试用 in-JVM
 * {@link HttpServer} 复刻同一 stub 的响应（chat 流式/非流式、embeddings、rerank、
 * audio/transcriptions 401 探针），种子数据与录制脚本同构（租户 10009、模型
 * b0000000-…-01..09）。唯一掩码：elapsed_ms。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ModelDebugContractTest {

    private static final String BCRYPT = "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK"; // Passw0rd!
    private static final OffsetDateTime TS = OffsetDateTime.of(2026, 9, 1, 8, 0, 0, 0, ZoneOffset.UTC);
    private static final long TENANT = 10009L;
    private static final String USER_ID = "b0000000-0000-0000-0000-0000000000f1";

    private static final String MD_CHAT = "b0000000-0000-0000-0000-000000000001";
    private static final String MD_CHAT_THINK = "b0000000-0000-0000-0000-000000000002";
    private static final String MD_EMB = "b0000000-0000-0000-0000-000000000003";
    private static final String MD_RERANK = "b0000000-0000-0000-0000-000000000004";
    private static final String MD_VLM = "b0000000-0000-0000-0000-000000000005";
    private static final String MD_ASR = "b0000000-0000-0000-0000-000000000006";
    private static final String MD_ASR_NOKEY = "b0000000-0000-0000-0000-000000000007";
    private static final String MD_BADSRC = "b0000000-0000-0000-0000-000000000008";
    private static final String MD_EMB_DL = "b0000000-0000-0000-0000-000000000009";

    private static final Pattern ELAPSED = Pattern.compile("\"elapsedMs\":\\d+");

    /** 1x1 PNG（与录制脚本 TINY_PNG_B64 相同，70 字节）。 */
    private static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    private static final byte[] TINY_MP3 = "fake-mp3-bytes".getBytes(StandardCharsets.UTF_8);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static HttpServer stub;
    private static String stubBase;

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
    private SsrfGuard ssrfGuard;

    private String token;

    // ── in-JVM stub LLM（复刻 scripts/stub-llm-server.py 的响应） ──────────

    @BeforeAll
    static void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        stub.createContext("/", ModelDebugContractTest::dispatch);
        stub.start();
        stubBase = "http://127.0.0.1:" + stub.getAddress().getPort();
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) {
            stub.stop(0);
        }
    }

    private static void dispatch(HttpExchange ex) throws IOException {
        byte[] body = ex.getRequestBody().readAllBytes();
        String path = ex.getRequestURI().getPath();
        if (path.endsWith("/chat/completions")) {
            chat(ex, body);
        } else if (path.endsWith("/embeddings")) {
            respondJson(ex, 200, "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,"
                    + "\"embedding\":[0.1,0.2,0.3]}],\"model\":\"stub-model\","
                    + "\"usage\":{\"prompt_tokens\":2,\"total_tokens\":2}}");
        } else if (path.endsWith("/rerank")) {
            respondJson(ex, 200, "{\"results\":[{\"index\":0,\"relevance_score\":0.99}],"
                    + "\"model\":\"stub-model\"}");
        } else if (path.endsWith("/audio/transcriptions")) {
            audio(ex);
        } else {
            respond(ex, 404, "text/plain", "");
        }
    }

    /** chat/completions：固定分片 "你好，" + "我是知识助手。"（usage 恒定，对照 stub USAGE）。 */
    private static void chat(HttpExchange ex, byte[] body) throws IOException {
        JsonNode req;
        try {
            req = MAPPER.readTree(body.length == 0 ? "{}" : new String(body, StandardCharsets.UTF_8));
        } catch (IOException e) {
            req = MAPPER.createObjectNode();
        }
        String model = req.path("model").asText("stub-model");
        boolean stream = req.path("stream").asBoolean(false);
        String usage = "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":9,\"total_tokens\":21}";
        if (!stream) {
            respondJson(ex, 200, "{\"id\":\"chatcmpl-stub46d\",\"object\":\"chat.completion\","
                    + "\"created\":1735689600,\"model\":\"" + model + "\",\"choices\":[{\"index\":0,"
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"你好，我是知识助手。\"},"
                    + "\"finish_reason\":\"stop\"}]," + usage + "}");
            return;
        }
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0); // chunked
        try (OutputStream os = ex.getResponseBody()) {
            String chunk1 = "{\"id\":\"chatcmpl-stub46d\",\"object\":\"chat.completion.chunk\","
                    + "\"created\":1735689600,\"model\":\"" + model + "\",\"choices\":[{\"index\":0,"
                    + "\"delta\":{\"role\":\"assistant\",\"content\":\"你好，\"},\"finish_reason\":null}]}";
            String chunk2 = "{\"id\":\"chatcmpl-stub46d\",\"object\":\"chat.completion.chunk\","
                    + "\"created\":1735689600,\"model\":\"" + model + "\",\"choices\":[{\"index\":0,"
                    + "\"delta\":{\"content\":\"我是知识助手。\"},\"finish_reason\":\"stop\"}]," + usage + "}";
            os.write(("data: " + chunk1 + "\n\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.write(("data: " + chunk2 + "\n\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }

    /** audio/transcriptions：缺/空 Authorization（含 Go 的 "Bearer " 尾随空格形态）→ 401。 */
    private static void audio(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || auth.trim().isEmpty() || auth.trim().equals("Bearer")) {
            respondJson(ex, 401, "{\"error\":{\"message\":\"invalid api key\","
                    + "\"type\":\"invalid_request_error\"}}");
            return;
        }
        respondJson(ex, 200, "{\"text\":\"stub-transcript\",\"segments\":[{\"start\":0.0,"
                + "\"end\":1.5,\"text\":\" stub-transcript \"}]}");
    }

    private static void respondJson(HttpExchange ex, int status, String json) throws IOException {
        respond(ex, status, "application/json", json);
    }

    private static void respond(HttpExchange ex, int status, String contentType, String body)
            throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }

    // ── 种子（对照 record-modeldebug-golden.sh 的 SQL） ────────────────────

    @BeforeEach
    void seed() throws Exception {
        ssrfGuard.reloadWhitelist("127.0.0.1,::1,localhost");
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);

        Tenant tenant = new Tenant();
        tenant.setId(TENANT);
        tenant.setName("md-batch-tenant");
        tenant.setStatus("active");
        tenantMapper.insert(tenant);

        User user = new User();
        user.setId(USER_ID);
        user.setUsername("mdbatch");
        user.setEmail("md-batch@weknora.test");
        user.setPasswordHash(BCRYPT);
        user.setTenantId(TENANT);
        user.setIsActive(true);
        user.setPreferences(new UserPreferences());
        user.setCreatedAt(TS);
        user.setUpdatedAt(TS);
        userMapper.insert(user);

        TenantMember member = new TenantMember();
        member.setUserId(USER_ID);
        member.setTenantId(TENANT);
        member.setRole("owner");
        member.setStatus("active");
        member.setJoinedAt(TS);
        memberMapper.insert(member);

        String url = stubBase + "/v1";
        insertModel(MD_CHAT, "KnowledgeQA", "md-chat", "remote", "debug chat",
                "{\"baseUrl\":\"" + url + "\"}", "active");
        insertModel(MD_CHAT_THINK, "KnowledgeQA", "md-chat-think", "remote", "debug chat think",
                "{\"baseUrl\":\"" + url + "\",\"extraConfig\":{\"thinking_control\":"
                        + "\"enable_thinking\",\"api_token\":\"super-secret\"}}", "active");
        insertModel(MD_EMB, "Embedding", "md-emb", "remote", "debug embedding",
                "{\"baseUrl\":\"" + url + "\"}", "active");
        insertModel(MD_RERANK, "Rerank", "md-rerank", "remote", "debug rerank",
                "{\"baseUrl\":\"" + url + "\"}", "active");
        insertModel(MD_VLM, "VLLM", "md-vlm", "remote", "debug vlm",
                "{\"baseUrl\":\"" + url + "\"}", "active");
        insertModel(MD_ASR, "ASR", "md-asr", "remote", "debug asr",
                "{\"baseUrl\":\"" + url + "\",\"apiKey\":\"sk-debug\"}", "active");
        insertModel(MD_ASR_NOKEY, "ASR", "md-asr-nokey", "remote", "debug asr nokey",
                "{\"baseUrl\":\"" + url + "\"}", "active");
        insertModel(MD_BADSRC, "KnowledgeQA", "md-badsrc", "bogus", "debug bad source",
                "{\"baseUrl\":\"" + url + "\"}", "active");
        insertModel(MD_EMB_DL, "Embedding", "md-emb-dl", "remote", "debug downloading",
                "{\"baseUrl\":\"" + url + "\"}", "downloading");

        token = login("md-batch@weknora.test");
    }

    @AfterEach
    void restoreWhitelist() {
        ssrfGuard.reloadWhitelist(mergeEnvWhitelist());
    }

    /** 还原成"env 里那一份"（{@code SsrfGuard} 的默认初始化逻辑）。 */
    private static String mergeEnvWhitelist() {
        String primary = System.getenv("SSRF_WHITELIST");
        String extra = System.getenv("SSRF_WHITELIST_EXTRA");
        primary = primary == null ? "" : primary.trim();
        extra = extra == null ? "" : extra.trim();
        if (primary.isEmpty()) {
            return extra;
        }
        if (extra.isEmpty()) {
            return primary;
        }
        return primary + "," + extra;
    }

    private void insertModel(String id, String type, String name, String source, String description,
                             String parameters, String status) {
        jdbc.update("INSERT INTO models (id, tenant_id, type, name, source, description, parameters,"
                        + " is_default, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                id, TENANT, type, name, source, description, parameters, false, status,
                java.sql.Timestamp.from(TS.toInstant()), java.sql.Timestamp.from(TS.toInstant()));
    }

    // ── 参数校验族（确定性 400/404/500） ──────────────────────────────────

    @Test
    void notFound() throws Exception {
        assertDebug("md-not-found.json", 404, dbg("b0000000-0000-0000-0000-000000000099")
                .param("input", "hi"));
    }

    @Test
    void inputTooLong() throws Exception {
        assertDebug("md-input-too-long.json", 400, dbg(MD_CHAT)
                .param("input", "x".repeat(64 * 1024 + 1)));
    }

    @Test
    void optionsBadType() throws Exception {
        assertDebug("md-options-bad-type.json", 400, dbg(MD_CHAT)
                .param("input", "hi").param("options", "{\"maxTokens\":\"abc\"}"));
    }

    @Test
    void optionsSyntax() throws Exception {
        assertDebug("md-options-syntax.json", 400, dbg(MD_CHAT)
                .param("input", "hi").param("options", "{"));
    }

    @Test
    void optionsMaxTokens() throws Exception {
        assertDebug("md-options-maxtokens.json", 400, dbg(MD_CHAT)
                .param("input", "hi").param("options", "{\"maxTokens\":0}"));
    }

    @Test
    void optionsTemperature() throws Exception {
        assertDebug("md-options-temperature.json", 400, dbg(MD_CHAT)
                .param("input", "hi").param("options", "{\"temperature\":2.5}"));
    }

    @Test
    void optionsTopP() throws Exception {
        assertDebug("md-options-topp.json", 400, dbg(MD_CHAT)
                .param("input", "hi").param("options", "{\"topP\":0}"));
    }

    @Test
    void documentsBad() throws Exception {
        assertDebug("md-documents-bad.json", 400, dbg(MD_RERANK)
                .param("input", "q").param("documents", "not-json"));
    }

    @Test
    void documentsTooMany() throws Exception {
        StringBuilder docs = new StringBuilder("[");
        for (int i = 0; i < 101; i++) {
            if (i > 0) {
                docs.append(", ");
            }
            docs.append("\"d").append(i).append("\"");
        }
        docs.append("]");
        assertDebug("md-documents-too-many.json", 400, dbg(MD_RERANK)
                .param("input", "q").param("documents", docs.toString()));
    }

    @Test
    void modelDownloading() throws Exception {
        assertDebug("md-model-downloading.json", 500, dbg(MD_EMB_DL).param("input", "hi"));
    }

    // ── 分支前置校验（400） ────────────────────────────────────────────────

    @Test
    void chatEmptyQuery() throws Exception {
        assertDebug("md-chat-empty-query.json", 400, dbg(MD_CHAT).param("input", "   "));
    }

    @Test
    void embeddingEmpty() throws Exception {
        assertDebug("md-embedding-empty.json", 400, dbg(MD_EMB).param("input", "  "));
    }

    @Test
    void rerankMissingDocs() throws Exception {
        assertDebug("md-rerank-missing-docs.json", 400, dbg(MD_RERANK).param("input", "q"));
    }

    @Test
    void vlmNoFile() throws Exception {
        assertDebug("md-vlm-no-file.json", 400, dbg(MD_VLM).param("input", "describe"));
    }

    @Test
    void asrNoFile() throws Exception {
        assertDebug("md-asr-no-file.json", 400, dbg(MD_ASR).param("input", ""));
    }

    // ── 成功路径（stub 上游） ─────────────────────────────────────────────

    @Test
    void chatOk() throws Exception {
        assertDebug("md-chat-ok.json", 200, dbg(MD_CHAT).param("input", "<<SCENARIO:chat>>你好"));
    }

    @Test
    void chatOptions() throws Exception {
        assertDebug("md-chat-options.json", 200, dbg(MD_CHAT)
                .param("input", "<<SCENARIO:chat>>你好")
                .param("options", "{\"systemPrompt\":\"你是助手\",\"temperature\":0.5,"
                        + "\"topP\":0.9,\"maxTokens\":100,\"thinking\":true}"));
    }

    @Test
    void chatThinking() throws Exception {
        assertDebug("md-chat-thinking.json", 200, dbg(MD_CHAT_THINK)
                .param("input", "<<SCENARIO:chat>>你好").param("options", "{\"thinking\":true}"));
    }

    @Test
    void chatBadSource() throws Exception {
        assertDebug("md-chat-bad-source.json", 200, dbg(MD_BADSRC).param("input", "hi"));
    }

    @Test
    void embeddingOk() throws Exception {
        assertDebug("md-embedding-ok.json", 200, dbg(MD_EMB).param("input", "embed me"));
    }

    @Test
    void rerankOk() throws Exception {
        assertDebug("md-rerank-ok.json", 200, dbg(MD_RERANK)
                .param("input", "q").param("documents", "[\"第一段\",\"第二段\"]"));
    }

    @Test
    void vlmOk() throws Exception {
        assertDebug("md-vlm-ok.json", 200, dbg(MD_VLM)
                .file(new MockMultipartFile("file", "tiny.png", "image/png", TINY_PNG))
                .param("input", "描述这张图"));
    }

    @Test
    void asrOk() throws Exception {
        assertDebug("md-asr-ok.json", 200, dbg(MD_ASR)
                .file(new MockMultipartFile("file", "tiny.mp3", "audio/mpeg", TINY_MP3)));
    }

    @Test
    void asr401() throws Exception {
        assertDebug("md-asr-401.json", 200, dbg(MD_ASR_NOKEY)
                .file(new MockMultipartFile("file", "tiny.mp3", "audio/mpeg", TINY_MP3)));
    }

    // ── 工具 ──────────────────────────────────────────────────────────────

    private MockMultipartHttpServletRequestBuilder dbg(String modelId) {
        MockMultipartHttpServletRequestBuilder builder =
                multipart("/api/v1/models/" + modelId + "/debug");
        builder.header("Authorization", "Bearer " + token);
        return builder;
    }

    private void assertDebug(String goldenName, int status, MockHttpServletRequestBuilder req)
            throws Exception {
        MvcResult result = mockMvc.perform(req)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().is(status))
                .andReturn();
        String actual = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(mask(golden(goldenName)), mask(actual),
                goldenName + " 应与 golden 一致（掩码 elapsed_ms 后）");
    }

    private String login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Passw0rd!\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        java.util.regex.Matcher m = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(body);
        assertTrue(m.find(), "login 响应应含 token: " + body);
        return m.group(1);
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

    /** 唯一掩码：elapsedMs。 */
    private static String mask(String s) {
        // PR4 语义比较入口：键序/转义归一后再掩码
        s = com.ragagent.support.ContractJson.semantic(s);
        return ELAPSED.matcher(s).replaceAll("\"elapsedMs\":0");
    }
}
