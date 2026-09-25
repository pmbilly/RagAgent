package com.ragagent.retrieval.engine.milvus;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.ragagent.retrieval.engine.EngineTypes;
import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;
import com.ragagent.vectorstore.domain.IndexConfig;

/**
 * Milvus 驱动对<b>真实服务端</b>的端到端验证（env 门控，默认跳过）：起 compose 口径的
 * {@code milvusdb/milvus:v2.6.11} standalone，然后：
 *
 * <pre>
 * docker run -d --name WeKnora-milvus-local --security-opt seccomp=unconfined \
 *   -p 19530:19530 -p 9091:9091 \
 *   -e ETCD_USE_EMBED=true -e ETCD_DATA_DIR=/var/lib/milvus/etcd \
 *   -e COMMON_STORAGETYPE=local -e DEPLOY_MODE=STANDALONE \
 *   milvusdb/milvus:v2.6.11 milvus run standalone
 * WEKNORA_MILVUS_IT=true ./gradlew :server:test --tests "*MilvusDriverLocalIT*"
 * </pre>
 *
 * 覆盖：建集合（BM25 函数 + 稀疏列 + 索引参数）→ 批量写 → 向量检索（distance 分数）→
 * BM25 中文关键词检索 → tag/enabled 的"查整行→回写"（向量必须仍在）→ 拷贝（offset 分页）→
 * move → 删除。Milvus 默认 Bounded 一致性有可见性延迟 → 断言用轮询等待（照真实部署语义）。
 */
@EnabledIfEnvironmentVariable(named = "WEKNORA_MILVUS_IT", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MilvusDriverLocalIT {

    private static final String ADDR = System.getenv().getOrDefault("WEKNORA_MILVUS_ADDRESS",
            "localhost:19530");
    private static final long AWAIT_TIMEOUT_MS = 30_000;

    private final String base = "weknora_it_" + UUID.randomUUID().toString().substring(0, 8);
    private MilvusRetrieveRepository repo;

    @BeforeAll
    void setUp() {
        IndexConfig idx = new IndexConfig();
        idx.collectionPrefix = base;
        repo = MilvusRetrieveRepository.create(ADDR, "", "", "", idx, null);
    }

    @AfterAll
    void tearDown() throws IOException, InterruptedException {
        HttpClient http = HttpClient.newHttpClient();
        String body = "{\"collectionName\":\"" + base + "_3\"}";
        http.send(HttpRequest.newBuilder(URI.create("http://" + ADDR + "/v2/vectordb/collections/drop"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static IndexInfo info(String chunkId, String kb, String knowledgeId) {
        IndexInfo i = new IndexInfo();
        i.chunkId = chunkId;
        i.sourceId = chunkId;
        // 注意：Milvus 的 enable_analyzer 用标准分析器（按 CJK 连段切分，非分词）——
        // 关键词要用"独立成段的词"才命中（Go 侧同款 schema，行为一致，见 known-issues）
        i.content = "中文 检索 hello " + chunkId.substring(0, 4);
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

    private static RetrieveParams vectorParams(float[] embedding, List<String> kbs,
                                               List<String> tags) {
        RetrieveParams p = new RetrieveParams();
        p.retrieverType = EngineTypes.RETRIEVER_VECTOR;
        p.embedding = embedding;
        p.topK = 5;
        p.threshold = 0.1;
        p.knowledgeBaseIds = kbs;
        p.tagIds = tags;
        return p;
    }

    /** 轮询等待（Milvus 默认 Bounded 一致性：写后短窗口内读可能不可见）。 */
    private static List<RetrieveResult> awaitHits(Callable<List<RetrieveResult>> call,
                                                  String what) throws Exception {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
        List<RetrieveResult> last = null;
        while (System.currentTimeMillis() < deadline) {
            last = call.call();
            if (!last.get(0).results().isEmpty()) {
                return last;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("timed out waiting for " + what + "; last=" + last);
    }

    @Test
    @DisplayName("真服务端全链：建集合→写→向量查→BM25 中文查→tag/enabled 回写→拷贝→move→删除")
    void endToEnd() throws Exception {
        String chunkA = UUID.randomUUID().toString();
        String chunkB = UUID.randomUUID().toString();
        repo.batchSave(List.of(info(chunkA, "kb1", "k1"), info(chunkB, "kb2", "k2")), vectors(
                chunkA, new float[] {1f, 0f, 0f},
                chunkB, new float[] {0f, 1f, 0f}));

        // 1) 向量检索：kb1 只应命中 A
        List<RetrieveResult> hits = awaitHits(
                () -> repo.retrieve(vectorParams(new float[] {1f, 0f, 0f}, List.of("kb1"), null)),
                "vector hit for kb1");
        assertThat(hits.get(0).results()).hasSize(1);
        assertThat(hits.get(0).results().get(0).chunkId).isEqualTo(chunkA);

        // 2) BM25 中文关键词检索（gse 等价的中文分析器由 Milvus 内建）
        RetrieveParams kw = new RetrieveParams();
        kw.retrieverType = EngineTypes.RETRIEVER_KEYWORDS;
        kw.query = "中文";
        kw.topK = 5;
        List<RetrieveResult> kwHits = awaitHits(() -> repo.retrieve(kw), "BM25 hit");
        assertThat(kwHits.get(0).results()).isNotEmpty();
        assertThat(kwHits.get(0).results().get(0).score).isEqualTo(1.0);

        // 3) tag 更新（查整行 → 回写）：对象仍可被向量检索（证明向量随行回写没丢）
        repo.batchUpdateChunkTagID(Map.of(chunkA, "t9"));
        List<RetrieveResult> byTag = awaitHits(
                () -> repo.retrieve(vectorParams(new float[] {1f, 0f, 0f}, null, List.of("t9"))),
                "tag-filtered vector hit");
        assertThat(byTag.get(0).results()).hasSize(1);
        assertThat(byTag.get(0).results().get(0).chunkId).isEqualTo(chunkA);

        // 4) enabled 更新：停用 → kb1 范围不可见；启用 → 回来
        repo.batchUpdateChunkEnabledStatus(Map.of(chunkA, false));
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (repo.retrieve(vectorParams(new float[] {1f, 0f, 0f}, List.of("kb1"), null))
                    .get(0).results().isEmpty()) {
                break;
            }
            Thread.sleep(500);
        }
        assertThat(repo.retrieve(vectorParams(new float[] {1f, 0f, 0f}, List.of("kb1"), null))
                .get(0).results()).isEmpty();
        repo.batchUpdateChunkEnabledStatus(Map.of(chunkA, true));
        awaitHits(() -> repo.retrieve(vectorParams(new float[] {1f, 0f, 0f}, List.of("kb1"), null)),
                "re-enabled hit");

        // 5) 拷贝：kb2 → targetKb（新 chunk id），目标范围可按同向量检索到
        String chunkB2 = UUID.randomUUID().toString();
        repo.copyIndices("kb2", Map.of("k2", "tk2"), Map.of(chunkB, chunkB2), "targetKb", 3,
                "document");
        List<RetrieveResult> copied = awaitHits(
                () -> repo.retrieve(vectorParams(new float[] {0f, 1f, 0f}, List.of("targetKb"), null)),
                "copied hit in targetKb");
        assertThat(copied.get(0).results()).hasSize(1);
        assertThat(copied.get(0).results().get(0).chunkId).isEqualTo(chunkB2);

        // 6) move：kb2 → kb3。照 Go 的 drain 语义：Bounded 一致性下"重复 ID = 更新尚未可见"，
        // 驱动故意抛错让调用方重试（见 move.go 注释）——IT 因此按调用方姿态重试。
        int attempts = 0;
        while (true) {
            try {
                repo.moveKnowledgeIndices("kb2", "kb3", "k2", List.of(), 3, "document");
                break;
            } catch (IllegalStateException e) {
                if (!e.getMessage().contains("invalid or repeated move index") || ++attempts >= 10) {
                    throw e;
                }
                Thread.sleep(1000);
            }
        }
        awaitHits(() -> repo.retrieve(vectorParams(new float[] {0f, 1f, 0f}, List.of("kb3"), null)),
                "moved hit in kb3");

        // 7) 删除（按 chunk id）
        repo.deleteByChunkIdList(List.of(chunkB2), 3, "document");
        deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (repo.retrieve(vectorParams(new float[] {0f, 1f, 0f}, List.of("targetKb"), null))
                    .get(0).results().isEmpty()) {
                break;
            }
            Thread.sleep(500);
        }
        assertThat(repo.retrieve(vectorParams(new float[] {0f, 1f, 0f}, List.of("targetKb"), null))
                .get(0).results()).isEmpty();
    }
}
