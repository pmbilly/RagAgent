package com.ragagent.retrieval.engine.weaviate;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.retrieval.engine.EngineTypes;
import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;
import com.ragagent.vectorstore.domain.IndexConfig;

/**
 * Weaviate 驱动对<b>真实服务端</b>的端到端验证（env 门控，默认跳过）：
 * 起一个 Weaviate（compose 口径 1.28.4，<b>须带 {@code ENABLE_TOKENIZER_GSE=true}</b>——
 * 类里 content 用 gse 分词，不开该开关建类会 422），然后：
 *
 * <pre>
 * # 起真例（与 docker-compose.yml 的 weaviate 服务同款）
 * docker run -d --name WeKnora-weaviate-local -p 9035:8080 -p 50052:50051 \
 *   -e DEFAULT_VECTORIZER_MODULE=none -e ENABLE_MODULES=none \
 *   -e AUTHENTICATION_ANONYMOUS_ACCESS_ENABLED=true -e ENABLE_TOKENIZER_GSE=true \
 *   semitechnologies/weaviate:1.28.4
 * # 跑本类
 * WEKNORA_WEAVIATE_IT=true ./gradlew :server:test --tests "*WeaviateDriverLocalIT*"
 * </pre>
 *
 * 覆盖：建类 → 批量写 → 向量检索（certainty）→ 关键词检索（BM25 + gse 分词）→
 * <b>merge 更新（tag/enabled）后对象仍可检索</b>（证明修正后的 PATCH 没清属性/向量）→
 * 拷贝（offset 分页 + 命名向量回搬）→ move → 删除。
 */
@EnabledIfEnvironmentVariable(named = "WEKNORA_WEAVIATE_IT", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WeaviateDriverLocalIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HOST = System.getenv().getOrDefault("WEKNORA_WEAVIATE_HOST",
            "localhost:9035");

    private final String base = "Weknora_it_" + UUID.randomUUID().toString().substring(0, 8);
    private WeaviateRetrieveRepository repo;

    @BeforeAll
    void setUp() {
        IndexConfig idx = new IndexConfig();
        idx.collectionPrefix = base;
        repo = WeaviateRetrieveRepository.create(HOST, "http", "", idx, null);
    }

    @AfterAll
    void tearDown() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> schema = http.send(HttpRequest.newBuilder(
                        URI.create("http://" + HOST + "/v1/schema")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        for (JsonNode cls : MAPPER.readTree(schema.body()).path("classes")) {
            String name = cls.path("class").asText("");
            if (name.startsWith(base)) {
                http.send(HttpRequest.newBuilder(
                                URI.create("http://" + HOST + "/v1/schema/" + name))
                                .DELETE().build(), HttpResponse.BodyHandlers.ofString());
            }
        }
    }

    private static IndexInfo info(String chunkId, String kb, String knowledgeId) {
        IndexInfo i = new IndexInfo();
        i.chunkId = chunkId;
        i.sourceId = chunkId;
        i.content = "中文检索 hello " + chunkId.substring(0, 4);
        i.knowledgeId = knowledgeId;
        i.knowledgeBaseId = kb;
        i.tagId = "";
        i.isEnabled = true;
        return i;
    }

    private static Map<String, Object> vectors(Object... idAndVectorPairs) {
        Map<String, float[]> map = new LinkedHashMap<>();
        for (int i = 0; i < idAndVectorPairs.length; i += 2) {
            map.put((String) idAndVectorPairs[i], (float[]) idAndVectorPairs[i + 1]);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("embedding", map);
        return params;
    }

    private static RetrieveParams vectorParams(float[] embedding, int topK, List<String> kbs,
                                               List<String> tags) {
        RetrieveParams p = new RetrieveParams();
        p.retrieverType = EngineTypes.RETRIEVER_VECTOR;
        p.embedding = embedding;
        p.topK = topK;
        p.threshold = 0.1;
        p.knowledgeBaseIds = kbs;
        p.tagIds = tags;
        return p;
    }

    @Test
    @DisplayName("真服务端全链：写→查→merge 更新→拷贝→move→删除")
    void endToEnd() throws Exception {
        String chunkA = UUID.randomUUID().toString();
        String chunkB = UUID.randomUUID().toString();
        IndexInfo a = info(chunkA, "kb1", "k1");
        IndexInfo b = info(chunkB, "kb2", "k2");
        repo.batchSave(List.of(a, b), vectors(
                chunkA, new float[] {1f, 0f, 0f},
                chunkB, new float[] {0f, 1f, 0f}));

        // 1) 向量检索：kb1 只应命中 A，score=certainty≈1
        List<RetrieveResult> hit = repo.retrieve(vectorParams(new float[] {1f, 0f, 0f}, 5,
                List.of("kb1"), null));
        assertThat(hit.get(0).results()).hasSize(1);
        assertThat(hit.get(0).results().get(0).chunkId).isEqualTo(chunkA);
        assertThat(hit.get(0).results().get(0).score).isGreaterThan(0.9);

        // 2) 关键词检索：BM25 + gse 分词命中（中文 query）
        RetrieveParams kw = new RetrieveParams();
        kw.retrieverType = EngineTypes.RETRIEVER_KEYWORDS;
        kw.query = "中文";
        kw.topK = 5;
        List<RetrieveResult> kwHits = repo.retrieve(kw);
        assertThat(kwHits.get(0).results()).isNotEmpty();
        assertThat(kwHits.get(0).results().get(0).score).isEqualTo(1.0);

        // 3) merge 更新 tag 后：对象仍可被向量检索（证明没清属性/向量——修正的核心）
        repo.batchUpdateChunkTagID(Map.of(chunkA, "t9"));
        List<RetrieveResult> byTag = repo.retrieve(vectorParams(new float[] {1f, 0f, 0f}, 5,
                null, List.of("t9")));
        assertThat(byTag.get(0).results()).hasSize(1);
        assertThat(byTag.get(0).results().get(0).chunkId).isEqualTo(chunkA);

        // 4) 停用/启用（merge 单字段）：kb1 范围内过滤生效（注：kb2 的 B 也会命中，故带 kb 过滤）
        repo.batchUpdateChunkEnabledStatus(Map.of(chunkA, false));
        assertThat(repo.retrieve(vectorParams(new float[] {1f, 0f, 0f}, 5, List.of("kb1"), null))
                .get(0).results()).isEmpty();
        repo.batchUpdateChunkEnabledStatus(Map.of(chunkA, true));
        assertThat(repo.retrieve(vectorParams(new float[] {1f, 0f, 0f}, 5, List.of("kb1"), null))
                .get(0).results()).hasSize(1);

        // 5) 拷贝（offset 分页 + 命名向量回搬）：目标 KB 下应能按同样向量检索到新 chunk
        String chunkA2 = UUID.randomUUID().toString();
        repo.copyIndices("kb2", Map.of("k2", "tk2"), Map.of(chunkB, chunkA2), "targetKb", 3,
                "document");
        List<RetrieveResult> copied = repo.retrieve(vectorParams(new float[] {0f, 1f, 0f}, 5,
                List.of("targetKb"), null));
        assertThat(copied.get(0).results()).hasSize(1);
        assertThat(copied.get(0).results().get(0).chunkId).isEqualTo(chunkA2);

        // 6) move：kb2 → kb3
        repo.moveKnowledgeIndices("kb2", "kb3", "k2", List.of(), 3, "document");
        assertThat(repo.retrieve(vectorParams(new float[] {0f, 1f, 0f}, 5, List.of("kb2"), null))
                .get(0).results()).isEmpty();
        assertThat(repo.retrieve(vectorParams(new float[] {0f, 1f, 0f}, 5, List.of("kb3"), null))
                .get(0).results()).hasSize(1);

        // 7) 删除（按 chunk id）
        repo.deleteByChunkIdList(List.of(chunkA2), 3, "document");
        assertThat(repo.retrieve(vectorParams(new float[] {0f, 1f, 0f}, 5,
                List.of("targetKb"), null)).get(0).results()).isEmpty();
    }
}
