package com.ragagent.retrieval.engine.opensearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;
import com.ragagent.vectorstore.domain.IndexConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * OpenSearch k-NN 驱动（W5γ4.9）对照 Go {@code retriever/opensearch/} 全包：
 * 构造期探针（版本分段 / 每节点 k-NN 插件）、惰性逐维建索引（mapping settings +
 * properties 形状、别名动作、drift 指纹）、Save/BatchSave 的 wire 形状（字母序键、
 * NDJSON 动作行、批量上限、混合维度）、knn/keyword 查询体（min_score 直通、
 * 类型化过滤）、删除/移动/批量更新的 by-query 体（painless + params、refresh=true、
 * 完整性校验）、CopyIndices 的三态 SourceID 改写与目标 SourceID 向量回填、
 * 纯函数（parseMajorMinor / sanitizeIndexName / transformSourceId / effectiveTopK）。
 *
 * <p>桩是本地 OS 假服务：断言"发出去的 JSON 长什么样"（该批无 golden 面，逐请求
 * 断言即本批的字节契约——键序为 Go json.Marshal 的 map 字母序）。</p>
 */
class OpenSearchRetrieveRepositoryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Captured(String method, String path, String contentType, String body) {
    }

    private final List<Captured> captured = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> statusOverrides = new HashMap<>();
    private final Map<String, String> bodyOverrides = new HashMap<>();
    private HttpServer server;
    private String base;
    private OpenSearchRetrieveRepository repo;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(null);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getRawQuery();
        String key = method + " " + path;
        byte[] body = ex.getRequestBody().readAllBytes();
        captured.add(new Captured(method, path + (query == null ? "" : "?" + query),
                ex.getRequestHeaders().getFirst("Content-Type"),
                body.length == 0 ? "" : new String(body, StandardCharsets.UTF_8)));
        ex.getResponseHeaders().set("Content-Type", "application/json");
        // HEAD（别名存在性）缺省 404——保证 ensureReady/ensureKeywordsIndex 的
        // 惰性建索引路径真实走到；其余缺省 200
        int status = statusOverrides.getOrDefault(key, method.equals("HEAD") ? 404 : 200);
        String response = bodyOverrides.get(key);
        if (response == null) {
            if ("GET /".equals(key) && status == 200) {
                response = "{\"version\":{\"distribution\":\"opensearch\",\"number\":\"2.11.0\"}}";
            } else if ("GET /_cat/plugins".equals(key) && status == 200) {
                response = "[{\"name\":\"node-1\",\"component\":\"opensearch-knn\"},"
                        + "{\"name\":\"node-1\",\"component\":\"opensearch-knn\"}]";
            } else if (method.equals("POST") && path.endsWith("_search") && status == 200) {
                response = "{\"hits\":{\"hits\":[]}}";
            } else {
                response = "{}";
            }
        }
        ex.sendResponseHeaders(status, response.getBytes(StandardCharsets.UTF_8).length);
        ex.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
        ex.close();
    }

    private OpenSearchRetrieveRepository newRepo() {
        return new OpenSearchRetrieveRepository(base, "", null, "", "", false, null);
    }

    private IndexInfo info(String sourceId, String chunkId) {
        IndexInfo i = new IndexInfo();
        i.sourceId = sourceId;
        i.chunkId = chunkId;
        i.knowledgeId = "doc-1";
        i.knowledgeBaseId = "kb-1";
        i.content = "hello";
        i.isEnabled = true;
        i.tagId = "";
        return i;
    }

    // ── 构造期探针 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("版本探针：distribution 非 opensearch / 1.x / 2.0~2.3 全拒")
    void versionProbeRejects() {
        statusOverrides.put("GET /", 200);
        bodyOverrides.put("GET /", "{\"version\":{\"distribution\":\"elasticsearch\","
                + "\"number\":\"8.11.0\"}}");
        assertThatThrownBy(this::newRepo)
                .isInstanceOfSatisfying(OpenSearchDriverException.class, e ->
                        assertThat(e.kind()).isEqualTo(OpenSearchDriverException.Kind.VERSION_UNSUPPORTED));

        bodyOverrides.put("GET /", "{\"version\":{\"distribution\":\"opensearch\","
                + "\"number\":\"1.3.0\"}}");
        assertThatThrownBy(this::newRepo).isInstanceOf(OpenSearchDriverException.class);

        bodyOverrides.put("GET /", "{\"version\":{\"distribution\":\"opensearch\","
                + "\"number\":\"2.3.0\"}}");
        assertThatThrownBy(this::newRepo).isInstanceOf(OpenSearchDriverException.class);
    }

    @Test
    @DisplayName("版本探针：2.5（pre-LTS）与 3.3.2 通过；k-NN 插件缺节点拒")
    void versionProbeAcceptsAndPluginProbe() {
        bodyOverrides.put("GET /", "{\"version\":{\"distribution\":\"opensearch\","
                + "\"number\":\"2.5.0\"}}");
        assertThat(newRepo()).isNotNull();
        bodyOverrides.put("GET /", "{\"version\":{\"distribution\":\"opensearch\","
                + "\"number\":\"3.3.2\"}}");
        assertThat(newRepo()).isNotNull();

        // 双节点其中之一缺 k-NN → CONFIG_INVALID，缺失列表 [node-2]（Go %v 形态）
        bodyOverrides.put("GET /_cat/plugins", "[{\"name\":\"node-1\","
                + "\"component\":\"opensearch-knn\"},{\"name\":\"node-2\","
                + "\"component\":\"other\"}]");
        assertThatThrownBy(this::newRepo)
                .isInstanceOfSatisfying(OpenSearchDriverException.class, e -> {
                    assertThat(e.kind()).isEqualTo(OpenSearchDriverException.Kind.CONFIG_INVALID);
                    assertThat(e.getMessage()).contains("missing on 1/2 nodes ([node-2])");
                });
    }

    @Test
    @DisplayName("storeID 折叠 + sanitize：env 空 id → weknora；≥16 id 折叠前 12 位；非法字符拒")
    void storeIdFoldingAndSanitize() {
        // 空 storeID → base=weknora；探针后无索引创建（惰性）
        OpenSearchRetrieveRepository env = newRepo();
        assertThat(env).isNotNull();
        // 非法字符在探针之后报——桩已回合法版本
        assertThatThrownBy(() -> new OpenSearchRetrieveRepository(base, "short", null,
                "", "", false, null))
                .isInstanceOfSatisfying(OpenSearchDriverException.class, e -> {
                    assertThat(e.kind()).isEqualTo(OpenSearchDriverException.Kind.CONFIG_INVALID);
                    assertThat(e.getMessage()).contains("storeID must be empty or >=16 chars, got 5");
                });
    }

    // ── 惰性建索引（ensureReady → createIndexAndAlias） ────────────────────

    @Test
    @DisplayName("首用建索引：PUT <base>_3_v1 带 knn settings + properties（字母序）→ PUT _aliases → 审计 emit")
    void lazyIndexCreation() throws Exception {
        List<String> auditEvents = new ArrayList<>();
        OpenSearchRetrieveRepository r = newRepo();
        r.withAuditSink(new OpenSearchRetrieveRepository.AuditSink() {
            @Override public void emitIndexCreated(String alias, int dim) {
                auditEvents.add("created:" + alias + ":" + dim);
            }

            @Override public void emitReindexExecuted(String src, String dst, long docs) {
            }
        });
        r.save(info("c1", "c1"), Map.of("embedding", Map.of("c1", new float[] {0.1f, 0.2f, 0.3f})));

        assertThat(auditEvents).containsExactly("created:weknora_3:3");
        Captured create = captured.stream()
                .filter(c -> c.method().equals("PUT") && c.path().equals("/weknora_3_v1"))
                .findFirst().orElseThrow();
        JsonNode body = MAPPER.readTree(create.body());
        assertThat(body.path("settings").path("index").path("knn").asBoolean()).isTrue();
        assertThat(body.path("settings").path("index").path("number_of_shards").asInt()).isEqualTo(4);
        assertThat(body.path("settings").path("index").path("refresh_interval").asText())
                .isEqualTo("1s");
        assertThat(body.path("settings").path("index").path("knn.algo_param.ef_search").asInt())
                .isEqualTo(100);
        JsonNode emb = body.path("mappings").path("properties").path("embedding");
        assertThat(emb.path("type").asText()).isEqualTo("knn_vector");
        assertThat(emb.path("dimension").asInt()).isEqualTo(3);
        assertThat(emb.path("method").path("name").asText()).isEqualTo("hnsw");
        assertThat(emb.path("method").path("space_type").asText()).isEqualTo("cosinesimil");
        assertThat(emb.path("method").path("engine").asText()).isEqualTo("lucene");
        assertThat(emb.path("method").path("parameters").path("m").asInt()).isEqualTo(16);
        assertThat(emb.path("method").path("parameters").path("ef_construction").asInt())
                .isEqualTo(100);
        assertThat(body.path("mappings").path("properties").path("chunk_id").path("type")
                .asText()).isEqualTo("keyword");
        assertThat(body.path("mappings").path("properties").path("source_type").path("type")
                .asText()).isEqualTo("integer");

        Captured alias = captured.stream()
                .filter(c -> c.path().equals("/_aliases")).findFirst().orElseThrow();
        JsonNode aliasBody = MAPPER.readTree(alias.body());
        assertThat(aliasBody.path("actions").get(0).path("add").path("index").asText())
                .isEqualTo("weknora_3_v1");
        assertThat(aliasBody.path("actions").get(0).path("add").path("alias").asText())
                .isEqualTo("weknora_3");

        // 幂等：第二次 save 时 alias 已存在（桩 HEAD 默认 404？——alias 存在性由
        // HEAD 决定；桩未覆写则恒 404 → 每次都会尝试创建。此处只验第二轮不再
        // 重复 emit 审计：HEAD 仍 404 时 PUT 会再次成功并 emit。为了验证短路，
        // 把 HEAD 拨成 200）
        statusOverrides.put("HEAD /_alias/weknora_3", 200);
        captured.clear();
        auditEvents.clear();
        r.save(info("c1", "c1"), Map.of("embedding", Map.of("c1", new float[] {0.1f, 0.2f, 0.3f})));
        assertThat(auditEvents).isEmpty();
        // alias 已存在 → 短路、不 emit
    }

    // ── Save / BatchSave 的 wire 形状 ──────────────────────────────────────

    @Test
    @DisplayName("Save：_id=chunk_id 幂等；文档键字母序；缺 embedding 路由 keywords 索引")
    void saveShapes() throws Exception {
        OpenSearchRetrieveRepository r = newRepo();
        Map<String, Object> params = new HashMap<>();
        params.put("embedding", Map.of("c1", new float[] {1.5f}));
        params.put("chunk_enabled", Map.of("c1", false));
        r.save(info("c1", "c1"), params);

        Captured put = captured.stream()
                .filter(c -> c.method().equals("PUT") && c.path().startsWith("/weknora_1/_doc/"))
                .findFirst().orElseThrow();
        assertThat(put.path()).isEqualTo("/weknora_1/_doc/c1").as("_id=chunk_id");
        JsonNode doc = MAPPER.readTree(put.body());
        assertThat(doc.path("chunk_id").asText()).isEqualTo("c1");
        assertThat(doc.path("is_enabled").asBoolean()).isFalse().as("chunk_enabled 覆写");
        assertThat(doc.path("embedding").get(0).asDouble()).isEqualTo(1.5);
        // 键字母序（Go json.Marshal 的 map 语义）
        List<String> keys = new ArrayList<>();
        doc.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).isSorted();

        // keyword-only：无 embedding → <base>_keywords 索引 + 文档无 embedding 字段
        captured.clear();
        r.save(info("s1", "c1"), null);
        Captured kw = captured.stream()
                .filter(c -> c.path().startsWith("/weknora_keywords/_doc/"))
                .findFirst().orElseThrow();
        assertThat(MAPPER.readTree(kw.body()).has("embedding")).isFalse();
    }

    @Test
    @DisplayName("BatchSave：NDJSON 动作行 {\"index\":{\"_id\",\"_index\"}}；混合维度拒；n>1000 拒")
    void batchSaveShapes() throws Exception {
        OpenSearchRetrieveRepository r = newRepo();
        Map<String, Object> params = Map.of("embedding", Map.of(
                "s1", new float[] {1f, 2f}, "s2", new float[] {3f, 4f}));
        r.batchSave(List.of(info("s1", "c1"), info("s2", "c2")), params);

        Captured bulk = captured.stream()
                .filter(c -> c.path().equals("/_bulk")).findFirst().orElseThrow();
        assertThat(bulk.contentType()).isEqualTo("application/x-ndjson");
        String[] lines = bulk.body().split("\n");
        assertThat(lines).hasSize(4);
        JsonNode action = MAPPER.readTree(lines[0]);
        assertThat(action.path("index").path("_id").asText()).isEqualTo("c1");
        assertThat(action.path("index").path("_index").asText()).isEqualTo("weknora_2");
        JsonNode doc2 = MAPPER.readTree(lines[1]);
        assertThat(doc2.path("source_id").asText()).isEqualTo("s1");

        // 混合维度 → DIMENSION_MISMATCH
        Map<String, Object> mixed = Map.of("embedding", Map.of(
                "s1", new float[] {1f, 2f}, "s2", new float[] {3f}));
        assertThatThrownBy(() -> r.batchSave(List.of(info("s1", "c1"), info("s2", "c2")), mixed))
                .isInstanceOfSatisfying(OpenSearchDriverException.class, e ->
                        assertThat(e.kind())
                                .isEqualTo(OpenSearchDriverException.Kind.DIMENSION_MISMATCH));

        // n > 1000 → BATCH_TOO_LARGE
        List<IndexInfo> many = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            many.add(info("s" + i, "c" + i));
        }
        assertThatThrownBy(() -> r.batchSave(many, null))
                .isInstanceOfSatisfying(OpenSearchDriverException.class, e ->
                        assertThat(e.kind())
                                .isEqualTo(OpenSearchDriverException.Kind.BATCH_TOO_LARGE));
    }

    // ── 检索 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("向量检索：knn 体（vector/k/filter 嵌套 must + min_score 直通）；命中解析含 matchType")
    void vectorRetrieveShape() throws Exception {
        OpenSearchRetrieveRepository r = newRepo();
        bodyOverrides.put("POST /weknora_2/_search",
                "{\"hits\":{\"hits\":[{\"_id\":\"c1\",\"_score\":0.87,\"_source\":"
                        + "{\"chunk_id\":\"c1\",\"knowledge_id\":\"doc-1\","
                        + "\"knowledge_base_id\":\"kb-1\",\"source_id\":\"c1\","
                        + "\"source_type\":0,\"tag_id\":\"\",\"content\":\"x\","
                        + "\"is_enabled\":true}}]}}");
        RetrieveParams p = new RetrieveParams();
        p.retrieverType = EngineTypes_RETREIVER_VECTOR;
        p.embedding = new float[] {1f, 2f};
        p.topK = 5;
        p.threshold = 0.7;
        p.knowledgeBaseIds = List.of("kb-1");
        List<RetrieveResult> out = r.retrieve(p);

        Captured search = captured.stream()
                .filter(c -> c.path().equals("/weknora_2/_search")).findFirst().orElseThrow();
        JsonNode body = MAPPER.readTree(search.body());
        assertThat(body.path("size").asInt()).isEqualTo(5);
        assertThat(body.path("min_score").asDouble()).isEqualTo(0.7)
                .as("COSINESIMIL 已映射 [0,1] → threshold 直通");
        JsonNode knn = body.path("query").path("knn").path("embedding");
        assertThat(knn.path("k").asInt()).isEqualTo(5);
        assertThat(knn.path("vector").get(0).asDouble()).isEqualTo(1.0);
        assertThat(knn.path("filter").path("bool").path("must").get(0)
                .path("terms").path("knowledge_base_id").get(0).asText()).isEqualTo("kb-1");
        assertThat(knn.path("filter").path("bool").path("must").get(1).path("term")
                .path("is_enabled").asBoolean()).isTrue().as("隐含 is_enabled=true 子句");

        assertThat(out).hasSize(1);
        assertThat(out.get(0).retrieverEngineType()).isEqualTo("opensearch");
        assertThat(out.get(0).results().get(0).matchType)
                .isEqualTo(com.ragagent.retrieval.engine.EngineTypes.MATCH_EMBEDDING);
        assertThat(out.get(0).results().get(0).score).isEqualTo(0.87);
    }

    @Test
    @DisplayName("关键词检索：BM25 match + 过滤；dim 缺失走 <base>_* 多索引；TopK 缺省 10")
    void keywordRetrieveShape() throws Exception {
        OpenSearchRetrieveRepository r = newRepo();
        bodyOverrides.put("POST /weknora_*/_search",
                "{\"hits\":{\"hits\":[{\"_id\":\"c1\",\"_score\":3.5,\"_source\":"
                        + "{\"chunk_id\":\"c1\",\"knowledge_id\":\"doc-1\","
                        + "\"knowledge_base_id\":\"kb-1\",\"source_id\":\"c1\","
                        + "\"source_type\":0,\"tag_id\":\"\",\"content\":\"x\","
                        + "\"is_enabled\":true}}]}}");
        RetrieveParams p = new RetrieveParams();
        p.retrieverType = EngineTypes_RETREIVER_KEYWORDS;
        p.query = "退款";
        p.topK = 0; // 缺省 10（WARN caller bug）
        List<RetrieveResult> out = r.retrieve(p);

        Captured search = captured.stream()
                .filter(c -> c.path().equals("/weknora_*/_search")).findFirst().orElseThrow();
        JsonNode body = MAPPER.readTree(search.body());
        assertThat(body.path("size").asInt()).isEqualTo(10).as("TopK 缺省 10");
        // must 序：无显式过滤时为 [is_enabled 隐含子句, match content]
        assertThat(body.path("query").path("bool").path("must").get(1).path("match")
                .path("content").asText()).isEqualTo("退款");
        assertThat(out.get(0).results().get(0).matchType)
                .isEqualTo(com.ragagent.retrieval.engine.EngineTypes.MATCH_KEYWORDS);
    }

    // ── 删除 / 移动 / 批量更新 ──────────────────────────────────────────────

    @Test
    @DisplayName("删除：terms delete_by_query + refresh=true；cap 1000")
    void deleteShapes() throws Exception {
        OpenSearchRetrieveRepository r = newRepo();
        r.deleteBySourceIdList(List.of("s1", "s2"), 2, "");

        Captured del = captured.stream()
                .filter(c -> c.path().startsWith("/weknora_2/_delete_by_query"))
                .findFirst().orElseThrow();
        assertThat(del.path()).isEqualTo("/weknora_2/_delete_by_query?refresh=true");
        JsonNode body = MAPPER.readTree(del.body());
        assertThat(body.path("query").path("terms").path("source_id").get(0).asText())
                .isEqualTo("s1");

        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            tooMany.add("s" + i);
        }
        assertThatThrownBy(() -> r.deleteByChunkIdList(tooMany, 2, ""))
                .isInstanceOfSatisfying(OpenSearchDriverException.class, e ->
                        assertThat(e.kind())
                                .isEqualTo(OpenSearchDriverException.Kind.BATCH_TOO_LARGE));
    }

    @Test
    @DisplayName("move：跨维 update_by_query、painless 脚本 + params 绑定、完整性校验")
    void moveShape() throws Exception {
        OpenSearchRetrieveRepository r = newRepo();
        bodyOverrides.put("POST /weknora_*/_update_by_query",
                "{\"total\":3,\"updated\":3,\"timed_out\":false,\"version_conflicts\":0,"
                        + "\"failures\":[]}");
        r.moveKnowledgeIndices("kb-src", "kb-dst", "doc-1", List.of(), 0, "");

        Captured up = captured.stream()
                .filter(c -> c.path().startsWith("/weknora_*/_update_by_query"))
                .findFirst().orElseThrow();
        assertThat(up.path()).contains("refresh=true");
        JsonNode body = MAPPER.readTree(up.body());
        assertThat(body.path("script").path("params").path("target").asText())
                .isEqualTo("kb-dst").as("params 绑定在 script 内（Go 无顶层 params）");
        assertThat(body.path("script").path("source").asText())
                .isEqualTo("ctx._source.knowledge_base_id = params.target;"
                        + " ctx._source.tag_id = '';");
        assertThat(body.path("query").path("bool").path("filter").get(0).path("term")
                .path("knowledge_base_id").asText()).isEqualTo("kb-src");

        // 完整性：updated != total → TRANSPORT
        bodyOverrides.put("POST /weknora_*/_update_by_query",
                "{\"total\":3,\"updated\":2,\"timed_out\":false,\"version_conflicts\":0,"
                        + "\"failures\":[]}");
        assertThatThrownBy(() -> r.moveKnowledgeIndices("kb-src", "kb-dst", "doc-1",
                List.of(), 0, ""))
                .isInstanceOfSatisfying(OpenSearchDriverException.class, e -> {
                    assertThat(e.kind()).isEqualTo(OpenSearchDriverException.Kind.TRANSPORT);
                    assertThat(e.getMessage()).contains("incomplete move document counts");
                });
    }

    @Test
    @DisplayName("批量更新：false 先 true 后、组内 id 排序、常量脚本 + params 绑定")
    void batchUpdateShape() throws Exception {
        OpenSearchRetrieveRepository r = newRepo();
        r.batchUpdateChunkEnabledStatus(new HashMap<>(Map.of(
                "c2", true, "c1", false, "c3", true)));

        List<Captured> updates = captured.stream()
                .filter(c -> c.path().startsWith("/weknora_*/_update_by_query")).toList();
        assertThat(updates).hasSize(2);
        JsonNode first = MAPPER.readTree(updates.get(0).body());
        assertThat(first.path("script").path("params").path("v").asBoolean()).isFalse()
                .as("false 组先发");
        assertThat(first.path("query").path("terms").path("chunk_id").get(0).asText())
                .isEqualTo("c1");
        JsonNode second = MAPPER.readTree(updates.get(1).body());
        assertThat(second.path("script").path("params").path("v").asBoolean()).isTrue();
        assertThat(second.path("query").path("terms").path("chunk_id").get(1).asText())
                .isEqualTo("c3").as("组内 id 排序");

        // tag 分组：字典序
        captured.clear();
        r.batchUpdateChunkTagID(Map.of("c1", "t-b", "c2", "t-a"));
        JsonNode tagBody = MAPPER.readTree(
                captured.get(0).body());
        assertThat(tagBody.path("script").path("params").path("v").asText()).isEqualTo("t-a");
    }

    // ── CopyIndices ────────────────────────────────────────────────────────

    @Test
    @DisplayName("copy：分页扫源 + 三态 SourceID 改写 + 向量按目标 SourceID 回填 + 审计")
    void copyIndicesShape() throws Exception {
        String page = "{\"hits\":{\"hits\":["
                + "{\"_source\":{\"content\":\"x\",\"source_id\":\"c1\",\"source_type\":0,"
                + "\"chunk_id\":\"c1\",\"knowledge_id\":\"doc-1\",\"knowledge_base_id\":\"kb-src\","
                + "\"tag_id\":\"t1\",\"is_enabled\":true,\"is_recommended\":false,"
                + "\"embedding\":[1.0,2.0]}},"
                + "{\"_source\":{\"content\":\"q\",\"source_id\":\"c1-q1\",\"source_type\":0,"
                + "\"chunk_id\":\"c1\",\"knowledge_id\":\"doc-1\",\"knowledge_base_id\":\"kb-src\","
                + "\"tag_id\":\"\",\"is_enabled\":true,\"is_recommended\":false,"
                + "\"embedding\":[3.0,4.0]}},"
                + "{\"_source\":{\"content\":\"o\",\"source_id\":\"other\",\"source_type\":0,"
                + "\"chunk_id\":\"c9\",\"knowledge_id\":\"doc-9\",\"knowledge_base_id\":\"kb-src\","
                + "\"tag_id\":\"\",\"is_enabled\":true,\"is_recommended\":false}}]}}";
        bodyOverrides.put("POST /weknora_2/_search", page);
        List<String> auditEvents = new ArrayList<>();
        OpenSearchRetrieveRepository r = newRepo();
        r.withAuditSink(new OpenSearchRetrieveRepository.AuditSink() {
            @Override public void emitIndexCreated(String alias, int dim) {
            }

            @Override public void emitReindexExecuted(String src, String dst, long docs) {
                auditEvents.add("reindex:" + docs);
            }
        });
        r.copyIndices("kb-src", Map.of("doc-1", "doc-t"),
                Map.of("c1", "c1t"), "kb-dst", 2, "");

        assertThat(auditEvents).containsExactly("reindex:2").as("未映射行跳过（3 行 → 2 条）");
        Captured bulk = captured.stream()
                .filter(c -> c.path().equals("/_bulk")).findFirst().orElseThrow();
        String[] lines = bulk.body().split("\n");
        assertThat(lines).hasSize(4);
        // 本块：source_id=c1 → c1t；生成问题：c1-q1 → c1t-q1；向量键 = 目标 source id
        JsonNode doc1 = MAPPER.readTree(lines[1]);
        assertThat(doc1.path("source_id").asText()).isEqualTo("c1t");
        assertThat(doc1.path("chunk_id").asText()).isEqualTo("c1t");
        assertThat(doc1.path("knowledge_id").asText()).isEqualTo("doc-t");
        assertThat(doc1.path("tag_id").asText()).isEqualTo("t1");
        JsonNode action2 = MAPPER.readTree(lines[2]);
        assertThat(action2.path("index").path("_id").asText()).isEqualTo("c1t");
        JsonNode doc2 = MAPPER.readTree(lines[3]);
        assertThat(doc2.path("source_id").asText()).isEqualTo("c1t-q1");
        assertThat(doc2.path("embedding").get(0).asDouble()).isEqualTo(3.0);
    }

    // ── 估算 + 纯函数 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("EstimateStorageSize：保守下界 n*(1024+4*768+128)")
    void estimate() {
        OpenSearchRetrieveRepository r = newRepo();
        assertThat(r.estimateStorageSize(List.of(), null)).isEqualTo(0);
        assertThat(r.estimateStorageSize(List.of(info("s", "c"), info("s2", "c2")), null))
                .isEqualTo(2 * (1024 + 4 * 768 + 128));
    }

    @Test
    @DisplayName("纯函数：parseMajorMinor / sanitizeIndexName / transformSourceId / effectiveTopK")
    void pureFunctions() {
        assertThat(OpenSearchRetrieveRepository.parseMajorMinor("3.3.2")).isEqualTo(new int[] {3, 3});
        assertThat(OpenSearchRetrieveRepository.parseMajorMinor("2.10.0-SNAPSHOT"))
                .isEqualTo(new int[] {2, 10});
        assertThat(OpenSearchRetrieveRepository.parseMajorMinor("2.10")).isEqualTo(new int[] {2, 10});
        assertThat(OpenSearchRetrieveRepository.parseMajorMinor("bogus")).isEqualTo(new int[] {0, 0});

        assertThat(OpenSearchRetrieveRepository.sanitizeIndexName("weknora_abcdef012345"))
                .isEqualTo("weknora_abcdef012345");
        assertThatThrownBy(() -> OpenSearchRetrieveRepository.sanitizeIndexName("Bad_Name"))
                .isInstanceOf(OpenSearchDriverException.class);
        assertThatThrownBy(() -> OpenSearchRetrieveRepository.sanitizeIndexName("has*wild"))
                .isInstanceOf(OpenSearchDriverException.class);

        assertThat(OpenSearchRetrieveRepository.transformSourceId("c1", "c1", "t1"))
                .isEqualTo("t1");
        assertThat(OpenSearchRetrieveRepository.transformSourceId("c1-q1", "c1", "t1"))
                .isEqualTo("t1-q1");
        String fallback = OpenSearchRetrieveRepository.transformSourceId("xyz", "c1", "t1");
        assertThat(fallback).doesNotContain("t1").hasSize(36).as("兜底新 UUID");

        RetrieveParams p = new RetrieveParams();
        assertThat(OpenSearchRetrieveRepository.effectiveTopK(p)).isEqualTo(10);
        p.topK = 20000;
        assertThat(OpenSearchRetrieveRepository.effectiveTopK(p)).isEqualTo(10000);
        p.topK = 7;
        assertThat(OpenSearchRetrieveRepository.effectiveTopK(p)).isEqualTo(7);
    }

    private static final String EngineTypes_RETREIVER_VECTOR =
            com.ragagent.retrieval.engine.EngineTypes.RETRIEVER_VECTOR;
    private static final String EngineTypes_RETREIVER_KEYWORDS =
            com.ragagent.retrieval.engine.EngineTypes.RETRIEVER_KEYWORDS;
}
