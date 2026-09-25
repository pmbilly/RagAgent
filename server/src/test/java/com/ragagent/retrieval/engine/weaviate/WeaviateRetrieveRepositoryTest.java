package com.ragagent.retrieval.engine.weaviate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.retrieval.engine.EngineTypes;
import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;
import com.ragagent.retrieval.engine.weaviate.WeaviateRestClient.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Weaviate 驱动（W5γ4.13）对照 Go {@code retriever/weaviate/} 全包：惰性建类
 * （命名向量 + gse 分词 + 可过滤属性 + cluster 选项）、单对象/批量创建、批量删除、
 * <b>merge 更新</b>（有意修正：Go 的 PUT 会清属性与向量）、GraphQL 向量/关键词检索的
 * 解析、CopyIndices 的 offset 分页与命名向量回搬、move 的 seen-set 循环。
 *
 * <p>桩逐请求断言 HTTP 形状（该批无 golden 面）；GraphQL 串的字节契约在
 * {@code WeaviateGqlTest}（Go 实录）。真实服务端验证见 {@code WeaviateDriverLocalIT}。</p>
 */
class WeaviateRetrieveRepositoryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Captured(String method, String path, String body) {

        JsonNode json() {
            try {
                return MAPPER.readTree(body);
            } catch (Exception e) {
                throw new AssertionError("not JSON: " + body, e);
            }
        }
    }

    private final List<Captured> captured = new CopyOnWriteArrayList<>();
    private final Map<String, String> responses = new HashMap<>();
    private final Map<String, Integer> statuses = new HashMap<>();
    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(null);
        server.start();
        base = "127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        captured.add(new Captured(method, path, raw));
        String key = method + " " + path;
        String body = responses.get(key);
        int status = statuses.getOrDefault(key, 200);
        if (body == null) {
            if (method.equals("GET") && path.startsWith("/v1/schema/")) {
                body = "{\"class\":\"x\"}";
            } else if (method.equals("GET") && path.equals("/v1/schema")) {
                body = "{\"classes\":[]}";
            } else if (method.equals("POST") && path.equals("/v1/graphql")) {
                body = "{\"data\":{\"Get\":{}}}";
            } else {
                body = "{}";
            }
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private WeaviateRetrieveRepository repo(String baseName) {
        return new WeaviateRetrieveRepository(
                new WeaviateRestClient(base, "http", "", null), baseName, 0, 0);
    }

    private static Map<String, Object> embeddings(Object... sourceIdAndVectorPairs) {
        Map<String, float[]> map = new LinkedHashMap<>();
        for (int i = 0; i < sourceIdAndVectorPairs.length; i += 2) {
            map.put((String) sourceIdAndVectorPairs[i], (float[]) sourceIdAndVectorPairs[i + 1]);
        }
        Map<String, Object> params = new HashMap<>();
        params.put("embedding", map);
        return params;
    }

    private static IndexInfo info(String sourceId, String chunkId) {
        IndexInfo info = new IndexInfo();
        info.sourceId = sourceId;
        info.chunkId = chunkId;
        info.content = "hello";
        info.knowledgeId = "k1";
        info.knowledgeBaseId = "kb1";
        info.tagId = "";
        info.isEnabled = true;
        return info;
    }

    private Captured byMethodPath(String method, String pathSuffix) {
        return captured.stream().filter(c -> c.method().equals(method)
                && c.path().endsWith(pathSuffix)).findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no " + method + " …" + pathSuffix + ": " + captured));
    }

    private static final String CHUNK = "11111111-1111-1111-1111-111111111111";

    // ── 建类与写入 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("惰性建类：判存 → POST /v1/schema（命名向量/gse/可过滤属性 + cluster 选项）；缓存生效")
    void ensureCollectionCreatesClass() throws Exception {
        statuses.put("GET /v1/schema/Weknora_embeddings_3", 404);
        WeaviateRetrieveRepository repo = new WeaviateRetrieveRepository(
                new WeaviateRestClient(base, "http", "", null), "Weknora_embeddings", 2, 3);
        repo.save(info(CHUNK, CHUNK), embeddings(CHUNK, new float[] {1f, 2f, 3f}));

        Captured create = byMethodPath("POST", "/v1/schema");
        JsonNode body = create.json();
        assertThat(body.path("class").asText()).isEqualTo("Weknora_embeddings_3");
        assertThat(body.path("description").asText())
                .isEqualTo("WeKnora embeddings collection with dimension 3");
        JsonNode embedding = body.path("vectorConfig").path("embedding");
        assertThat(embedding.path("vectorIndexType").asText()).isEqualTo("hnsw");
        assertThat(embedding.path("vectorIndexConfig").path("distance").asText())
                .isEqualTo("cosine");
        assertThat(embedding.path("vectorIndexConfig").path("efConstruction").asInt())
                .isEqualTo(128);
        assertThat(embedding.path("vectorIndexConfig").path("maxConnections").asInt())
                .isEqualTo(32);
        assertThat(embedding.path("vectorIndexConfig").path("ef").asInt()).isEqualTo(64);
        assertThat(embedding.path("vectorizer").has("none")).isTrue();
        JsonNode content = body.path("properties").get(0);
        assertThat(content.path("name").asText()).isEqualTo("content");
        assertThat(content.path("tokenization").asText()).isEqualTo("gse");
        JsonNode enabled = body.path("properties").get(7);
        assertThat(enabled.path("name").asText()).isEqualTo("is_enabled");
        assertThat(enabled.path("dataType").get(0).asText()).isEqualTo("boolean");
        assertThat(enabled.path("indexFilterable").asBoolean()).isTrue();
        assertThat(body.path("replicationConfig").path("factor").asInt()).isEqualTo(2);
        assertThat(body.path("shardingConfig").path("desiredCount").asInt()).isEqualTo(3);

        // 第二次写入不再判存/建类（缓存）
        int before = captured.size();
        repo.save(info(CHUNK, CHUNK), embeddings(CHUNK, new float[] {1f, 2f, 3f}));
        assertThat(captured.size() - before).isEqualTo(1); // 只剩 POST /v1/objects
    }

    @Test
    @DisplayName("单对象创建：POST /v1/objects（id=chunkID + properties 全字段 + vector）；空向量拒收")
    void saveObjectShape() throws Exception {
        WeaviateRetrieveRepository repo = repo("Weknora_embeddings");
        repo.save(info(CHUNK, CHUNK), embeddings(CHUNK, new float[] {0.5f}));

        Captured created = byMethodPath("POST", "/v1/objects");
        JsonNode body = created.json();
        assertThat(body.path("class").asText()).isEqualTo("Weknora_embeddings_1");
        assertThat(body.path("id").asText()).isEqualTo(CHUNK);
        assertThat(body.path("vector").get(0).asDouble()).isEqualTo(0.5);
        JsonNode props = body.path("properties");
        assertThat(props.path("source_id").asText()).isEqualTo(CHUNK);
        assertThat(props.path("chunk_id").asText()).isEqualTo(CHUNK);
        assertThat(props.path("knowledge_id").asText()).isEqualTo("k1");
        assertThat(props.path("knowledge_base_id").asText()).isEqualTo("kb1");
        assertThat(props.path("is_enabled").asBoolean()).isTrue();
        assertThat(props.path("source_type").asInt()).isEqualTo(0);

        assertThatThrownBy(() -> repo.save(info("s1", "c1"), embeddings()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("empty embedding vector for chunk ID: c1");
    }

    @Test
    @DisplayName("批量创建：按维度分组 + POST /v1/batch/objects（fields=[ALL]）；逐对象错误只记日志")
    void batchSaveShape() throws Exception {
        responses.put("POST /v1/batch/objects",
                "[{\"result\":{\"status\":\"FAILED\",\"errors\":{\"error\":[{\"message\":\"boom\"}]}}}]");
        WeaviateRetrieveRepository repo = repo("Weknora_embeddings");
        List<IndexInfo> infos = new ArrayList<>();
        Map<String, float[]> vectors = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) {
            String id = UUID.randomUUID().toString();
            infos.add(info(id, id));
            vectors.put(id, new float[] {1f, 2f});
        }
        Map<String, Object> params = new HashMap<>();
        params.put("embedding", vectors);
        repo.batchSave(infos, params);

        Captured batch = byMethodPath("POST", "/v1/batch/objects");
        JsonNode body = batch.json();
        assertThat(body.path("fields").get(0).asText()).isEqualTo("ALL");
        assertThat(body.path("objects")).hasSize(3);
        assertThat(body.path("objects").get(0).path("id").asText()).isIn(vectors.keySet());
    }

    // ── 删除与更新 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("批量删除：DELETE /v1/batch/objects（output=minimal + ContainsAny + valueTextArray）")
    void batchDeleteShape() throws Exception {
        WeaviateRetrieveRepository repo = repo("Weknora_embeddings");
        repo.deleteByChunkIdList(List.of(CHUNK), 3, "document");
        Captured del = byMethodPath("DELETE", "/v1/batch/objects");
        JsonNode body = del.json();
        assertThat(body.path("output").asText()).isEqualTo("minimal");
        assertThat(body.path("match").path("class").asText())
                .isEqualTo("Weknora_embeddings_3");
        JsonNode where = body.path("match").path("where");
        assertThat(where.path("operator").asText()).isEqualTo("ContainsAny");
        assertThat(where.path("path").get(0).asText()).isEqualTo("chunk_id");
        assertThat(where.path("valueTextArray").get(0).asText()).isEqualTo(CHUNK);
    }

    @Test
    @DisplayName("批量更新用 PATCH merge（有意修正）：只发单字段 + 集合前缀过滤 + 失败不冒泡")
    void batchUpdateUsesMerge() throws Exception {
        responses.put("GET /v1/schema",
                "{\"classes\":[{\"class\":\"Weknora_embeddings_3\"},"
                        + "{\"class\":\"other_base\"}]}");
        statuses.put("PATCH /v1/objects/Weknora_embeddings_3/" + CHUNK, 204);
        WeaviateRetrieveRepository repo = repo("Weknora_embeddings");
        Map<String, Boolean> status = new LinkedHashMap<>();
        status.put(CHUNK, false);
        repo.batchUpdateChunkEnabledStatus(status);

        Captured patch = byMethodPath("PATCH", "/v1/objects/Weknora_embeddings_3/" + CHUNK);
        JsonNode body = patch.json();
        assertThat(body.path("class").asText()).isEqualTo("Weknora_embeddings_3");
        assertThat(body.path("id").asText()).isEqualTo(CHUNK);
        assertThat(body.path("properties").path("is_enabled").asBoolean()).isFalse();
        assertThat(body.path("properties").size()).isEqualTo(1); // merge：只发变更字段
        assertThat(captured.stream().noneMatch(c -> c.path().contains("other_base"))).isTrue();

        // 逐对象失败只记日志（照 Go）
        statuses.put("PATCH /v1/objects/Weknora_embeddings_3/" + CHUNK, 500);
        repo.batchUpdateChunkTagID(Map.of(CHUNK, "t9"));
    }

    // ── 检索 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("向量检索：GraphQL 串逐字发出、certainty→score、类不存在短路")
    void vectorRetrieve() throws Exception {
        responses.put("POST /v1/graphql",
                "{\"data\":{\"Get\":{\"Weknora_embeddings_2\":[{\"content\":\"hello\","
                        + "\"source_id\":\"s1\",\"source_type\":0,\"chunk_id\":\"c1\","
                        + "\"knowledge_id\":\"k1\",\"knowledge_base_id\":\"kb1\",\"tag_id\":\"\","
                        + "\"_additional\":{\"id\":\"p1\",\"certainty\":0.93}}]}}}");
        WeaviateRetrieveRepository repo = repo("Weknora_embeddings");
        RetrieveParams params = new RetrieveParams();
        params.retrieverType = EngineTypes.RETRIEVER_VECTOR;
        params.embedding = new float[] {0.5f, -0.25f};
        params.knowledgeBaseIds = List.of("kb1");
        params.topK = 5;
        params.threshold = 0.7;

        List<RetrieveResult> results = repo.retrieve(params);

        Captured gql = byMethodPath("POST", "/v1/graphql");
        String query = gql.json().path("query").asText();
        assertThat(query).isEqualTo(
                "{Get {Weknora_embeddings_2 (where:{operator: And operands:[{operator: Equal "
                        + "path: [\"is_enabled\"] valueBoolean: true},{operator: ContainsAny "
                        + "path: [\"knowledge_base_id\"] valueText: [\"kb1\"]}]}, "
                        + "nearVector:{certainty: 0.7 vector: [0.5,-0.25]}, limit: 5) "
                        + "{content source_id source_type chunk_id knowledge_id "
                        + "knowledge_base_id tag_id _additional{id certainty}}}}");
        assertThat(results.get(0).results()).hasSize(1);
        assertThat(results.get(0).results().get(0).score).isEqualTo(0.93);
        assertThat(results.get(0).results().get(0).matchType)
                .isEqualTo(EngineTypes.MATCH_EMBEDDING);
        assertThat(results.get(0).results().get(0).sourceId).isEqualTo("s1");

        // 类不存在 → 空结果且不发 GraphQL
        int gqlCalls = captured.stream()
                .filter(c -> c.method().equals("POST") && c.path().equals("/v1/graphql")).toList()
                .size();
        statuses.put("GET /v1/schema/Weknora_embeddings_2", 404);
        WeaviateRetrieveRepository missing = repo("Weknora_embeddings");
        assertThat(missing.retrieve(params).get(0).results()).isEmpty();
        assertThat(captured.stream().filter(c -> c.method().equals("POST")
                && c.path().equals("/v1/graphql")).toList()).hasSize(gqlCalls);
    }

    @Test
    @DisplayName("关键词检索：跨集合 BM25 + score 恒 1.0 + TopK 截断；单集合失败直接抛（照 Go）")
    void keywordsRetrieve() throws Exception {
        responses.put("GET /v1/schema",
                "{\"classes\":[{\"class\":\"Weknora_embeddings_2\"},"
                        + "{\"class\":\"other_base\"}]}");
        // score 用真服务端的字符串形态（Go 实录），并混一条缺失 score 的
        responses.put("POST /v1/graphql",
                "{\"data\":{\"Get\":{\"Weknora_embeddings_2\":["
                        + "{\"content\":\"a\",\"chunk_id\":\"c1\",\"_additional\":{\"id\":\"p1\","
                        + "\"score\":\"0.48952064\"}},"
                        + "{\"content\":\"b\",\"chunk_id\":\"c2\",\"_additional\":{\"id\":\"p2\","
                        + "\"score\":\"0.16285032\"}},"
                        + "{\"content\":\"c\",\"chunk_id\":\"c3\",\"_additional\":{\"id\":\"p3\"}}]}}}");
        WeaviateRetrieveRepository repo = repo("Weknora_embeddings");
        RetrieveParams params = new RetrieveParams();
        params.retrieverType = EngineTypes.RETRIEVER_KEYWORDS;
        params.query = "中文 检索";
        params.topK = 2;

        List<RetrieveResult> results = repo.retrieve(params);

        assertThat(results.get(0).results()).hasSize(2); // 截 TopK
        assertThat(results.get(0).results().get(0).score).isEqualTo(1.0);
        assertThat(results.get(0).results().get(0).matchType)
                .isEqualTo(EngineTypes.MATCH_KEYWORDS);
        assertThat(captured.stream().noneMatch(c -> c.path().contains("other_base"))).isTrue();

        // 单集合失败 → 抛（与 Qdrant 的跳过继续相反，照 Go）
        statuses.put("POST /v1/graphql", 500);
        assertThatThrownBy(() -> repo.retrieve(params))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to search:");
    }

    @Test
    @DisplayName("GraphQL 级错误：转成 graphql search failed: <first message>")
    void graphqlErrorsBecomeFailure() throws Exception {
        responses.put("POST /v1/graphql",
                "{\"errors\":[{\"message\":\"boom\"}]}");
        WeaviateRetrieveRepository repo = repo("Weknora_embeddings");
        RetrieveParams params = new RetrieveParams();
        params.retrieverType = EngineTypes.RETRIEVER_VECTOR;
        params.embedding = new float[] {1f};
        params.topK = 3;
        assertThatThrownBy(() -> repo.retrieve(params))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("graphql search failed: boom");
    }

    @Test
    @DisplayName("检索类型分派：未知类型 → Go 原文")
    void invalidRetrieverType() {
        WeaviateRetrieveRepository repo = repo("Weknora_embeddings");
        RetrieveParams params = new RetrieveParams();
        params.retrieverType = "bogus";
        assertThatThrownBy(() -> repo.retrieve(params))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("invalid retriever type: bogus");
    }

    // ── CopyIndices / move ────────────────────────────────────────────────

    @Test
    @DisplayName("CopyIndices：offset 分页 + 命名向量回搬 + 三态 SourceID + isEnabled 恒 true")
    void copyIndices() throws Exception {
        String c1 = UUID.randomUUID().toString();
        String c2 = UUID.randomUUID().toString();
        responses.put("POST /v1/graphql",
                "{\"data\":{\"Get\":{\"Weknora_embeddings_2\":["
                        + "{\"content\":\"a\",\"source_id\":\"" + c1 + "\",\"source_type\":0,"
                        + "\"chunk_id\":\"" + c1 + "\",\"knowledge_id\":\"k1\","
                        + "\"knowledge_base_id\":\"srcKb\",\"tag_id\":\"t\","
                        + "\"_additional\":{\"id\":\"p1\",\"vectors\":{\"embedding\":[0.5,0.25]}}},"
                        + "{\"content\":\"b\",\"source_id\":\"" + c1 + "-q7\",\"source_type\":0,"
                        + "\"chunk_id\":\"" + c1 + "\",\"knowledge_id\":\"k1\","
                        + "\"knowledge_base_id\":\"srcKb\",\"tag_id\":\"\","
                        + "\"_additional\":{\"id\":\"p2\",\"vectors\":{\"embedding\":[0.1]}}}"
                        + "]}}}");
        responses.put("PUT /v1/objects", null);
        WeaviateRetrieveRepository repo = repo("Weknora_embeddings");
        repo.copyIndices("srcKb", Map.of("k1", "tk1"), Map.of(c1, c2), "targetKb", 2, "document");

        Captured gql = byMethodPath("POST", "/v1/graphql");
        assertThat(gql.json().path("query").asText()).contains("limit: 64, offset: 0")
                .contains("_additional{id vectors{embedding}}");
        Captured batch = byMethodPath("POST", "/v1/batch/objects");
        JsonNode objects = batch.json().path("objects");
        assertThat(objects).hasSize(2);
        JsonNode first = objects.get(0);
        assertThat(first.path("id").asText()).matches("[0-9a-f-]{36}");
        assertThat(first.path("properties").path("source_id").asText()).isEqualTo(c2);
        assertThat(first.path("properties").path("chunk_id").asText()).isEqualTo(c2);
        assertThat(first.path("properties").path("knowledge_id").asText()).isEqualTo("tk1");
        assertThat(first.path("properties").path("knowledge_base_id").asText())
                .isEqualTo("targetKb");
        assertThat(first.path("properties").path("is_enabled").asBoolean()).isTrue();
        assertThat(first.path("vector").get(0).asDouble()).isEqualTo(0.5);
        assertThat(objects.get(1).path("properties").path("source_id").asText())
                .isEqualTo(c2 + "-q7");

        // 空映射 → 短路
        WeaviateRetrieveRepository untouched = repo("Weknora_embeddings");
        untouched.copyIndices("srcKb", Map.of("k1", "tk1"), Map.of(), "targetKb", 2, "document");
    }

    @Test
    @DisplayName("move：where 列举 → 逐个 merge 重写 kb_id/tag_id → 空页收束；重复 id 报 no progress")
    void moveIndices() throws Exception {
        String id1 = UUID.randomUUID().toString();
        responses.put("POST /v1/graphql",
                "{\"data\":{\"Get\":{\"Weknora_embeddings_2\":[{\"_additional\":{\"id\":\""
                        + id1 + "\"}}]}}}");
        statuses.put("PATCH /v1/objects/Weknora_embeddings_2/" + id1, 204);
        WeaviateRetrieveRepository repo = repo("Weknora_embeddings");

        // 第二页返回同样的 id → no progress（循环只跑一轮即抓到）
        assertThatThrownBy(() -> repo.moveKnowledgeIndices("srcKb", "targetKb", "k1",
                List.of(), 2, "document"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("move indices made no progress for " + id1);

        Captured patch = byMethodPath("PATCH", "/v1/objects/Weknora_embeddings_2/" + id1);
        assertThat(patch.json().path("properties").path("knowledge_base_id").asText())
                .isEqualTo("targetKb");
        assertThat(patch.json().path("properties").path("tag_id").asText()).isEmpty();

        // 空页 → 正常收束
        responses.put("POST /v1/graphql",
                "{\"data\":{\"Get\":{\"Weknora_embeddings_2\":[]}}}");
        repo.moveKnowledgeIndices("srcKb", "targetKb", "k1", List.of(), 2, "document");
    }

    // ── 纯函数 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("存储估算：payload 与 tag_id 无关；HNSW 512（M=32）只在非 null 向量时计入")
    void estimateStorageSize() {
        WeaviateVectorEmbedding row = new WeaviateVectorEmbedding();
        row.content = "中";
        row.sourceId = "s";
        row.chunkId = "c";
        row.knowledgeId = "k";
        row.knowledgeBaseId = "kb";
        row.embedding = new float[] {1f, 2f};
        assertThat(WeaviateRetrieveRepository.calculateStorageSize(row))
                .isEqualTo((3 + 1 + 1 + 1 + 2 + 8) + 8 + 512 + 24);

        WeaviateVectorEmbedding noVector = new WeaviateVectorEmbedding();
        assertThat(WeaviateRetrieveRepository.calculateStorageSize(noVector))
                .isEqualTo(8 + 24);

        WeaviateVectorEmbedding empty = new WeaviateVectorEmbedding();
        empty.embedding = new float[0];
        assertThat(WeaviateRetrieveRepository.calculateStorageSize(empty))
                .isEqualTo(8 + 512 + 24); // 非 null 空数组也计（照 Go 的 nil 判定）
    }

    @Test
    @DisplayName("类名解析：prefix > name > 缺省（Go 原文拼写 Weknora_embeddings）")
    void resolveCollectionName() {
        com.ragagent.vectorstore.domain.IndexConfig withPrefix =
                new com.ragagent.vectorstore.domain.IndexConfig();
        withPrefix.collectionPrefix = "pref";
        withPrefix.collectionName = "name";
        assertThat(WeaviateRetrieveRepository.resolveCollectionName(withPrefix)).isEqualTo("pref");

        com.ragagent.vectorstore.domain.IndexConfig withName =
                new com.ragagent.vectorstore.domain.IndexConfig();
        withName.collectionName = "name";
        assertThat(WeaviateRetrieveRepository.resolveCollectionName(withName)).isEqualTo("name");
        assertThat(WeaviateRetrieveRepository.resolveCollectionName(null))
                .isEqualTo("Weknora_embeddings");
    }

    @Test
    @DisplayName("SourceID 三态改写")
    void translateSourceId() {
        assertThat(WeaviateRetrieveRepository.translateSourceId("c1", "c1", "tc1"))
                .isEqualTo("tc1");
        assertThat(WeaviateRetrieveRepository.translateSourceId("c1-q7", "c1", "tc1"))
                .isEqualTo("tc1-q7");
        assertThat(WeaviateRetrieveRepository.translateSourceId("other", "c1", "tc1"))
                .matches("[0-9a-f-]{36}");
    }

    @Test
    @DisplayName("探针：ready 200 + meta version；ready 非 2xx → 异常")
    void testConnection() throws Exception {
        responses.put("GET /v1/meta", "{\"version\":\"1.28.4\"}");
        assertThat(WeaviateRetrieveRepository.testConnection(base, "http", "", null))
                .isEqualTo("1.28.4");

        statuses.put("GET /v1/.well-known/ready", 503);
        assertThatThrownBy(() -> WeaviateRetrieveRepository.testConnection(base, "http", "", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("weaviate server not ready");
    }

    @Test
    @DisplayName("base URL：scheme/host 缺省照 Go（http + weaviate:8080）")
    void baseUrlDefaults() {
        assertThat(WeaviateRestClient.buildBaseUrl(null, null))
                .isEqualTo("http://weaviate:8080");
        assertThat(WeaviateRestClient.buildBaseUrl("w:8080", "https"))
                .isEqualTo("https://w:8080");
        assertThat(Json.object().isEmpty()).isTrue();
    }
}
