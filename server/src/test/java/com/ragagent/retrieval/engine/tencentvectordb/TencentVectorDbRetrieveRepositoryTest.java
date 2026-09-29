package com.ragagent.retrieval.engine.tencentvectordb;

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
import com.ragagent.vectorstore.domain.IndexConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 腾讯 VectorDB 驱动（W5γ4.15）对照 Go {@code retriever/tencentvectordb/}：wire 形状
 * （HTTP API 的 auth/path/信封）、集合命名开关、建集合索引表、Upsert 的稀疏向量、
 * 删除 filter 形态、向量/全文检索请求体、Update API 的批量更新、拷贝的 offset 分页与
 * sha256 三态 SourceID、move。BM25 用内存参数表注入（对照 Go 的数学已在
 * {@code TencentVectorDbBm25Test} 验证）。
 */
class TencentVectorDbRetrieveRepositoryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Captured(String method, String path, String auth, String sdkVersion, String body) {

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
        captured.add(new Captured(method, path, ex.getRequestHeaders().getFirst("Authorization"),
                ex.getRequestHeaders().getFirst("Sdk-Version"), raw));
        String key = method + " " + path;
        String body = responses.get(key);
        int status = statuses.getOrDefault(key, 200);
        if (body == null) {
            switch (path) {
                case "/database/list" -> body = "{\"code\":0,\"databases\":[\"weknora\"]}";
                case "/collection/describe" -> body = "{\"code\":0}";
                case "/collection/list" ->
                        body = "{\"code\":0,\"collections\":["
                                + "{\"collectionName\":\"weknora_embeddings_3\"}]}";
                case "/document/query" -> body = "{\"code\":0,\"documents\":[],\"count\":0}";
                case "/document/search", "/document/fullTextSearch" ->
                        body = "{\"code\":0,\"documents\":[]}";
                default -> body = "{\"code\":0}";
            }
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private TencentVectorDbRetrieveRepository repo(String baseName, boolean dimensionSuffix) {
        TencentVectorDbRetrieveRepository repo = new TencentVectorDbRetrieveRepository(
                new TencentVectorDbRestClient(addr, "root", "key-1", null), "weknora", baseName,
                dimensionSuffix, 1, 1);
        repo.useBm25ForTest(TencentVectorDbBm25.inMemory(0.75, 1.2, 1000, 100,
                Map.of("613153351", 10.0), TencentVectorDbBm25.fixedTokenizer(List.of("hello"))));
        return repo;
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

    private static Map<String, Object> embeddings(Object... pairs) {
        Map<String, float[]> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (float[]) pairs[i + 1]);
        }
        Map<String, Object> params = new HashMap<>();
        params.put("embedding", map);
        return params;
    }

    private Captured last(String path) {
        return captured.stream().filter(c -> c.path().equals(path)).reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("no request to " + path + ": " + captured));
    }

    private static final String CHUNK = "11111111-1111-1111-1111-111111111111";

    // ── 传输层 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("HTTP 形状：Authorization: Bearer account=…&api_key=… + Sdk-Version + 静态路径")
    void wireShape() throws Exception {
        responses.put("POST /collection/describe", "{\"code\":15202,\"msg\":\"not exist\"}");
        repo("weknora_embeddings", true).batchSave(List.of(info(CHUNK, CHUNK)),
                embeddings(CHUNK, new float[] {1f, 0f, 0f}));
        Captured upsert = last("/document/upsert");
        assertThat(upsert.auth()).isEqualTo("Bearer account=root&api_key=key-1");
        assertThat(upsert.sdkVersion()).isEqualTo("v1.8.4");
        assertThat(last("/collection/create").json().path("database").asText())
                .isEqualTo("weknora");
    }

    @Test
    @DisplayName("客户端构造：https 被拒（照 SDK）、空用户名/密钥被拒、地址自动补 http://")
    void clientConstruction() {
        assertThatThrownBy(() -> new TencentVectorDbRestClient("https://x:8100", "root", "k", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not supporting https://");
        assertThatThrownBy(() -> new TencentVectorDbRestClient("x:8100", "", "k", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("username or key is empty");
        assertThat(new TencentVectorDbRestClient("x:8100", "root", "k", null)).isNotNull();
    }

    @Test
    @DisplayName("信封：code != 0 → 「code: N, message: …」（照 Go 的 handleResponse）")
    void envelopeErrors() {
        responses.put("POST /collection/describe", "{\"code\":15202,\"msg\":\"collection not exist\"}");
        TencentVectorDbRetrieveRepository repo = repo("weknora_embeddings", true);
        // existsCollection 把 15202 当"不存在"（照 Go）
        assertThat(repo).isNotNull();
        statuses.put("POST /collection/create", 503);
        assertThatThrownBy(() -> repo.batchSave(List.of(info(CHUNK, CHUNK)),
                embeddings(CHUNK, new float[] {1f})))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("response code is 503");
    }

    // ── 建集合与写入 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("建集合：库不存在先建库；索引表照 Go（HNSW/COSINE/M16/efC200 + inverted/IP + 9 标量）")
    void createCollectionShape() throws Exception {
        responses.put("GET /database/list", "{\"code\":0,\"databases\":[]}");
        responses.put("POST /collection/describe", "{\"code\":15202,\"msg\":\"not exist\"}");
        repo("weknora_embeddings", true).batchSave(List.of(info(CHUNK, CHUNK)),
                embeddings(CHUNK, new float[] {1f, 2f, 3f}));

        assertThat(captured.stream().anyMatch(c -> c.path().equals("/database/create"))).isTrue();
        JsonNode create = last("/collection/create").json();
        assertThat(create.path("collection").asText()).isEqualTo("weknora_embeddings_3");
        assertThat(create.path("shardNum").asInt()).isEqualTo(1);
        assertThat(create.path("replicaNum").asInt()).isEqualTo(1);
        JsonNode indexes = create.path("indexes");
        assertThat(indexes.get(0).path("fieldName").asText()).isEqualTo("vector");
        assertThat(indexes.get(0).path("indexType").asText()).isEqualTo("HNSW");
        assertThat(indexes.get(0).path("metricType").asText()).isEqualTo("COSINE");
        assertThat(indexes.get(0).path("dimension").asInt()).isEqualTo(3);
        assertThat(indexes.get(0).path("params").path("M").asInt()).isEqualTo(16);
        assertThat(indexes.get(0).path("params").path("efConstruction").asInt()).isEqualTo(200);
        assertThat(indexes.get(1).path("fieldName").asText()).isEqualTo("sparse_vector");
        assertThat(indexes.get(1).path("indexType").asText()).isEqualTo("inverted");
        assertThat(indexes.get(1).path("metricType").asText()).isEqualTo("IP");
        assertThat(indexes.get(2).path("indexType").asText()).isEqualTo("primaryKey");
        assertThat(indexes.get(10).path("fieldName").asText()).isEqualTo("is_enabled");
        assertThat(indexes.get(10).path("fieldType").asText()).isEqualTo("uint64");
    }

    @Test
    @DisplayName("写入：文档体（id/vector/sparse_vector 对 + 8 字段）；id 兜底 SourceID；维度分组")
    void upsertShape() throws Exception {
        TencentVectorDbRetrieveRepository repo = repo("weknora_embeddings", true);
        IndexInfo noId = info("src-1", "chunk-1");
        repo.batchSave(List.of(noId), embeddings("src-1", new float[] {0.5f, 0.25f, 0f}));

        JsonNode doc = last("/document/upsert").json().path("documents").get(0);
        assertThat(doc.path("id").asText()).isEqualTo("src-1");
        assertThat(doc.path("vector").get(0).asDouble()).isEqualTo(0.5);
        JsonNode sparse = doc.path("sparse_vector").get(0);
        assertThat(sparse.get(0).asLong()).isEqualTo(613153351L); // murmur3("hello")
        assertThat(sparse.get(1).asDouble()).isGreaterThan(0);
        assertThat(doc.path("content").asText()).isEqualTo("hello");
        assertThat(doc.path("source_id").asText()).isEqualTo("src-1");
        assertThat(doc.path("chunk_id").asText()).isEqualTo("chunk-1");
        assertThat(doc.path("is_enabled").asLong()).isEqualTo(1L);
        assertThat(last("/document/upsert").json().path("buildIndex").asBoolean()).isTrue();

        // 空向量跳过（不抛，照 Go 的 WARN 语义）
        int before = captured.size();
        repo.batchSave(List.of(info("s", "c")), embeddings());
        assertThat(captured).hasSize(before);
    }

    @Test
    @DisplayName("集合命名开关：indexCfg.collectionName 非空 → 单集合（无维度后缀）")
    void singleCollectionMode() throws Exception {
        IndexConfig idx = new IndexConfig();
        idx.collectionName = "my_collection";
        responses.put("POST /collection/describe", "{\"code\":15202,\"msg\":\"not exist\"}");
        TencentVectorDbRetrieveRepository repo = repo("my_collection", false);
        repo.batchSave(List.of(info(CHUNK, CHUNK)), embeddings(CHUNK, new float[] {1f, 2f}));
        assertThat(last("/document/upsert").json().path("collection").asText())
                .isEqualTo("my_collection");
        assertThat(last("/collection/create").json().path("collection").asText())
                .isEqualTo("my_collection");
        assertThat(TencentVectorDbRetrieveRepository.shouldUseDimensionSuffix(idx)).isFalse();
        assertThat(TencentVectorDbRetrieveRepository.shouldUseDimensionSuffix(null)).isTrue();
    }

    @Test
    @DisplayName("删除：filter 形态 field in (\"a\",\"b\")（照 tcvectordb.In）；空列表不发请求")
    void deleteFilters() throws Exception {
        TencentVectorDbRetrieveRepository repo = repo("weknora_embeddings", true);
        repo.deleteByChunkIdList(List.of("a", "b"), 3, "document");
        assertThat(last("/document/delete").json().path("query").path("filter").asText())
                .isEqualTo("chunk_id in (\"a\",\"b\")");
        int before = captured.size();
        repo.deleteBySourceIdList(List.of(), 3, "document");
        assertThat(captured).hasSize(before);
    }

    // ── 检索 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("向量检索：vectors + params.ef=100 + radius + outputFields 九项；dim=0/集合不存在短路")
    void vectorRetrieve() throws Exception {
        responses.put("POST /document/search",
                "{\"code\":0,\"documents\":[[{\"id\":\"p1\",\"content\":\"hello\","
                        + "\"source_id\":\"s1\",\"source_type\":2,\"chunk_id\":\"c1\","
                        + "\"knowledge_id\":\"k1\",\"knowledge_base_id\":\"kb1\","
                        + "\"tag_id\":\"t\",\"is_enabled\":1,\"score\":0.93}]]}");
        TencentVectorDbRetrieveRepository repo = repo("weknora_embeddings", true);
        RetrieveParams params = new RetrieveParams();
        params.retrieverType = EngineTypes.RETRIEVER_VECTOR;
        params.embedding = new float[] {1f, 0f, 0f};
        params.knowledgeBaseIds = List.of("kb1");
        params.excludeChunkIds = List.of("c9");
        params.topK = 5;
        params.threshold = 0.7f;

        List<RetrieveResult> results = repo.retrieve(params);

        JsonNode search = last("/document/search").json().path("search");
        assertThat(search.path("vectors").get(0).get(0).asDouble()).isEqualTo(1.0);
        assertThat(search.path("params").path("ef").asInt()).isEqualTo(100);
        assertThat(search.path("retrieveVector").asBoolean()).isFalse();
        assertThat(search.path("limit").asLong()).isEqualTo(5);
        assertThat(search.path("radius").asDouble()).isCloseTo(0.7, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(search.path("filter").asText()).isEqualTo(
                "is_enabled=1 and knowledge_base_id in (\"kb1\") and chunk_id not in (\"c9\")");
        assertThat(search.path("outputFields")).hasSize(9);
        assertThat(results.get(0).results().get(0).score).isEqualTo(0.93);
        assertThat(results.get(0).results().get(0).isEnabled).isTrue();
        assertThat(results.get(0).results().get(0).sourceType).isEqualTo(2);

        // dim = 0 → 短路空结果
        RetrieveParams empty = new RetrieveParams();
        empty.retrieverType = EngineTypes.RETRIEVER_VECTOR;
        empty.embedding = new float[0];
        assertThat(repo.retrieve(empty).get(0).results()).isEmpty();

        // 集合不存在（15202）→ 空结果
        responses.put("POST /collection/describe", "{\"code\":15202,\"msg\":\"not exist\"}");
        assertThat(repo.retrieve(params).get(0).results()).isEmpty();
    }

    @Test
    @DisplayName("关键词检索：BM25 查询向量进 match.data + fieldName=sparse_vector；score 降序截 TopK；全失败报错")
    void keywordsRetrieve() throws Exception {
        responses.put("POST /collection/list",
                "{\"code\":0,\"collections\":[{\"collectionName\":\"weknora_embeddings_3\"},"
                        + "{\"collectionName\":\"other_base\"}]}");
        responses.put("POST /document/fullTextSearch",
                "{\"code\":0,\"documents\":[[{\"id\":\"p1\",\"chunk_id\":\"c1\",\"score\":0.2},"
                        + "{\"id\":\"p2\",\"chunk_id\":\"c2\",\"score\":0.9},"
                        + "{\"id\":\"p3\",\"chunk_id\":\"c3\",\"score\":0.5}]]}");
        TencentVectorDbRetrieveRepository repo = repo("weknora_embeddings", true);
        RetrieveParams params = new RetrieveParams();
        params.retrieverType = EngineTypes.RETRIEVER_KEYWORDS;
        params.query = "  hello  ";
        params.topK = 2;

        List<RetrieveResult> results = repo.retrieve(params);

        JsonNode search = last("/document/fullTextSearch").json().path("search");
        assertThat(search.path("match").path("fieldName").asText()).isEqualTo("sparse_vector");
        JsonNode data = search.path("match").path("data").get(0).get(0);
        assertThat(data.get(0).asLong()).isEqualTo(613153351L);
        assertThat(search.path("filter").asText()).isEqualTo("is_enabled=1");
        assertThat(results.get(0).results()).extracting(r -> r.chunkId)
                .containsExactly("c2", "c3"); // 0.9 > 0.5，截到 2 条
        assertThat(results.get(0).results().get(0).matchType)
                .isEqualTo(EngineTypes.MATCH_KEYWORDS);

        // 单集合失败 → 跳过；全失败 → 报错（照 Go 的提示文案）
        statuses.put("POST /document/fullTextSearch", 500);
        assertThatThrownBy(() -> repo.retrieve(params))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed in all matched collections");
    }

    // ── 批量更新 / 拷贝 / move ────────────────────────────────────────────

    @Test
    @DisplayName("enabled/tag 更新：Update API（query.filter + update 字段），跨匹配集合；失败即抛")
    void batchUpdates() throws Exception {
        TencentVectorDbRetrieveRepository repo = repo("weknora_embeddings", true);
        repo.batchUpdateChunkEnabledStatus(new LinkedHashMap<>(Map.of(CHUNK, false)));
        JsonNode update = last("/document/update").json();
        assertThat(update.path("query").path("filter").asText())
                .isEqualTo("chunk_id in (\"" + CHUNK + "\")");
        assertThat(update.path("update").path("is_enabled").asLong()).isEqualTo(0L);

        repo.batchUpdateChunkTagID(Map.of(CHUNK, "t9"));
        assertThat(last("/document/update").json().path("update").path("tag_id").asText())
                .isEqualTo("t9");

        statuses.put("POST /document/update", 500);
        assertThatThrownBy(() -> repo.batchUpdateChunkTagID(Map.of(CHUNK, "t1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("update chunks in weknora_embeddings_3");
    }

    @Test
    @DisplayName("拷贝：offset 分页（limit=500）+ kb 条件 + 三态 SourceID（第 3 态 sha256 前 16 hex）+ 目标 id 改写")
    void copyIndices() throws Exception {
        String c1 = UUID.randomUUID().toString();
        String q1 = c1 + "-q7";
        String other = "mystery-source";
        responses.put("POST /document/query",
                "{\"code\":0,\"count\":3,\"documents\":["
                        + "{\"id\":\"p1\",\"content\":\"hello\",\"source_id\":\"" + c1 + "\","
                        + "\"source_type\":0,\"chunk_id\":\"" + c1 + "\",\"knowledge_id\":\"k1\","
                        + "\"knowledge_base_id\":\"srcKb\",\"tag_id\":\"t\",\"is_enabled\":0,"
                        + "\"vector\":[0.5,0.25,0]}"
                        + "]}");
        TencentVectorDbRetrieveRepository repo = repo("weknora_embeddings", true);
        String c2 = UUID.randomUUID().toString();
        repo.copyIndices("srcKb", Map.of("k1", "tk1"), Map.of(c1, c2), "targetKb", 3, "document");

        JsonNode query = last("/document/query").json().path("query");
        assertThat(query.path("offset").asLong()).isZero();
        assertThat(query.path("limit").asLong()).isEqualTo(500);
        assertThat(query.path("retrieveVector").asBoolean()).isTrue();
        assertThat(query.path("filter").asText()).isEqualTo(
                "knowledge_base_id in (\"srcKb\") and chunk_id in (\"" + c1 + "\")");

        JsonNode doc = last("/document/upsert").json().path("documents").get(0);
        assertThat(doc.path("id").asText()).isEqualTo(c2);
        assertThat(doc.path("source_id").asText()).isEqualTo(c2);
        assertThat(doc.path("chunk_id").asText()).isEqualTo(c2);
        assertThat(doc.path("knowledge_id").asText()).isEqualTo("tk1");
        assertThat(doc.path("knowledge_base_id").asText()).isEqualTo("targetKb");
        assertThat(doc.path("is_enabled").asLong()).isZero(); // 沿用源值
        assertThat(doc.path("vector").get(0).asDouble()).isEqualTo(0.5);
        assertThat(doc.path("sparse_vector").isArray()).isTrue();

        // 三态第 2 态（生成型问题）
        assertThat(TencentVectorDbRetrieveRepository.translateSourceId(q1, c1, c2))
                .isEqualTo(c2 + "-q7");
        // 三态第 3 态：sha256(targetChunkID NUL sourceChunkID NUL original) 前 16 hex
        String third = TencentVectorDbRetrieveRepository.translateSourceId(other, c1, c2);
        assertThat(third).startsWith(c2 + "-").hasSize(c2.length() + 1 + 16);
        assertThat(third).isEqualTo(c2 + "-" + sha256Hex16(c2, c1, other));
    }

    private static String sha256Hex16(String target, String source, String original) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update(target.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(source.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(original.getBytes(StandardCharsets.UTF_8));
            byte[] sum = digest.digest();
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", sum[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("move：Update API 一次改 kb + 清 tag（照 move.go，无 seen 守卫）")
    void moveIndices() throws Exception {
        TencentVectorDbRetrieveRepository repo = repo("weknora_embeddings", true);
        repo.moveKnowledgeIndices("srcKb", "targetKb", "k1", List.of(), 3, "document");
        JsonNode update = last("/document/update").json();
        assertThat(update.path("collection").asText()).isEqualTo("weknora_embeddings_3");
        assertThat(update.path("query").path("filter").asText())
                .isEqualTo("knowledge_base_id in (\"srcKb\") and knowledge_id in (\"k1\")");
        assertThat(update.path("update").path("knowledge_base_id").asText())
                .isEqualTo("targetKb");
        assertThat(update.path("update").path("tag_id").asText()).isEmpty();
    }

    // ── 纯函数面 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("纯函数：存储估算（content 计两次）/ UTF-8 清理 / 库名与分片解析 / 探针")
    void pureFunctions() {
        // 存储估算：content 3B + 向量 8B + content×2 6B + (s+c+k1+kb1=1+1+2+3=7) + 256
        TencentVectorDbRetrieveRepository estimating = repo("weknora_embeddings", true);
        IndexInfo info = info("s", "c");
        info.content = "中";
        assertThat(estimating.estimateStorageSize(List.of(info),
                embeddings("s", new float[] {1f, 2f}))).isEqualTo(3 + 8 + 6 + 7 + 256);

        assertThat(TencentVectorDbRetrieveRepository.byteLength("中")).isEqualTo(3);
        assertThat(TencentVectorDbRetrieveRepository.cleanInvalidUtf8("a\u0000b")).isEqualTo("ab");
        assertThat(TencentVectorDbRetrieveRepository.cleanInvalidUtf8("a\uD800b")).isEqualTo("ab");
        assertThat(TencentVectorDbRetrieveRepository.cleanInvalidUtf8("\uD83D\uDE00"))
                .isEqualTo("\uD83D\uDE00");
        assertThat(TencentVectorDbRetrieveRepository.resolveDatabase(null)).isEqualTo("weknora");
        assertThat(TencentVectorDbRetrieveRepository.resolveCollectionBase(null))
                .isEqualTo("weknora_embeddings");
        assertThat(TencentVectorDbRetrieveRepository.resolveReplicaNumber(null)).isEqualTo(1);
        assertThat(TencentVectorDbRetrieveRepository.in("f", List.of())).isEmpty();
        assertThat(TencentVectorDbRetrieveRepository.notIn("f", List.of("a")))
                .isEqualTo("f not in (\"a\")");
        assertThat(TencentVectorDbRetrieveRepository.isCollectionAlreadyExistsError(
                new IllegalStateException("code: 15202, message: xxx"))).isTrue();
        assertThat(TencentVectorDbRetrieveRepository.isCollectionAlreadyExistsError(
                new IllegalStateException("collection already exists"))).isTrue();
        assertThat(TencentVectorDbRetrieveRepository.isCollectionAlreadyExistsError(
                new IllegalStateException("boom"))).isFalse();
        assertThat(TencentVectorDbRetrieveRepository.testConnection(addr, "root", "k", null))
                .isEmpty();
    }

    @Test
    @DisplayName("retrieve 类型分派：未知类型 → Go 原文")
    void invalidRetrieverType() {
        TencentVectorDbRetrieveRepository repo = repo("weknora_embeddings", true);
        RetrieveParams bogus = new RetrieveParams();
        bogus.retrieverType = "bogus";
        assertThatThrownBy(() -> repo.retrieve(bogus))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("invalid retriever type: bogus");
    }

    /** 空集合列表时 keyword 检索应返回空（不报"全失败"——matched=0）。 */
    @Test
    @DisplayName("关键词检索：无匹配集合 → 空结果（不报全失败）")
    void keywordsNoMatchedCollections() throws Exception {
        responses.put("POST /collection/list", "{\"code\":0,\"collections\":[]}");
        TencentVectorDbRetrieveRepository repo = repo("weknora_embeddings", true);
        RetrieveParams params = new RetrieveParams();
        params.retrieverType = EngineTypes.RETRIEVER_KEYWORDS;
        params.query = "hello";
        params.topK = 3;
        List<RetrieveResult> results = repo.retrieve(params);
        assertThat(results.get(0).results()).isEmpty();
    }

    @Test
    @DisplayName("关键词检索：空查询短路")
    void keywordsEmptyQuery() throws Exception {
        TencentVectorDbRetrieveRepository repo = repo("weknora_embeddings", true);
        RetrieveParams params = new RetrieveParams();
        params.retrieverType = EngineTypes.RETRIEVER_KEYWORDS;
        params.query = "   ";
        int before = captured.size();
        assertThat(repo.retrieve(params).get(0).results()).isEmpty();
        assertThat(captured).hasSize(before);
    }
}
