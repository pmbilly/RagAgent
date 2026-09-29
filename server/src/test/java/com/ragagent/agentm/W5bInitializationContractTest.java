package com.ragagent.agentm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import com.ragagent.TestSchema;
import com.ragagent.common.security.SsrfGuard;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 收尾批 W5b 契约测试：initialization 系统级 14 条（ollama 管理 6 + 模型测试 5 +
 * 抽取 3），对照 golden w5b-*.json（Go dev server 实录，
 * scripts/record-w5b-golden.sh）逐字节/掩码比对。
 *
 * <p>结构（顺序敏感）：</p>
 * <ol>
 *   <li><b>down 家族</b>（@Order(1)）：ollama stub 未启动——status/models/check/
 *       download 的不可用形态 + progress 404 + tasks 空表 + viewer 403 + noauth。
 *       ollama 错误内文两侧措辞不同（Go dial tcp vs JDK），掩码 <ollama-err>。</li>
 *   <li><b>up 家族</b>（@Order(2)）：in-JVM Ollama stub（11434，与 bean 缺省基址
 *       对齐）——status/models/check/download 全周期（轮询到 completed）。</li>
 *   <li><b>upstream 家族</b>（@Order(3)）：SSRF 白名单先撤（127.0.0.1 被拒的确定性
 *       400）再注入，in-JVM OpenAI 兼容 stub——remote/embedding/rerank/asr 的
 *       全部分支 + 抽取 graph/fabri-text + multimodal 校验族。</li>
 *   <li>fabri-tag 随机输出：掩码 tags 数组内容，另断言形状（1..9 个、不重复、
 *       全在 tagOptions 内）。</li>
 * </ol>
 *
 * <p>multimodal 的 docreader 执行步（成功/失败两态）不进 golden——Java 测试 JVM 无
 * docreader，Go dev 有；该路径放 ab-w5b.sh（双端同打同一 docreader）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class W5bInitializationContractTest {

    private static final String BCRYPT = "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK"; // Passw0rd!
    private static final String AGU = "a0000000-0000-0000-0000-000000000001";
    private static final String AGV = "a0000000-0000-0000-0000-000000000002";
    private static final String MD_STUB = "a0000000-0000-0000-0000-000000000402";
    private static final String MISSING_TASK = "99999999-9999-9999-9999-999999999999";

    private static final Pattern TS_PATTERN = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})");
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"");
    /** ollama 不可用错误内文（Go dial tcp 文案 vs JDK 文案，掩到值）。 */
    private static final Pattern OLLAMA_ERR = Pattern.compile(
            "(ollama service unavailable: (?:[^\"\\\\]|\\\\.)*)");
    /** 下载任务进度值（up 家族轮询中的中间态，不比对；终态是确定的 100）。 */
    private static final String GRAPH_TEXT = "<<SCENARIO:graph>> 请从下面的文本中抽取实体与关系。";

    /** 1x1 PNG（与录制脚本 scripts/record-w5b-golden.sh 的 base64 同源）。 */
    private static final byte[] TINY_PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SsrfGuard ssrfGuard;

    private static SsrfGuard guardHolder;

    private String bearer;

    @BeforeEach
    void seed() throws Exception {
        guardHolder = ssrfGuard;
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        com.ragagent.agentm.service.OllamaDownloadTaskStore.resetAll();
        jdbc.execute("INSERT INTO tenants (id, name, description, business, status) VALUES "
                + "(10005, 'ag-batch-tenant', '', '', 'active')");
        jdbc.update("INSERT INTO users (id, username, email, password_hash, tenant_id, is_active) VALUES "
                + "(?, 'agbatch', 'ag-batch@weknora.test', ?, 10005, true),"
                + "(?, 'agviewer', 'ag-batch-viewer@weknora.test', ?, 10005, true)",
                AGU, BCRYPT, AGV, BCRYPT);
        jdbc.update("INSERT INTO tenant_members (user_id, tenant_id, role, status) VALUES "
                + "(?, 10005, 'owner', 'active'), (?, 10005, 'viewer', 'active')", AGU, AGV);
        // 抽取/测试连接用的模型行（baseUrl 在 upstream 用例里按 stub 端口回填）
        jdbc.update("INSERT INTO models (id, tenant_id, type, name, source, description, parameters, "
                + "is_default, status, created_at, updated_at) VALUES "
                + "(?, 10005, 'KnowledgeQA', 'w5b-stub-llm', 'remote', 'W5B stub chat model', "
                + "'{}', FALSE, 'active', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00')",
                MD_STUB);
        bearer = "Bearer " + login("ag-batch@weknora.test");
    }

    @AfterAll
    static void restoreWhitelist() {
        if (guardHolder != null) {
            guardHolder.reloadWhitelist("");
        }
    }

    // ══════════════ @Order(1)：ollama down 家族（stub 未启动） ══════════════

    @Test
    @Order(1)
    void downFamily() throws Exception {
        assertGolden(get("/api/v1/initialization/ollama/status", null), 401, "w5b-noauth.json");
        assertGolden(get("/api/v1/initialization/ollama/status", viewerBearer()), 200,
                "w5b-ollama-status-down.json");
        assertGolden(get("/api/v1/initialization/ollama/models", bearer), 500,
                "w5b-ollama-models-down.json");
        assertGolden(post("/api/v1/initialization/ollama/models/check", bearer,
                "{\"models\":[\"stub-model\"]}"), 500, "w5b-ollama-check-down.json");
        assertGolden(post("/api/v1/initialization/ollama/models/download", bearer,
                "{\"modelName\":\"stub-model\"}"), 500, "w5b-ollama-download-down.json");
        assertGolden(get("/api/v1/initialization/ollama/download/progress/" + MISSING_TASK, bearer),
                404, "w5b-progress-missing.json");
        assertGolden(get("/api/v1/initialization/ollama/download/tasks", bearer), 200,
                "w5b-tasks-empty.json");
        // viewer 打 Admin+ 路由 → RBAC 403
        assertGolden(post("/api/v1/initialization/remote/check", viewerBearer(), "{}"), 403,
                "w5b-viewer-post-403.json");
    }

    // ══════════════ @Order(2)：ollama up 家族（in-JVM stub） ══════════════

    @Test
    @Order(2)
    void upFamily() throws Exception {
        W5bStubServers.startOllama();
        assertGolden(get("/api/v1/initialization/ollama/status", bearer), 200,
                "w5b-ollama-status-up.json");
        assertGolden(get("/api/v1/initialization/ollama/models", bearer), 200,
                "w5b-ollama-models.json");
        assertGolden(post("/api/v1/initialization/ollama/models/check", bearer,
                "{\"models\":[\"stub-model\",\"absent-model\"]}"), 200, "w5b-ollama-check.json");
        assertGolden(post("/api/v1/initialization/ollama/models/check", bearer, "{}"), 400,
                "w5b-ollama-check-badbody.json");
        assertGolden(post("/api/v1/initialization/ollama/models/download", bearer,
                "{\"modelName\":\"stub-model\"}"), 200, "w5b-download-exists.json");

        // 建任务 → 轮询到 completed（stub 剧本固定 → 终态确定）
        MvcResult created = expect(200, post("/api/v1/initialization/ollama/models/download",
                bearer, "{\"modelName\":\"fresh-model\"}"), "w5b-download-created.json");
        String taskId = jsonPath(created, "data.taskId");
        String finalBody = "";
        for (int i = 0; i < 50; i++) {
            MvcResult progress = result(get(
                    "/api/v1/initialization/ollama/download/progress/" + taskId, bearer));
            finalBody = raw(progress);
            if (finalBody.contains("\"status\":\"completed\"")
                    || finalBody.contains("\"status\":\"failed\"")) {
                break;
            }
            Thread.sleep(100);
        }
        compareGolden(finalBody, 200, "w5b-progress-done.json");
        assertGolden(get("/api/v1/initialization/ollama/download/tasks", bearer), 200,
                "w5b-tasks-one.json");
    }

    // ══════════════ @Order(3)：upstream 家族（SSRF 白名单 + OpenAI 兼容 stub） ══════════════

    @Test
    @Order(3)
    void upstreamFamily() throws Exception {
        // 直连 IP（10.0.0.1 不在白名单）是确定性 SSRF 400，与白名单状态无关
        ssrfGuard.reloadWhitelist("");
        assertGolden(post("/api/v1/initialization/remote/check", bearer,
                "{\"modelName\":\"stub-model\",\"baseUrl\":\"http://10.0.0.1:9/v1\"}"), 400,
                "w5b-remote-check-ssrf.json");
        assertGolden(post("/api/v1/initialization/embedding/test", bearer,
                "{\"modelName\":\"stub-model\",\"baseUrl\":\"http://10.0.0.1:9/v1\"}"), 400,
                "w5b-embedding-ssrf.json");

        ssrfGuard.reloadWhitelist("127.0.0.1");
        int port = W5bStubServers.startUpstream();
        String base = "http://127.0.0.1:" + port + "/v1";
        jdbc.update("UPDATE models SET parameters = ? WHERE id = ?",
                "{\"base_url\":\"" + base + "\"}", MD_STUB);

        // ── remote/check ──
        assertGolden(post("/api/v1/initialization/remote/check", bearer, "{}"), 400,
                "w5b-remote-check-missing.json");
        assertGolden(post("/api/v1/initialization/remote/check", bearer,
                "{\"modelName\":\"stub-model\",\"baseUrl\":\"" + base + "\"}"), 200,
                "w5b-remote-check-ok.json");

        // ── embedding/test ──
        assertGolden(post("/api/v1/initialization/embedding/test", bearer,
                "{\"modelName\":\"stub-model\",\"baseUrl\":\"" + base + "\"}"), 200,
                "w5b-embedding-ok.json");
        assertGolden(post("/api/v1/initialization/embedding/test", bearer,
                "{\"provider\":\"aliyun\",\"modelName\":\"text-embedding-vision\","
                        + "\"baseUrl\":\"" + base + "\"}"), 200, "w5b-embedding-aliyun.json");

        // ── rerank/check ──
        assertGolden(post("/api/v1/initialization/rerank/check", bearer,
                "{\"modelName\":\"stub-model\"}"), 400, "w5b-rerank-missing.json");
        assertGolden(post("/api/v1/initialization/rerank/check", bearer,
                "{\"modelName\":\"stub-model\",\"baseUrl\":\"" + base + "\"}"), 200,
                "w5b-rerank-ok.json");

        // ── asr/check（ASR baseUrl 带 /v1；model 字段路由场景） ──
        assertGolden(post("/api/v1/initialization/asr/check", bearer,
                "{\"modelName\":\"stub-model\"}"), 400, "w5b-asr-missing.json");
        assertGolden(post("/api/v1/initialization/asr/check", bearer,
                "{\"apiKey\":\"sk-w5b\",\"modelName\":\"stub-model\",\"baseUrl\":\"" + base + "\"}"), 200,
                "w5b-asr-ok.json");
        assertGolden(post("/api/v1/initialization/asr/check", bearer,
                "{\"apiKey\":\"sk-w5b\",\"modelName\":\"asr-401\",\"baseUrl\":\"" + base + "\"}"), 200,
                "w5b-asr-401.json");
        assertGolden(post("/api/v1/initialization/asr/check", bearer,
                "{\"apiKey\":\"sk-w5b\",\"modelName\":\"asr-404\",\"baseUrl\":\"" + base + "\"}"), 200,
                "w5b-asr-404.json");
        assertGolden(post("/api/v1/initialization/asr/check", bearer,
                "{\"apiKey\":\"sk-w5b\",\"modelName\":\"asr-modelmissing\",\"baseUrl\":\"" + base + "\"}"), 200,
                "w5b-asr-modelmissing.json");
        assertGolden(post("/api/v1/initialization/asr/check", bearer,
                "{\"apiKey\":\"sk-w5b\",\"modelName\":\"asr-500text\",\"baseUrl\":\"" + base + "\"}"), 200,
                "w5b-asr-500text.json");
        // fillSecrets 探针：apiKey 留空 + modelId 指存量模型（parameters 里没有明文 key
        // 两侧 → 都发空 Bearer → stub 401 → 同文案）
        jdbc.update("INSERT INTO models (id, tenant_id, type, name, source, description, parameters, "
                        + "is_default, status, created_at, updated_at) VALUES "
                        + "('a0000000-0000-0000-0000-000000000403', 10005, 'ASR', 'w5b-asr-stored', "
                        + "'remote', 'W5B stored ASR', '{}', FALSE, 'active', "
                        + "'2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00')");
        assertGolden(post("/api/v1/initialization/asr/check", bearer,
                "{\"modelName\":\"stub-model\",\"modelId\":\"a0000000-0000-0000-0000-000000000403\","
                        + "\"baseUrl\":\"" + base + "\"}"), 200, "w5b-asr-storedkey.json");

        // ── extract ──
        assertGolden(post("/api/v1/initialization/extract/text-relation", bearer, "{}"), 400,
                "w5b-extract-badbody.json");
        assertGolden(post("/api/v1/initialization/extract/text-relation", bearer,
                "{\"text\":\"" + "a".repeat(5001) + "\",\"tags\":[\"Author\"],\"modelId\":\""
                        + MD_STUB + "\"}"), 400, "w5b-extract-toolong.json");
        assertGolden(post("/api/v1/initialization/extract/text-relation", bearer,
                "{\"text\":\"x\",\"tags\":[\"Author\"],\"modelId\":\"nope\"}"), 400,
                "w5b-extract-model-missing.json");
        assertGolden(post("/api/v1/initialization/extract/text-relation", bearer,
                "{\"text\":\"" + GRAPH_TEXT + "\",\"tags\":[\"Author\"],\"modelId\":\""
                        + MD_STUB + "\"}"), 200, "w5b-extract-graph.json");
        assertGolden(post("/api/v1/initialization/extract/fabri-text", bearer,
                "{\"tags\":[\"Author\",\"Alias\"],\"modelId\":\"" + MD_STUB + "\"}"), 200,
                "w5b-fabritext.json");
        assertGolden(post("/api/v1/initialization/extract/fabri-text", bearer,
                "{\"tags\":[],\"modelId\":\"nope\"}"), 400, "w5b-fabritext-model-missing.json");

        // ── multimodal 校验族（不触 docreader） ──
        MockMultipartFile png = new MockMultipartFile(
                "image", "tiny.png", "image/png", TINY_PNG);
        assertGolden(multipart("/api/v1/initialization/multimodal/test"), 400,
                "w5b-mm-missing-vlm.json");
        assertGolden(multipart("/api/v1/initialization/multimodal/test")
                        .param("vlm_model", "vlm-stub").param("vlm_base_url", base)
                        .param("storage_type", "s3"), 400, "w5b-mm-badstorage.json");
        assertGolden(multipart("/api/v1/initialization/multimodal/test")
                        .param("vlm_model", "vlm-stub").param("vlm_base_url", base)
                        .param("storage_type", "cos"), 400, "w5b-mm-cos-incomplete.json");
        assertGolden(multipart("/api/v1/initialization/multimodal/test")
                        .param("vlm_model", "vlm-stub").param("vlm_base_url", base)
                        .param("storage_type", "minio"), 400, "w5b-mm-minio-incomplete.json");
        assertGolden(multipart("/api/v1/initialization/multimodal/test")
                        .file(png)
                        .param("vlm_model", "vlm-stub").param("vlm_base_url", base)
                        .param("storage_type", "minio").param("minio_bucket_name", "b")
                        .param("chunk_size", "abc"), 400, "w5b-mm-chunksize.json");
        assertGolden(multipart("/api/v1/initialization/multimodal/test")
                        .file(png)
                        .param("vlm_model", "vlm-stub").param("vlm_base_url", base)
                        .param("storage_type", "minio").param("minio_bucket_name", "b")
                        .param("chunk_size", "1000").param("chunk_overlap", "abc"), 400,
                "w5b-mm-chunkoverlap.json");
        assertGolden(multipart("/api/v1/initialization/multimodal/test")
                        .file(new MockMultipartFile("image", "tiny.txt", "text/plain",
                                "hello".getBytes(StandardCharsets.UTF_8)))
                        .param("vlm_model", "vlm-stub").param("vlm_base_url", base)
                        .param("storage_type", "minio").param("minio_bucket_name", "b")
                        .param("chunk_size", "1000").param("chunk_overlap", "200"), 400,
                "w5b-mm-badtype.json");
    }

    /** @Order(4)：fabri-tag 随机输出（掩码内容 + 形状断言）。 */
    @Test
    @Order(4)
    void fabriTagShape() throws Exception {
        MvcResult r = result(post("/api/v1/initialization/extract/fabri-tag", bearer, ""));
        assertEquals(200, r.getResponse().getStatus());
        String body = raw(r);
        // 随机输出：tags 数组内容掩掉（形状断言见下），其余信封照 golden 比对
        String masked = Pattern.compile("\"tags\":\\[[^]]*\\]")
                .matcher(body).replaceAll("\"tags\":<tags>");
        masked = UUID_PATTERN.matcher(TS_PATTERN.matcher(masked).replaceAll("<ts>"))
                .replaceAll("\"<uuid>\"");
        String expected = new String(new ClassPathResource("contracts/w5b-fabritag.json")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        expected = Pattern.compile("\"tags\":\\[[^]]*\\]")
                .matcher(expected).replaceAll("\"tags\":<tags>");
        org.junit.jupiter.api.Assertions.assertEquals(expected, masked);
        com.fasterxml.jackson.databind.JsonNode tags =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(body)
                        .path("data").path("tags");
        assertTrue(tags.isArray() && !tags.isEmpty());
        Set<String> options = Set.of("Content", "Culture", "Person", "Event", "Time",
                "Location", "Work", "Author", "Relation", "Attribute");
        Set<String> seen = new HashSet<>();
        tags.forEach(t -> seen.add(t.asText()));
        assertTrue(seen.size() == tags.size() && options.containsAll(seen),
                "tags must be distinct and within tagOptions: " + seen);
    }

    // ══════════════ 请求与比对工具 ══════════════

    private String login(String email) throws Exception {
        MvcResult r = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Passw0rd!\"}"))
                .andReturn();
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(raw(r)).path("token").asText();
    }

    private String viewerBearer() throws Exception {
        return "Bearer " + login("ag-batch-viewer@weknora.test");
    }

    private MockHttpServletRequestBuilder get(String url, String bearerToken) {
        MockHttpServletRequestBuilder b = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(url);
        return bearerToken == null ? b : b.header("Authorization", bearerToken);
    }

    private MockHttpServletRequestBuilder post(String url, String bearerToken, String body) {
        MockHttpServletRequestBuilder b = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post(url).header("Authorization", bearerToken);
        return body == null ? b
                : b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder
            multipart(String url) {
        return (org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder)
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart(url).header("Authorization", bearer);
    }

    private MvcResult result(MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder).andReturn();
    }

    private MvcResult expect(int status, MockHttpServletRequestBuilder builder, String golden)
            throws Exception {
        MvcResult r = mockMvc.perform(builder).andReturn();
        assertEquals(status, r.getResponse().getStatus(),
                statusMismatch(golden, r));
        compareGolden(raw(r), status, golden);
        return r;
    }

    private void assertGolden(MockHttpServletRequestBuilder builder, int status, String golden)
            throws Exception {
        MvcResult r = mockMvc.perform(builder).andReturn();
        assertEquals(status, r.getResponse().getStatus(),
                statusMismatch(golden, r));
        compareGolden(raw(r), status, golden);
    }

    private void compareGolden(String actual, int status, String golden) throws Exception {
        // PR4 语义比较：双侧归一
        actual = com.ragagent.support.ContractJson.semantic(actual);
        String expected = com.ragagent.support.ContractJson.semantic(
                new String(new ClassPathResource("contracts/" + golden)
                        .getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        // golden 是 Go 原始实录（uuid/时间戳/ollama 错误内文为动态值）→ 两侧同掩码
        org.junit.jupiter.api.Assertions.assertEquals(mask(expected), mask(actual),
                () -> golden + " body mismatch (status " + status + ")");
    }

    private static String mask(String s) {
        s = TS_PATTERN.matcher(s).replaceAll("<ts>");
        s = UUID_PATTERN.matcher(s).replaceAll("\"<uuid>\"");
        s = OLLAMA_ERR.matcher(s).replaceAll("<ollama-err>");
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

    private static String statusMismatch(String golden, MvcResult r) {
        try {
            return golden + " status mismatch, body=" + raw(r);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
