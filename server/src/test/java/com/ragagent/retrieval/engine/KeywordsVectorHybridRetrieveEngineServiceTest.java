package com.ragagent.retrieval.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;
import com.ragagent.embedding.Embedder;

/**
 * KV 混合检索引擎服务（W5γ4.4a）对照 Go
 * {@code service/retriever/keywords_vector_hybrid_indexer.go}：嵌入映射一律按 SourceID、
 * 向量路分批 40/非向量路分批 10、退避 5 次（200ms 起翻倍）、净化（内联 base64 → [image] +
 * 按码点截断 20000）、迁移能力探测；并验证 EstimateStorageSize 的"按 SourceID 占位"
 * 修复（Go 用 ChunkID，生成问题估不到向量）。
 */
class KeywordsVectorHybridRetrieveEngineServiceTest {

    /** 捕获型仓库桩：记录 save/batchSave/estimate/delete/retrieve 的入参。 */
    static class StubRepo implements RetrieveEngineRepository {
        final List<Map<String, Object>> saveParams = new CopyOnWriteArrayList<>();
        final List<List<IndexInfo>> savedBatches = new CopyOnWriteArrayList<>();
        final List<Map<String, Object>> batchParams = new CopyOnWriteArrayList<>();
        final List<Map<String, Object>> estimateParams = new CopyOnWriteArrayList<>();
        final List<String> deletedSourceIds = new CopyOnWriteArrayList<>();
        boolean supportVector = true;

        @Override
        public String engineType() {
            return EngineTypes.ENGINE_ELASTICSEARCH;
        }

        @Override
        public List<String> support() {
            return supportVector ? List.of("keywords", "vector") : List.of("keywords");
        }

        @Override
        public void save(IndexInfo indexInfo, Map<String, Object> params) {
            saveParams.add(params);
        }

        @Override
        public void batchSave(List<IndexInfo> indexInfoList, Map<String, Object> params) {
            // 并发下两个 list 必须成对追加，否则下标错位（照 Go 的 errgroup 并发语义）
            synchronized (this) {
                savedBatches.add(List.copyOf(indexInfoList));
                batchParams.add(params);
            }
        }

        @Override
        public long estimateStorageSize(List<IndexInfo> indexInfoList, Map<String, Object> params) {
            estimateParams.add(params);
            return indexInfoList.size();
        }

        @Override
        public void deleteByChunkIdList(List<String> chunkIdList, int dimension,
                                        String knowledgeType) {
        }

        @Override
        public void deleteBySourceIdList(List<String> sourceIdList, int dimension,
                                         String knowledgeType) {
            deletedSourceIds.addAll(sourceIdList);
        }

        @Override
        public void deleteByKnowledgeIdList(List<String> knowledgeIdList, int dimension,
                                            String knowledgeType) {
        }

        @Override
        public void copyIndices(String sourceKnowledgeBaseId,
                                Map<String, String> sourceToTargetKbIdMap,
                                Map<String, String> sourceToTargetChunkIdMap,
                                String targetKnowledgeBaseId, int dimension,
                                String knowledgeType) {
        }

        @Override
        public void batchUpdateChunkEnabledStatus(Map<String, Boolean> chunkStatusMap) {
        }

        @Override
        public void batchUpdateChunkTagID(Map<String, String> chunkTagMap) {
        }

        @Override
        public List<RetrieveResult> retrieve(RetrieveParams params) {
            return List.of(new RetrieveResult(List.of(), engineType(), params.retrieverType));
        }
    }

    /** 可编程嵌入器桩：记录收到的文本。 */
    static class StubEmbedder implements Embedder {
        final List<List<String>> calls = new CopyOnWriteArrayList<>();
        final List<List<String>> batchCalls = new CopyOnWriteArrayList<>();
        List<float[]> batchResult = List.of(new float[] {1f, 2f});
        RuntimeException failure;
        int dimensions = 2;

        @Override
        public float[] embed(String text) {
            calls.add(List.of(text));
            return new float[] {1f, 2f};
        }

        @Override
        public List<float[]> batchEmbed(List<String> texts) {
            batchCalls.add(List.copyOf(texts));
            if (failure != null) {
                throw failure;
            }
            List<float[]> out = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                out.add(new float[] {i, i + 1f});
            }
            return out;
        }

        @Override
        public int getDimensions() {
            return dimensions;
        }

        @Override
        public String getModelName() {
            return "stub-embedder";
        }

        @Override
        public String getModelID() {
            return "stub-embedder-id";
        }
    }

    private static IndexInfo info(String chunkId, String sourceId, String content) {
        IndexInfo info = new IndexInfo();
        info.chunkId = chunkId;
        info.sourceId = sourceId;
        info.content = content;
        return info;
    }

    /** 底延迟压到 1ms → 退避测试不慢（默认 200ms，照 Go 常量）。 */
    private static KeywordsVectorHybridRetrieveEngineService service(StubRepo repo) {
        return new KeywordsVectorHybridRetrieveEngineService(repo,
                EngineTypes.ENGINE_ELASTICSEARCH, 1L);
    }

    @Test
    @DisplayName("Index：向量路嵌入（净化后）→ 映射按 SourceID；非向量路不嵌入但 key 仍在")
    void indexEmbedsBySourceId() throws Exception {
        StubRepo repo = new StubRepo();
        StubEmbedder embedder = new StubEmbedder();

        service(repo).index(embedder, info("c1", "c1", "  正文  <img src=\"data:image/png;base64,"
                + "A".repeat(300) + "\">"), List.of("vector", "keywords"));

        assertEquals(1, embedder.calls.size());
        assertFalse(embedder.calls.get(0).get(0).contains("base64,"), "内联图被净化");
        assertTrue(embedder.calls.get(0).get(0).contains("[image]"));
        @SuppressWarnings("unchecked")
        Map<String, float[]> map = (Map<String, float[]>) repo.saveParams.get(0).get("embedding");
        assertEquals(1, map.size());
        assertTrue(map.containsKey("c1"), "键取 SourceID（照 structs.go 的查表语义）");

        // 生成问题：sourceId ≠ chunkId，仍按 SourceID 落键
        repo.saveParams.clear();
        service(repo).index(embedder, info("c1", "c1-q9", "问"), List.of("vector"));
        @SuppressWarnings("unchecked")
        Map<String, float[]> map2 = (Map<String, float[]>) repo.saveParams.get(0).get("embedding");
        assertTrue(map2.containsKey("c1-q9"));

        // 非向量路：不调用嵌入器，params 里 embedding 是空 map（照 Go 恒设键）
        embedder.calls.clear();
        repo.saveParams.clear();
        service(repo).index(embedder, info("c2", "c2", "关键词"), List.of("keywords"));
        assertTrue(embedder.calls.isEmpty());
        assertNotNull(repo.saveParams.get(0).get("embedding"));
        assertTrue(((Map<?, ?>) repo.saveParams.get(0).get("embedding")).isEmpty());
    }

    @Test
    @DisplayName("BatchIndex：向量路每批 40（85 条 → 40/40/5）、映射按批偏移取；非向量路每批 10")
    void batchIndexChunking() throws Exception {
        StubRepo repo = new StubRepo();
        StubEmbedder embedder = new StubEmbedder();
        List<IndexInfo> infos = new ArrayList<>();
        for (int i = 0; i < 85; i++) {
            infos.add(info("c" + i, "s" + i, "内容" + i));
        }

        service(repo).batchIndex(embedder, infos, List.of("vector"));

        assertEquals(List.of(85), embedder.batchCalls.stream().map(List::size).toList());
        assertEquals(3, repo.savedBatches.size());
        assertEquals(List.of(5, 40, 40),
                repo.savedBatches.stream().map(List::size).sorted().toList(),
                "分批 40/40/5（并发 → 顺序不定）");
        int smallIdx = -1;
        for (int i = 0; i < repo.savedBatches.size(); i++) {
            if (repo.savedBatches.get(i).size() == 5) {
                smallIdx = i;
            }
        }
        assertEquals("s80", repo.savedBatches.get(smallIdx).get(0).sourceId,
                "末批首条对应 embeddings[80]");
        @SuppressWarnings("unchecked")
        Map<String, float[]> last = (Map<String, float[]>) repo.batchParams.get(smallIdx)
                .get("embedding");
        assertEquals(80f, last.get("s80")[0], 1e-6, "按批偏移 i*batchSize+j 取向量");

        repo.savedBatches.clear();
        repo.batchParams.clear();
        StubEmbedder plain = new StubEmbedder();
        List<IndexInfo> plainInfos = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            plainInfos.add(info("c" + i, "s" + i, "内容" + i));
        }
        service(repo).batchIndex(plain, plainInfos, List.of("keywords"));
        assertTrue(plain.batchCalls.isEmpty(), "非向量路不嵌入");
        assertEquals(List.of(5, 10, 10),
                repo.savedBatches.stream().map(List::size).sorted().toList());
        assertTrue(repo.batchParams.get(0).isEmpty(), "非向量路 params 为空（照 Go）");

        repo.savedBatches.clear();
        service(repo).batchIndex(embedder, List.of(), List.of("vector"));
        assertTrue(repo.savedBatches.isEmpty(), "空列表直接返回");

        // 边界：批数 > 5 → 走有界并发（12 批 = 480 条向量 → 12×40）
        StubRepo big = new StubRepo();
        List<IndexInfo> many = new ArrayList<>();
        for (int i = 0; i < 480; i++) {
            many.add(info("c" + i, "s" + i, "内容" + i));
        }
        service(big).batchIndex(embedder, many, List.of("vector"));
        assertEquals(12, big.savedBatches.size());
    }

    @Test
    @DisplayName("净化：无 base64 时不跑正则；超 20000 码点按码点截断（emoji 不劈半）")
    void sanitizeRules() {
        String plain = "a".repeat(20005);
        assertEquals(20000, KeywordsVectorHybridRetrieveEngineService
                .sanitizeForEmbedding(plain).length());

        String emojis = "😀".repeat(20001);
        String cut = KeywordsVectorHybridRetrieveEngineService.sanitizeForEmbedding(emojis);
        assertEquals(20000, cut.codePointCount(0, cut.length()));
        assertEquals(40000, cut.length(), "每码点 2 个 char，不劈半");

        String img = "<img src=\"data:image/png;base64," + "A".repeat(250) + "\">尾";
        String sanitized = KeywordsVectorHybridRetrieveEngineService.sanitizeForEmbedding(img);
        assertEquals("[image]尾", sanitized);

        assertEquals("无特殊", KeywordsVectorHybridRetrieveEngineService
                .sanitizeForEmbedding("无特殊"));
    }

    @Test
    @DisplayName("批量嵌入退避：观测 attempt 次数 = 5（用 batchCalls 计数）")
    void batchEmbedBackoffCountsCalls() {
        StubRepo repo = new StubRepo();
        StubEmbedder embedder = new StubEmbedder();
        embedder.failure = new IllegalStateException("embed upstream 500");

        assertThrows(Exception.class, () -> service(repo).batchIndex(embedder,
                List.of(info("c1", "s1", "x")), List.of("vector")));
        assertEquals(5, embedder.batchCalls.size(), "照 Go：embedRetryAttempts = 5");
    }

    @Test
    @DisplayName("EstimateStorageSize：占位向量按 SourceID 落键（修复 Go 用 ChunkID 的漏估）")
    void estimateKeysBySourceId() {
        StubRepo repo = new StubRepo();
        StubEmbedder embedder = new StubEmbedder();
        embedder.dimensions = 3;

        long size = service(repo).estimateStorageSize(embedder,
                List.of(info("c1", "c1-q9", "问"), info("c2", "c2", "答")), List.of("vector"));

        assertEquals(2, size);
        @SuppressWarnings("unchecked")
        Map<String, float[]> map = (Map<String, float[]>) repo.estimateParams.get(0)
                .get("embedding");
        assertTrue(map.containsKey("c1-q9"), "生成问题也占位（Go 按 ChunkID 会漏）");
        assertEquals(3, map.get("c1-q9").length, "占位向量长度 = GetDimensions()");

        repo.estimateParams.clear();
        service(repo).estimateStorageSize(embedder, List.of(info("c1", "c1", "x")),
                List.of("keywords"));
        assertTrue(repo.estimateParams.get(0).isEmpty(), "非向量路不带 embedding");
    }

    @Test
    @DisplayName("迁移能力探测：仓库未挂 KnowledgeIndexMover → 报 does not support moving indices")
    void moveSupportDetection() throws Exception {
        StubRepo plain = new StubRepo();
        KeywordsVectorHybridRetrieveEngineService svc = service(plain);
        assertEquals("retriever elasticsearch does not support moving indices",
                assertThrows(IllegalStateException.class, svc::validateKnowledgeIndexMove)
                        .getMessage());
        assertThrows(IllegalStateException.class, () -> svc.moveKnowledgeIndices("a", "b", "k",
                List.of(), 0, ""));

        List<String> moved = new CopyOnWriteArrayList<>();
        RetrieveEngineRepository moving = new MoverRepo(plain, moved);
        KeywordsVectorHybridRetrieveEngineService mover =
                new KeywordsVectorHybridRetrieveEngineService(moving,
                        EngineTypes.ENGINE_ELASTICSEARCH, 1L);
        mover.moveKnowledgeIndices("src", "tgt", "k1", List.of(), 0, "");
        assertEquals(List.of("src>tgt>k1"), moved);
    }

    /** 带迁移子口的仓库桩。 */
    static class MoverRepo implements RetrieveEngineRepository,
            RetrieveEngineRepository.KnowledgeIndexMover {
        private final StubRepo delegate;
        private final List<String> moved;

        MoverRepo(StubRepo delegate, List<String> moved) {
            this.delegate = delegate;
            this.moved = moved;
        }

        @Override
        public void moveKnowledgeIndices(String sourceKb, String targetKb, String knowledgeId,
                                         List<String> chunkIds, int dimension,
                                         String knowledgeType) {
            moved.add(sourceKb + ">" + targetKb + ">" + knowledgeId);
        }

        @Override
        public String engineType() {
            return delegate.engineType();
        }

        @Override
        public List<String> support() {
            return delegate.support();
        }

        @Override
        public void save(IndexInfo indexInfo, Map<String, Object> params) {
            delegate.save(indexInfo, params);
        }

        @Override
        public void batchSave(List<IndexInfo> indexInfoList, Map<String, Object> params) {
            delegate.batchSave(indexInfoList, params);
        }

        @Override
        public long estimateStorageSize(List<IndexInfo> indexInfoList, Map<String, Object> params) {
            return delegate.estimateStorageSize(indexInfoList, params);
        }

        @Override
        public void deleteByChunkIdList(List<String> chunkIds, int dimension,
                                        String knowledgeType) {
        }

        @Override
        public void deleteBySourceIdList(List<String> sourceIds, int dimension,
                                         String knowledgeType) {
            delegate.deleteBySourceIdList(sourceIds, dimension, knowledgeType);
        }

        @Override
        public void deleteByKnowledgeIdList(List<String> knowledgeIds, int dimension,
                                            String knowledgeType) {
        }

        @Override
        public void copyIndices(String sourceKnowledgeBaseId,
                                Map<String, String> sourceToTargetKbIdMap,
                                Map<String, String> sourceToTargetChunkIdMap,
                                String targetKnowledgeBaseId, int dimension,
                                String knowledgeType) {
        }

        @Override
        public void batchUpdateChunkEnabledStatus(Map<String, Boolean> chunkStatusMap) {
        }

        @Override
        public void batchUpdateChunkTagID(Map<String, String> chunkTagMap) {
        }

        @Override
        public List<RetrieveResult> retrieve(RetrieveParams params) {
            return delegate.retrieve(params);
        }
    }

    @Test
    @DisplayName("转发：Retrieve / 删除 / Support / EngineType 走仓库；CopyIndices 与批量更新可直呼")
    void delegates() throws Exception {
        StubRepo repo = new StubRepo();
        KeywordsVectorHybridRetrieveEngineService svc = service(repo);
        assertEquals("elasticsearch", svc.engineType());
        assertEquals(List.of("keywords", "vector"), svc.support());

        RetrieveParams params = new RetrieveParams();
        params.retrieverType = "keywords";
        assertEquals("keywords", svc.retrieve(params).get(0).retrieverType());

        svc.deleteBySourceIdList(List.of("s1"), 0, "");
        assertEquals(List.of("s1"), repo.deletedSourceIds);

        svc.copyIndices("kb1", Map.of(), Map.of("c1", "c2"), "kb2", 2, "");
        svc.batchUpdateChunkEnabledStatus(Map.of("c1", true));
        svc.batchUpdateChunkTagID(Map.of("c1", "t1"));
    }
}
