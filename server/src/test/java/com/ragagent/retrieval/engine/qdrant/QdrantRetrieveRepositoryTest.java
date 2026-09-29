package com.ragagent.retrieval.engine.qdrant;

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
import com.ragagent.searchutil.SearchTextUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Qdrant 驱动（W5γ4.11）对照 Go {@code retriever/qdrant/} 全包：惰性建集合
 * （size/distance=Cosine + keyword×4/bool/text 索引）、BatchSave 的分组与 100 分片、
 * point ID 恒新 UUID、payload 清理（NUL/非法编码单元）、三种删除（match any）、
 * 向量检索（/points/search + score_threshold）、关键词检索（should(text) 的 Scroll +
 * 跨集合合并截 TopK）、批量更新与 move 的 SetPayload、CopyIndices 的向量回搬与三态
 * SourceID、存储估算与分词纯函数。
 *
 * <p>协议口径：Go 走 gRPC，本仓自持 REST——桩断言"发出去的 HTTP 长什么样"（该批无
 * golden 面，逐请求断言即本批的字节契约）。</p>
 */
class QdrantRetrieveRepositoryTest {

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
    private final Map<String, String> responseOverrides = new HashMap<>();
    private final Map<String, Integer> statusByPath = new HashMap<>();
    private HttpServer server;
    private String base;

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
        String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        captured.add(new Captured(method, path, raw));
        String override = responseOverrides.get(method + " " + path);
        int status = statusByPath.getOrDefault(method + " " + path, 200);
        String body;
        if (override != null) {
            body = override;
        } else if (method.equals("GET") && path.startsWith("/collections/")
                && path.split("/").length == 3) {
            // 集合存在性探测：缺省"存在"（{error:...} 形态的 override 由测试给）
            body = "{\"result\":{\"status\":\"green\"},\"status\":\"ok\"}";
        } else if (method.equals("GET") && path.equals("/collections")) {
            body = "{\"result\":{\"collections\":[]},\"status\":\"ok\"}";
        } else if (method.equals("POST") && path.endsWith("/points/search")) {
            body = "{\"result\":[],\"status\":\"ok\"}";
        } else if (method.equals("POST") && path.endsWith("/points/scroll")) {
            body = "{\"result\":{\"points\":[]},\"status\":\"ok\"}";
        } else {
            body = "{\"result\":true,\"status\":\"ok\"}";
        }
        ex.getResponseHeaders().set("Content-Type", "application/json");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private QdrantRetrieveRepository repo(String baseName) {
        return new QdrantRetrieveRepository(new QdrantRestClient(base, "", null), baseName, 0, 0);
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

    private Captured capturedByPath(String suffix) {
        return captured.stream().filter(c -> c.path().endsWith(suffix)).findFirst()
                .orElseThrow(() -> new AssertionError("no request ending with " + suffix
                        + ": " + captured));
    }

    // ── 建集合与索引 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("惰性建集合：不存在 → PUT（Cosine + size + shard/rep 省略）→ keyword×4/bool/text 索引")
    void ensureCollectionCreatesAndIndexes() throws Exception {
        statusByPath.put("GET /collections/weknora_embeddings_3", 404);
        QdrantRetrieveRepository repo = repo("weknora_embeddings");
        repo.save(info("s1", "c1"), embeddings("s1", new float[] {1f, 2f, 3f}));

        Captured create = captured.stream()
                .filter(c -> c.method().equals("PUT") && c.path().equals("/collections/weknora_embeddings_3"))
                .findFirst().orElseThrow();
        JsonNode body = create.json();
        assertThat(body.path("vectors").path("size").asInt()).isEqualTo(3);
        assertThat(body.path("vectors").path("distance").asText()).isEqualTo("Cosine");
        assertThat(body.has("shard_number")).isFalse();
        assertThat(body.has("replication_factor")).isFalse();

        // 捕获的 path 不含 query：索引请求的形态是 PUT /collections/<name>/index
        List<String> indexFields = captured.stream().filter(c -> c.method().equals("PUT")
                && c.path().endsWith("/index"))
                .map(c -> c.json().path("field_name").asText()).toList();
        assertThat(indexFields).containsExactly("chunk_id", "knowledge_id", "knowledge_base_id",
                "source_id", "is_enabled", "content");

        // 文本索引的形状
        Captured textIndex = captured.stream().filter(c -> c.method().equals("PUT")
                && c.path().endsWith("/index") && c.body().contains("multilingual"))
                .findFirst().orElseThrow();
        assertThat(textIndex.json().path("field_schema").path("type").asText()).isEqualTo("text");
        assertThat(textIndex.json().path("field_schema").path("lowercase").asBoolean()).isTrue();
        // 集合缓存：第二次 save 不再 GET 探测
        int probesBefore = countGets("GET /collections/weknora_embeddings_3");
        repo.save(info("s2", "c2"), embeddings("s2", new float[] {1f, 2f, 3f}));
        assertThat(countGets("GET /collections/weknora_embeddings_3")).isEqualTo(probesBefore);
    }

    private int countGets(String methodAndPath) {
        String[] parts = methodAndPath.split(" ", 2);
        return (int) captured.stream().filter(c -> c.method().equals(parts[0])
                && c.path().equals(parts[1])).count();
    }

    @Test
    @DisplayName("Save：空向量拒收（Go 原文）；point ID 是新 UUID（不承载业务主键）")
    void saveRejectsEmptyEmbedding() throws Exception {
        QdrantRetrieveRepository repo = repo("weknora_embeddings");
        assertThatThrownBy(() -> repo.save(info("s1", "c1"), embeddings()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("empty embedding vector for chunk ID: c1");

        repo.save(info("s1", "c1"), embeddings("s1", new float[] {0.5f}));
        Captured upsert = capturedByPath("/points");
        String pointId = upsert.json().path("points").get(0).path("id").asText();
        assertThat(pointId).matches("[0-9a-f-]{36}");
        assertThat(upsert.json().path("points").get(0).path("vector").get(0).asDouble())
                .isEqualTo(0.5);
        assertThat(upsert.json().path("points").get(0).path("payload").path("chunk_id").asText())
                .isEqualTo("c1");
    }

    @Test
    @DisplayName("BatchSave：按维度分组 + 100 分片 + 空向量跳过 + payload 清理 NUL")
    void batchSaveGroupsAndBatches() throws Exception {
        QdrantRetrieveRepository repo = repo("weknora_embeddings");
        List<IndexInfo> infos = new ArrayList<>();
        Map<String, float[]> vectors = new LinkedHashMap<>();
        for (int i = 0; i < 101; i++) {
            IndexInfo info = info("s" + i, "c" + i);
            info.content = i == 0 ? "bad\u0000content" : "hello";
            infos.add(info);
            vectors.put("s" + i, new float[] {1f, 2f});
        }
        Map<String, Object> params = new HashMap<>();
        params.put("embedding", vectors);
        repo.batchSave(infos, params);

        List<Captured> upserts = captured.stream().filter(c -> c.method().equals("PUT")
                && c.path().equals("/collections/weknora_embeddings_2/points")).toList();
        assertThat(upserts).hasSize(2);
        assertThat(upserts.get(0).json().path("points")).hasSize(100);
        assertThat(upserts.get(1).json().path("points")).hasSize(1);
        // NUL 被丢弃（CleanInvalidUtf8）
        assertThat(upserts.get(0).json().path("points").get(0).path("payload")
                .path("content").asText()).isEqualTo("badcontent");
    }

    // ── 删除 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("三种删除：match any 过滤 + 空列表短路")
    void deletesUseMatchAnyFilters() throws Exception {
        QdrantRetrieveRepository repo = repo("weknora_embeddings");
        repo.deleteByChunkIdList(List.of("c1", "c2"), 2, "document");
        JsonNode delete = capturedByPath("/points/delete").json();
        assertThat(delete.path("filter").path("must").get(0).path("key").asText())
                .isEqualTo("chunk_id");
        assertThat(delete.path("filter").path("must").get(0).path("match").path("any"))
                .hasSize(2);
        assertThat(delete.has("wait")).isFalse();

        int before = captured.size();
        repo.deleteByKnowledgeIdList(List.of(), 2, "document");
        repo.deleteBySourceIdList(List.of(), 2, "document");
        assertThat(captured).hasSize(before);
    }

    // ── 检索 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("向量检索：/points/search 体（filter + limit + score_threshold + with_payload）")
    void vectorRetrieveBody() throws Exception {
        responseOverrides.put("POST /collections/weknora_embeddings_2/points/search",
                "{\"result\":[{\"id\":\"p1\",\"score\":0.91,\"payload\":{\"content\":\"hello\","
                        + "\"source_id\":\"s1\",\"source_type\":0,\"chunk_id\":\"c1\","
                        + "\"knowledgeId\":\"k1\",\"knowledgeBaseId\":\"kb1\",\"tag_id\":\"\"}}],"
                        + "\"status\":\"ok\"}");
        QdrantRetrieveRepository repo = repo("weknora_embeddings");
        RetrieveParams params = new RetrieveParams();
        params.retrieverType = EngineTypes.RETRIEVER_VECTOR;
        params.embedding = new float[] {1f, 2f};
        params.knowledgeBaseIds = List.of("kb1");
        params.excludeChunkIds = List.of("c9");
        params.topK = 5;
        params.threshold = 0.7;

        List<RetrieveResult> results = repo.retrieve(params);

        JsonNode body = capturedByPath("/points/search").json();
        assertThat(body.path("vector").get(0).asDouble()).isEqualTo(1.0);
        assertThat(body.path("limit").asInt()).isEqualTo(5);
        assertThat(body.path("score_threshold").asDouble()).isEqualTo(0.7);
        assertThat(body.path("with_payload").asBoolean()).isTrue();
        JsonNode must = body.path("filter").path("must");
        assertThat(must.get(0).path("key").asText()).isEqualTo("is_enabled");
        assertThat(must.get(0).path("match").path("value").asBoolean()).isTrue();
        assertThat(must.get(1).path("match").path("any").get(0).asText()).isEqualTo("kb1");
        assertThat(body.path("filter").path("must_not").get(0).path("key").asText())
                .isEqualTo("chunk_id");

        assertThat(results.get(0).results()).hasSize(1);
        assertThat(results.get(0).results().get(0).score).isEqualTo(0.91);
        assertThat(results.get(0).results().get(0).sourceId).isEqualTo("s1");
        assertThat(results.get(0).results().get(0).matchType)
                .isEqualTo(EngineTypes.MATCH_EMBEDDING);
    }

    @Test
    @DisplayName("向量检索：集合不存在 → 空结果（不发 search）")
    void vectorRetrieveMissingCollection() throws Exception {
        statusByPath.put("GET /collections/weknora_embeddings_2", 404);
        QdrantRetrieveRepository repo = repo("weknora_embeddings");
        RetrieveParams params = new RetrieveParams();
        params.retrieverType = EngineTypes.RETRIEVER_VECTOR;
        params.embedding = new float[] {1f, 2f};
        assertThat(repo.retrieve(params).get(0).results()).isEmpty();
        assertThat(captured.stream().noneMatch(c -> c.path().endsWith("/points/search"))).isTrue();
    }

    @Test
    @DisplayName("关键词检索：should(text) 逐 token + 集合前缀过滤 + 跨集合合并截 TopK")
    void keywordsRetrieveShouldConditions() throws Exception {
        responseOverrides.put("GET /collections",
                "{\"result\":{\"collections\":[{\"name\":\"weknora_embeddings_2\"},"
                        + "{\"name\":\"other_base_3\"}]},\"status\":\"ok\"}");
        responseOverrides.put("POST /collections/weknora_embeddings_2/points/scroll",
                "{\"result\":{\"points\":[{\"id\":\"p1\",\"payload\":{\"content\":\"a\","
                        + "\"source_id\":\"s1\",\"source_type\":0,\"chunk_id\":\"c1\","
                        + "\"knowledgeId\":\"k1\",\"knowledgeBaseId\":\"kb1\",\"tag_id\":\"\"}},"
                        + "{\"id\":\"p2\",\"payload\":{}},{\"id\":\"p3\",\"payload\":{}}],"
                        + "\"next_page_offset\":null},\"status\":\"ok\"}");
        QdrantRetrieveRepository repo = repo("weknora_embeddings");
        RetrieveParams params = new RetrieveParams();
        params.retrieverType = EngineTypes.RETRIEVER_KEYWORDS;
        params.query = "知识库检索";
        params.topK = 2;

        List<RetrieveResult> results = repo.retrieve(params);

        JsonNode body = capturedByPath("/points/scroll").json();
        JsonNode should = body.path("filter").path("should");
        assertThat(should.size()).isGreaterThan(0);
        assertThat(should.get(0).path("key").asText()).isEqualTo("content");
        assertThat(should.get(0).path("match").has("text")).isTrue();
        assertThat(body.path("limit").asInt()).isEqualTo(2);
        // 跨集合合并后截 TopK=2；只查了前缀匹配的集合
        assertThat(results.get(0).results()).hasSize(2);
        assertThat(results.get(0).results().get(0).score).isEqualTo(1.0);
        assertThat(results.get(0).results().get(0).matchType)
                .isEqualTo(EngineTypes.MATCH_KEYWORDS);
        assertThat(captured.stream().noneMatch(c -> c.path().contains("other_base"))).isTrue();
    }

    @Test
    @DisplayName("关键词检索：单集合失败只 WARN 跳过（不炸整体）")
    void keywordsToleratesCollectionFailure() throws Exception {
        responseOverrides.put("GET /collections",
                "{\"result\":{\"collections\":[{\"name\":\"weknora_embeddings_2\"}]},"
                        + "\"status\":\"ok\"}");
        QdrantRetrieveRepository repo = repo("weknora_embeddings");
        RetrieveParams params = new RetrieveParams();
        params.retrieverType = EngineTypes.RETRIEVER_KEYWORDS;
        params.query = "x";
        params.topK = 3;
        // 缺省 scroll 桩返回空集合 → 不炸
        assertThat(repo.retrieve(params).get(0).results()).isEmpty();

        statusByPath.put("POST /collections/weknora_embeddings_2/points/scroll", 500);
        assertThat(repo.retrieve(params).get(0).results()).isEmpty();
    }

    @Test
    @DisplayName("retrieve：未知检索类型 → Go 原文")
    void retrieveInvalidType() {
        QdrantRetrieveRepository repo = repo("weknora_embeddings");
        RetrieveParams params = new RetrieveParams();
        params.retrieverType = "bogus";
        assertThatThrownBy(() -> repo.retrieve(params))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("invalid retriever type: bogus");
    }

    // ── 批量更新与 move ────────────────────────────────────────────────────

    @Test
    @DisplayName("批量更新 enabled/tag：SetPayload（wait=true）按集合 × 分组扇出；集合失败只 WARN")
    void batchUpdatesSetPayload() throws Exception {
        responseOverrides.put("GET /collections",
                "{\"result\":{\"collections\":[{\"name\":\"weknora_embeddings_2\"},"
                        + "{\"name\":\"other_base\"}]},\"status\":\"ok\"}");
        QdrantRetrieveRepository repo = repo("weknora_embeddings");
        Map<String, Boolean> status = new LinkedHashMap<>();
        status.put("c1", true);
        status.put("c2", false);
        repo.batchUpdateChunkEnabledStatus(status);
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("c3", "t9");
        repo.batchUpdateChunkTagID(tags);

        List<Captured> payloads = captured.stream().filter(c -> c.method().equals("POST")
                && c.path().equals("/collections/weknora_embeddings_2/points/payload")).toList();
        assertThat(payloads).hasSize(3);
        assertThat(payloads.get(0).json().path("payload").path("is_enabled").asBoolean()).isTrue();
        assertThat(payloads.get(1).json().path("payload").path("is_enabled").asBoolean())
                .isFalse();
        assertThat(payloads.get(2).json().path("payload").path("tag_id").asText())
                .isEqualTo("t9");
        // Go 的前缀规则是纯字符串前缀（不校验维度后缀）——非前缀集合必须跳过
        assertThat(captured.stream().noneMatch(c -> c.path().contains("other_base"))).isTrue();
    }

    @Test
    @DisplayName("move：SetPayload（kb_id 改写 + tag 清空 + wait=true）")
    void moveRewritesPayload() throws Exception {
        QdrantRetrieveRepository repo = repo("weknora_embeddings");
        repo.moveKnowledgeIndices("srcKb", "targetKb", "k1", List.of(), 2, "document");
        JsonNode body = capturedByPath("/points/payload").json();
        assertThat(body.path("payload").path("knowledge_base_id").asText()).isEqualTo("targetKb");
        assertThat(body.path("payload").path("tag_id").asText()).isEmpty();
        JsonNode must = body.path("filter").path("must");
        assertThat(must.get(0).path("key").asText()).isEqualTo("knowledge_base_id");
        assertThat(must.get(0).path("match").path("value").asText()).isEqualTo("srcKb");
        assertThat(must.get(1).path("key").asText()).isEqualTo("knowledge_id");
    }

    // ── CopyIndices ───────────────────────────────────────────────────────

    @Test
    @DisplayName("CopyIndices：scroll（with_vector）→ 三态 SourceID + 新 UUID + 向量回搬 + 未映射跳过")
    void copyIndicesCarriesVectors() throws Exception {
        responseOverrides.put("POST /collections/weknora_embeddings_2/points/scroll",
                "{\"result\":{\"points\":[{\"id\":\"p1\",\"vector\":[0.5,0.25],"
                        + "\"payload\":{\"content\":\"a\",\"source_id\":\"c1\",\"source_type\":0,"
                        + "\"chunk_id\":\"c1\",\"knowledgeId\":\"k1\","
                        + "\"knowledgeBaseId\":\"srcKb\",\"tag_id\":\"t\"}},"
                        + "{\"id\":\"p2\",\"vector\":[0.1],\"payload\":{\"source_id\":\"c1-q7\","
                        + "\"chunk_id\":\"c1\",\"knowledgeId\":\"k1\","
                        + "\"knowledgeBaseId\":\"srcKb\",\"is_enabled\":false}},"
                        + "{\"id\":\"p3\",\"payload\":{\"chunk_id\":\"c9\","
                        + "\"knowledgeId\":\"k1\"}}],\"next_page_offset\":null},\"status\":\"ok\"}");
        QdrantRetrieveRepository repo = repo("weknora_embeddings");
        repo.copyIndices("srcKb", Map.of("k1", "tk1"), Map.of("c1", "tc1"), "targetKb", 2,
                "document");

        Captured upsert = capturedByPath("/collections/weknora_embeddings_2/points");
        JsonNode points = upsert.json().path("points");
        assertThat(points).hasSize(2); // p3 无向量 → 跳过
        JsonNode first = points.get(0);
        assertThat(first.path("id").asText()).matches("[0-9a-f-]{36}");
        assertThat(first.path("payload").path("source_id").asText()).isEqualTo("tc1");
        assertThat(first.path("payload").path("chunk_id").asText()).isEqualTo("tc1");
        assertThat(first.path("payload").path("knowledge_id").asText()).isEqualTo("tk1");
        assertThat(first.path("payload").path("knowledge_base_id").asText()).isEqualTo("targetKb");
        assertThat(first.path("vector").get(0).asDouble()).isEqualTo(0.5);
        JsonNode second = points.get(1);
        assertThat(second.path("payload").path("source_id").asText()).isEqualTo("tc1-q7");
        assertThat(second.path("payload").path("is_enabled").asBoolean()).isFalse();
    }

    // ── 纯函数 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("存储估算：payload 与 tag_id 无关、向量/HNSW 只在非 null 时计入（照 Go）")
    void estimateStorageSize() {
        QdrantVectorEmbedding row = new QdrantVectorEmbedding();
        row.content = "中";
        row.sourceId = "s";
        row.chunkId = "c";
        row.knowledgeId = "k";
        row.knowledgeBaseId = "kb";
        row.embedding = new float[] {1f, 2f};
        // payload = 3 + 1 + 1 + 1 + 2 + 8 = 16；vec = 8；hnsw = 16*2*8 = 256；id tracker = 24
        assertThat(QdrantRetrieveRepository.calculateStorageSize(row)).isEqualTo(16 + 8 + 256 + 24);

        QdrantVectorEmbedding noVector = new QdrantVectorEmbedding();
        assertThat(QdrantRetrieveRepository.calculateStorageSize(noVector)).isEqualTo(8 + 24);

        QdrantVectorEmbedding emptyVector = new QdrantVectorEmbedding();
        emptyVector.embedding = new float[0];
        assertThat(QdrantRetrieveRepository.calculateStorageSize(emptyVector))
                .isEqualTo(8 + 256 + 24); // 非 null 空数组也计 HNSW（照 Go 的 nil 判定）
    }

    @Test
    @DisplayName("分词：trim/小写/单字符丢弃/去重保序（jieba 接缝）")
    void tokenizeQuery() {
        assertThat(QdrantRetrieveRepository.tokenizeQuery("")).isEmpty();
        assertThat(QdrantRetrieveRepository.tokenizeQuery("  ")).isEmpty();
        // 降级分词器把 "Hello hello" 整块返回 → 驱动按空白二次切分 + 小写 + 去重
        List<String> tokens = QdrantRetrieveRepository.tokenizeQuery("Hello hello 世界");
        assertThat(tokens).containsExactly("hello", "世界");
        assertThat(tokens.stream().allMatch(t -> t.codePointCount(0, t.length()) >= 2)).isTrue();

        // 注入假分词器验证顺序与过滤
        SearchTextUtil.Segmenter original = SearchTextUtil.segmenter();
        try {
            SearchTextUtil.setSegmenter(text -> List.of(" Alpha ", "A", "alpha", "Beta"));
            assertThat(QdrantRetrieveRepository.tokenizeQuery("x")).containsExactly("alpha", "beta");
        } finally {
            SearchTextUtil.setSegmenter(original);
        }
    }

    @Test
    @DisplayName("payload 清理：NUL 丢弃、合法字符原样、null → 空串")
    void sanitizePayloadStrings() {
        assertThat(QdrantRetrieveRepository.sanitize("a\u0000b")).isEqualTo("ab");
        assertThat(QdrantRetrieveRepository.sanitize("正常文本")).isEqualTo("正常文本");
        assertThat(QdrantRetrieveRepository.sanitize(null)).isEmpty();
    }

    @Test
    @DisplayName("translateSourceId：三态（普通 / 生成型问题 / 新 UUID）")
    void translateSourceId() {
        assertThat(QdrantRetrieveRepository.translateSourceId("c1", "c1", "tc1"))
                .isEqualTo("tc1");
        assertThat(QdrantRetrieveRepository.translateSourceId("c1-q7", "c1", "tc1"))
                .isEqualTo("tc1-q7");
        assertThat(QdrantRetrieveRepository.translateSourceId("other", "c1", "tc1"))
                .matches("[0-9a-f-]{36}");
    }

    @Test
    @DisplayName("集合名解析：prefix > name > 缺省（env 由进程环境决定）")
    void resolveCollectionName() {
        com.ragagent.vectorstore.domain.IndexConfig withPrefix =
                new com.ragagent.vectorstore.domain.IndexConfig();
        withPrefix.collectionPrefix = "pref";
        withPrefix.collectionName = "name";
        assertThat(QdrantRetrieveRepository.resolveCollectionName(withPrefix)).isEqualTo("pref");

        com.ragagent.vectorstore.domain.IndexConfig withName =
                new com.ragagent.vectorstore.domain.IndexConfig();
        withName.collectionName = "name";
        assertThat(QdrantRetrieveRepository.resolveCollectionName(withName)).isEqualTo("name");
        assertThat(QdrantRetrieveRepository.resolveCollectionName(null))
                .isEqualTo(QdrantRetrieveRepository.DEFAULT_COLLECTION_NAME);
    }

    @Test
    @DisplayName("test-connection：GET / 取 version；非 2xx → 异常")
    void testConnectionProbe() {
        responseOverrides.put("GET /", "{\"title\":\"qdrant - vector search engine\","
                + "\"version\":\"1.12.4\"}");
        assertThat(QdrantRetrieveRepository.testConnection("127.0.0.1",
                server.getAddress().getPort(), "", false, null)).isEqualTo("1.12.4");

        statusByPath.put("GET /", 401);
        assertThatThrownBy(() -> QdrantRetrieveRepository.testConnection("127.0.0.1",
                server.getAddress().getPort(), "", false, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("qdrant health check HTTP 401");
    }
}
