package com.ragagent.retrieval.engine.milvus;

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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Milvus 驱动（W5γ4.14）对照 Go {@code retriever/milvus/} 全包：惰性建集合
 * （BM25 函数 + 稀疏列 + indexParams 内联 + load）、行式 Upsert、{@code field in [...]}
 * 删除、enabled/tag 的"查整行→改字段→回写"（enabled 失败聚合冒泡 / tag 失败只 WARN）、
 * 向量 search（radius 范围）与 BM25 文本 search（data 为字符串）、CopyIndices 的 offset
 * 分页与三态 SourceID、move 的 seen 守卫。
 *
 * <p>桩逐请求断言 HTTP 形状与信封解析（{@code code != 0} → 异常）；真实服务端验证见
 * {@code MilvusDriverLocalIT}。</p>
 */
class MilvusRetrieveRepositoryTest {

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
    private String addr;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(null);
        server.start();
        addr = "127.0.0.1:" + server.getAddress().getPort();
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
            if (path.endsWith("/collections/has")) {
                body = "{\"code\":0,\"data\":{\"has\":true}}";
            } else if (path.endsWith("/collections/list")) {
                body = "{\"code\":0,\"data\":[]}";
            } else if (path.endsWith("/entities/query")) {
                body = "{\"code\":0,\"data\":[]}";
            } else if (path.endsWith("/entities/search")) {
                body = "{\"code\":0,\"data\":[],\"topks\":[0]}";
            } else {
                body = "{\"code\":0,\"data\":{}}";
            }
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private MilvusRetrieveRepository repo(String baseName) {
        return new MilvusRetrieveRepository(
                new MilvusRestClient(addr, "", "", "", null), baseName, "IP", 0, 0);
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

    private Captured last(String pathSuffix) {
        return captured.stream().filter(c -> c.path().endsWith(pathSuffix))
                .reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("no …" + pathSuffix + ": " + captured));
    }

    private static final String CHUNK = "11111111-1111-1111-1111-111111111111";

    // ── 建集合 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("建集合：schema（VarChar PK/FloatVector/gse 无关的 analyzer + BM25 函数 + 稀疏列）+ indexParams")
    void createCollectionBody() throws Exception {
        responses.put("POST /v2/vectordb/collections/has", "{\"code\":0,\"data\":{\"has\":false}}");
        repo("weknora_embeddings").save(info(CHUNK, CHUNK), embeddings(CHUNK, new float[] {1f, 2f}));

        Captured create = last("/collections/create");
        JsonNode body = create.json();
        assertThat(body.path("collectionName").asText()).isEqualTo("weknora_embeddings_2");
        JsonNode schema = body.path("schema");
        assertThat(schema.path("autoID").asBoolean()).isFalse();
        JsonNode fields = schema.path("fields");
        assertThat(fields.get(0).path("fieldName").asText()).isEqualTo("id");
        assertThat(fields.get(0).path("isPrimary").asBoolean()).isTrue();
        assertThat(fields.get(0).path("elementTypeParams").path("max_length").asInt())
                .isEqualTo(1024);
        assertThat(fields.get(1).path("dataType").asText()).isEqualTo("FloatVector");
        assertThat(fields.get(1).path("elementTypeParams").path("dim").asInt()).isEqualTo(2);
        JsonNode content = fields.get(2);
        assertThat(content.path("elementTypeParams").path("enable_analyzer").asBoolean())
                .isTrue();
        assertThat(content.path("elementTypeParams").path("enable_match").asBoolean()).isTrue();
        assertThat(fields.get(3).path("dataType").asText()).isEqualTo("SparseFloatVector");
        assertThat(fields.get(10).path("fieldName").asText()).isEqualTo("is_enabled");
        assertThat(fields.get(10).path("dataType").asText()).isEqualTo("Bool");

        JsonNode function = schema.path("functions").get(0);
        assertThat(function.path("name").asText()).isEqualTo("text_bm25_emb");
        assertThat(function.path("type").asText()).isEqualTo("BM25");
        assertThat(function.path("inputFieldNames").get(0).asText()).isEqualTo("content");
        assertThat(function.path("outputFieldNames").get(0).asText()).isEqualTo("content_sparse");

        JsonNode indexes = body.path("indexParams");
        assertThat(indexes.get(0).path("fieldName").asText()).isEqualTo("embedding");
        assertThat(indexes.get(0).path("metricType").asText()).isEqualTo("IP");
        assertThat(indexes.get(0).path("indexType").asText()).isEqualTo("HNSW");
        assertThat(indexes.get(0).path("params").path("M").asInt()).isEqualTo(16);
        assertThat(indexes.get(0).path("params").path("efConstruction").asInt()).isEqualTo(128);
        assertThat(indexes.get(1).path("metricType").asText()).isEqualTo("BM25");
        assertThat(indexes.get(1).path("indexType").asText()).isEqualTo("AUTOINDEX");
        assertThat(indexes.get(2).path("fieldName").asText()).isEqualTo("chunk_id");

        // 建完即 load（照 Go：每次 ensure 都 load）
        assertThat(last("/collections/load").json().path("collectionName").asText())
                .isEqualTo("weknora_embeddings_2");
    }

    @Test
    @DisplayName("建集合：indexCfg.shardsNum>0 才带 shardsNum（服务端忽略是已知差异，配置面照传）")
    void shardsNumPropagation() {
        MilvusRestClient client = new MilvusRestClient(addr, "", "", "", null);
        JsonNode withShards = new MilvusRetrieveRepository(client, "base", "IP", 4, 0)
                .collectionBody("base_2", 2);
        assertThat(withShards.path("shardsNum").asInt()).isEqualTo(4);
        JsonNode withoutShards = new MilvusRetrieveRepository(client, "base", "IP", 0, 0)
                .collectionBody("base_2", 2);
        assertThat(withoutShards.has("shardsNum")).isFalse();
    }

    @Test
    @DisplayName("集合已存在：只 load + upsert（不重复建）")
    void existingCollectionOnlyLoads() throws Exception {
        repo("weknora_embeddings").save(info(CHUNK, CHUNK), embeddings(CHUNK, new float[] {1f}));
        assertThat(captured.stream().noneMatch(c -> c.path().endsWith("/collections/create")))
                .isTrue();
        assertThat(captured.stream().anyMatch(c -> c.path().endsWith("/collections/load")))
                .isTrue();
    }

    // ── 写入 / 删除 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("单行 Upsert：行式 JSON（id 新 UUID + 全列）；空向量拒收")
    void upsertRowShape() throws Exception {
        MilvusRetrieveRepository repo = repo("weknora_embeddings");
        repo.save(info(CHUNK, CHUNK), embeddings(CHUNK, new float[] {0.5f, 0.25f}));

        JsonNode body = last("/entities/upsert").json();
        assertThat(body.path("collectionName").asText()).isEqualTo("weknora_embeddings_2");
        JsonNode row = body.path("data").get(0);
        assertThat(row.path("id").asText()).matches("[0-9a-f-]{36}");
        assertThat(row.path("embedding").get(0).asDouble()).isEqualTo(0.5);
        assertThat(row.path("content").asText()).isEqualTo("hello");
        assertThat(row.path("source_id").asText()).isEqualTo(CHUNK);
        assertThat(row.path("chunk_id").asText()).isEqualTo(CHUNK);
        assertThat(row.path("knowledge_id").asText()).isEqualTo("k1");
        assertThat(row.path("knowledge_base_id").asText()).isEqualTo("kb1");
        assertThat(row.path("is_enabled").asBoolean()).isTrue();
        assertThat(row.path("source_type").asInt()).isEqualTo(0);

        assertThatThrownBy(() -> repo.save(info("s1", "c1"), embeddings()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("empty embedding vector for chunk ID: c1");
        // 批量路径照 Go：空向量只 WARN 跳过（不抛、不写）
        int before = captured.size();
        repo.batchSave(List.of(info("s1", "c1")), embeddings());
        assertThat(captured).hasSize(before);
    }

    @Test
    @DisplayName("批量 Upsert：按维度分组（升序）+ 每组一次请求")
    void batchUpsertGroupsByDimension() throws Exception {
        MilvusRetrieveRepository repo = repo("weknora_embeddings");
        List<IndexInfo> infos = new ArrayList<>();
        Map<String, float[]> vectors = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) {
            String id = UUID.randomUUID().toString();
            infos.add(info(id, id));
            vectors.put(id, i == 0 ? new float[] {1f, 2f} : new float[] {1f});
        }
        Map<String, Object> params = new HashMap<>();
        params.put("embedding", vectors);
        repo.batchSave(infos, params);

        List<Captured> upserts = captured.stream()
                .filter(c -> c.path().endsWith("/entities/upsert")).toList();
        assertThat(upserts).hasSize(2);
        assertThat(upserts.get(0).json().path("collectionName").asText())
                .isEqualTo("weknora_embeddings_1");
        assertThat(upserts.get(1).json().path("collectionName").asText())
                .isEqualTo("weknora_embeddings_2");
        assertThat(upserts.get(1).json().path("data")).hasSize(1);
    }

    @Test
    @DisplayName("删除：filter 为 field in [\"…\"]（照 SDK WithStringIDs）")
    void deleteFilter() throws Exception {
        MilvusRetrieveRepository repo = repo("weknora_embeddings");
        repo.deleteByChunkIdList(List.of("a", "b"), 2, "document");
        JsonNode body = last("/entities/delete").json();
        assertThat(body.path("collectionName").asText()).isEqualTo("weknora_embeddings_2");
        assertThat(body.path("filter").asText()).isEqualTo("chunk_id in [\"a\",\"b\"]");

        int before = captured.size();
        repo.deleteByKnowledgeIdList(List.of(), 2, "document");
        assertThat(captured).hasSize(before);
    }

    // ── 批量更新（查整行 → 改字段 → 回写） ────────────────────────────────

    @Test
    @DisplayName("enabled 更新：query 取整行 → 改 is_enabled → Upsert 回写；失败聚合冒泡")
    void enabledUpdate() throws Exception {
        responses.put("POST /v2/vectordb/collections/list",
                "{\"code\":0,\"data\":[\"weknora_embeddings_2\",\"other_base\"]}");
        responses.put("POST /v2/vectordb/entities/query",
                "{\"code\":0,\"data\":[{\"id\":\"" + CHUNK + "\",\"content\":\"c\","
                        + "\"source_id\":\"" + CHUNK + "\",\"source_type\":0,"
                        + "\"chunk_id\":\"" + CHUNK + "\",\"knowledge_id\":\"k1\","
                        + "\"knowledge_base_id\":\"kb1\",\"tag_id\":\"\","
                        + "\"is_enabled\":true,\"embedding\":[0.5,0.25]}]}");
        MilvusRetrieveRepository repo = repo("weknora_embeddings");
        repo.batchUpdateChunkEnabledStatus(Map.of(CHUNK, false));

        Captured query = last("/entities/query");
        assertThat(query.json().path("filter").asText())
                .isEqualTo("chunk_id in [\"" + CHUNK + "\"]");
        assertThat(query.json().path("outputFields").get(0).asText()).isEqualTo("*");
        JsonNode row = last("/entities/upsert").json().path("data").get(0);
        assertThat(row.path("is_enabled").asBoolean()).isFalse();
        assertThat(row.path("embedding").get(0).asDouble()).isEqualTo(0.5); // 向量原样回写
        assertThat(captured.stream().noneMatch(c -> c.path().contains("other_base"))).isTrue();

        // 写失败 → 聚合冒泡（照 errors.Join）
        statuses.put("POST /v2/vectordb/entities/upsert", 500);
        assertThatThrownBy(() -> repo.batchUpdateChunkEnabledStatus(Map.of(CHUNK, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("update enabled chunks in weknora_embeddings_2");
    }

    @Test
    @DisplayName("tag 更新：query 失败只 WARN 继续（照 Go 的 continue）")
    void tagUpdateToleratesFailure() throws Exception {
        responses.put("POST /v2/vectordb/collections/list",
                "{\"code\":0,\"data\":[\"weknora_embeddings_2\"]}");
        statuses.put("POST /v2/vectordb/entities/query", 500);
        repo("weknora_embeddings").batchUpdateChunkTagID(Map.of(CHUNK, "t9"));
        assertThat(captured.stream().noneMatch(c -> c.path().endsWith("/entities/upsert")))
                .isTrue();
    }

    // ── 检索 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("向量检索：data=[[…]] + annsField=embedding + radius + 字面量过滤；distance→score")
    void vectorRetrieve() throws Exception {
        responses.put("POST /v2/vectordb/entities/search",
                "{\"code\":0,\"data\":[{\"id\":\"p1\",\"content\":\"hello\","
                        + "\"source_id\":\"s1\",\"source_type\":0,\"chunk_id\":\"c1\","
                        + "\"knowledge_id\":\"k1\",\"knowledge_base_id\":\"kb1\","
                        + "\"tag_id\":\"\",\"is_enabled\":true,\"embedding\":[1,0],"
                        + "\"distance\":0.93}],\"topks\":[1]}");
        MilvusRetrieveRepository repo = repo("weknora_embeddings");
        RetrieveParams params = new RetrieveParams();
        params.retrieverType = EngineTypes.RETRIEVER_VECTOR;
        params.embedding = new float[] {1f, 0f};
        params.knowledgeBaseIds = List.of("kb1");
        params.excludeChunkIds = List.of("c9");
        params.topK = 5;
        params.threshold = 0.7;

        List<RetrieveResult> results = repo.retrieve(params);

        JsonNode body = last("/entities/search").json();
        assertThat(body.path("data").get(0).get(0).asDouble()).isEqualTo(1.0);
        assertThat(body.path("annsField").asText()).isEqualTo("embedding");
        assertThat(body.path("limit").asInt()).isEqualTo(5);
        assertThat(body.path("outputFields").get(0).asText()).isEqualTo("*");
        assertThat(body.path("searchParams").path("radius").asDouble()).isEqualTo(0.7);
        // 逻辑连接是左结合全括号（照 Go 的 (a) and (b) 形状）
        assertThat(body.path("filter").asText()).isEqualTo(
                "((knowledge_base_id in [\"kb1\"]) and (chunk_id not in [\"c9\"])) "
                        + "and (is_enabled == true)");
        assertThat(results.get(0).results()).hasSize(1);
        assertThat(results.get(0).results().get(0).score).isEqualTo(0.93);
        assertThat(results.get(0).results().get(0).matchType)
                .isEqualTo(EngineTypes.MATCH_EMBEDDING);
        assertThat(results.get(0).results().get(0).sourceId).isEqualTo("s1");
    }

    @Test
    @DisplayName("向量检索：threshold=0 不带 radius；类不存在短路")
    void vectorRetrieveWithoutRadius() throws Exception {
        MilvusRetrieveRepository repo = repo("weknora_embeddings");
        RetrieveParams params = new RetrieveParams();
        params.retrieverType = EngineTypes.RETRIEVER_VECTOR;
        params.embedding = new float[] {1f};
        params.topK = 3;
        repo.retrieve(params);
        assertThat(last("/entities/search").json().has("searchParams")).isFalse();

        responses.put("POST /v2/vectordb/collections/has", "{\"code\":0,\"data\":{\"has\":false}}");
        assertThat(repo.retrieve(params).get(0).results()).isEmpty();
    }

    @Test
    @DisplayName("关键词检索：BM25 文本进 data、annsField=content_sparse、score 恒 1.0、失败跳过、截 TopK")
    void keywordsRetrieve() throws Exception {
        responses.put("POST /v2/vectordb/collections/list",
                "{\"code\":0,\"data\":[\"weknora_embeddings_2\",\"other_base\"]}");
        responses.put("POST /v2/vectordb/entities/search",
                "{\"code\":0,\"data\":[{\"id\":\"p1\",\"chunk_id\":\"c1\",\"distance\":0.49},"
                        + "{\"id\":\"p2\",\"chunk_id\":\"c2\",\"distance\":0.16},"
                        + "{\"id\":\"p3\",\"chunk_id\":\"c3\",\"distance\":0.11}],\"topks\":[3]}");
        MilvusRetrieveRepository repo = repo("weknora_embeddings");
        RetrieveParams params = new RetrieveParams();
        params.retrieverType = EngineTypes.RETRIEVER_KEYWORDS;
        params.query = "中文 检索";
        params.topK = 2;

        List<RetrieveResult> results = repo.retrieve(params);

        JsonNode body = last("/entities/search").json();
        assertThat(body.path("data").get(0).asText()).isEqualTo("中文 检索");
        assertThat(body.path("annsField").asText()).isEqualTo("content_sparse");
        assertThat(results.get(0).results()).hasSize(2);
        assertThat(results.get(0).results().get(0).score).isEqualTo(1.0);
        assertThat(results.get(0).results().get(0).matchType)
                .isEqualTo(EngineTypes.MATCH_KEYWORDS);

        // 单集合失败 → 跳过（与 Weaviate 的"直接抛"不同，照 Go）
        statuses.put("POST /v2/vectordb/entities/search", 500);
        assertThat(repo.retrieve(params).get(0).results()).isEmpty();
    }

    @Test
    @DisplayName("信封与检索类型：code != 0 → 异常；未知检索类型 → Go 原文")
    void envelopeAndDispatch() throws Exception {
        responses.put("POST /v2/vectordb/entities/search",
                "{\"code\":1100,\"message\":\"bad filter\"}");
        MilvusRetrieveRepository repo = repo("weknora_embeddings");
        RetrieveParams params = new RetrieveParams();
        params.retrieverType = EngineTypes.RETRIEVER_VECTOR;
        params.embedding = new float[] {1f};
        params.topK = 3;
        assertThatThrownBy(() -> repo.retrieve(params))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to search:")
                .hasMessageContaining("bad filter");

        RetrieveParams bogus = new RetrieveParams();
        bogus.retrieverType = "bogus";
        assertThatThrownBy(() -> repo.retrieve(bogus))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("invalid retriever type: bogus");
    }

    // ── CopyIndices / move ────────────────────────────────────────────────

    @Test
    @DisplayName("CopyIndices：offset 分页 + 三态 SourceID + 新 UUID + isEnabled 沿用源值")
    void copyIndices() throws Exception {
        String c1 = UUID.randomUUID().toString();
        String c2 = UUID.randomUUID().toString();
        responses.put("POST /v2/vectordb/entities/query",
                "{\"code\":0,\"data\":["
                        + "{\"id\":\"p1\",\"content\":\"a\",\"source_id\":\"" + c1 + "\","
                        + "\"source_type\":2,\"chunk_id\":\"" + c1 + "\","
                        + "\"knowledge_id\":\"k1\",\"knowledge_base_id\":\"srcKb\","
                        + "\"tag_id\":\"t\",\"is_enabled\":false,\"embedding\":[0.5,0.25]}"
                        + "]}");
        MilvusRetrieveRepository repo = repo("weknora_embeddings");
        repo.copyIndices("srcKb", Map.of("k1", "tk1"), Map.of(c1, c2), "targetKb", 2, "document");

        JsonNode query = last("/entities/query").json();
        assertThat(query.path("filter").asText()).isEqualTo("knowledge_base_id == \"srcKb\"");
        assertThat(query.path("limit").asInt()).isEqualTo(64);
        JsonNode row = last("/entities/upsert").json().path("data").get(0);
        assertThat(row.path("id").asText()).matches("[0-9a-f-]{36}");
        assertThat(row.path("source_id").asText()).isEqualTo(c2);
        assertThat(row.path("chunk_id").asText()).isEqualTo(c2);
        assertThat(row.path("knowledge_id").asText()).isEqualTo("tk1");
        assertThat(row.path("knowledge_base_id").asText()).isEqualTo("targetKb");
        assertThat(row.path("tag_id").asText()).isEqualTo("t");
        assertThat(row.path("is_enabled").asBoolean()).isFalse(); // 照 Go：沿用源值
        assertThat(row.path("source_type").asInt()).isEqualTo(2);
        assertThat(row.path("embedding").get(0).asDouble()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("move：query 分页 → Upsert 重写 kb/tag；重复 ID → invalid or repeated move index")
    void moveIndices() throws Exception {
        String id1 = UUID.randomUUID().toString();
        responses.put("POST /v2/vectordb/entities/query",
                "{\"code\":0,\"data\":[{\"id\":\"" + id1 + "\",\"chunk_id\":\"c1\","
                        + "\"knowledge_id\":\"k1\",\"knowledge_base_id\":\"srcKb\","
                        + "\"tag_id\":\"t\",\"embedding\":[1]}],\"topks\":[1]}");
        MilvusRetrieveRepository repo = repo("weknora_embeddings");
        assertThatThrownBy(() -> repo.moveKnowledgeIndices("srcKb", "targetKb", "k1",
                List.of(), 2, "document"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid or repeated move index");

        JsonNode row = last("/entities/upsert").json().path("data").get(0);
        assertThat(row.path("knowledge_base_id").asText()).isEqualTo("targetKb");
        assertThat(row.path("tag_id").asText()).isEmpty();
        assertThat(row.path("embedding").get(0).asDouble()).isEqualTo(1.0);

        // 空页 → 正常收束
        responses.put("POST /v2/vectordb/entities/query", "{\"code\":0,\"data\":[]}");
        repo.moveKnowledgeIndices("srcKb", "targetKb", "k1", List.of(), 2, "document");
    }

    @Test
    @DisplayName("探针：collections/list 成功 → 版本恒 \"\"（照 Go）；失败 → 异常")
    void testConnection() {
        assertThat(MilvusRetrieveRepository.testConnection(addr, "", "", "", null)).isEmpty();
        statuses.put("POST /v2/vectordb/collections/list", 500);
        assertThatThrownBy(() -> MilvusRetrieveRepository.testConnection(addr, "", "", "", null))
                .isInstanceOf(IllegalStateException.class);
    }
}
