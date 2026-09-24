package com.ragagent.retrieval.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.ragagent.embedding.Embedder;
import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;
import com.ragagent.vectorstore.domain.VectorStore;
import com.ragagent.vectorstore.mapper.VectorStoreRepository;

/**
 * 接线批测试共用的假件——对照 Go {@code factory_test.go} / {@code registry_test.go} /
 * {@code registry_rehydrate_test.go} 里的 {@code fakeEngine} / {@code fakeOwnership} /
 * {@code fakeStoreRepo} / {@code mockEngineService}。
 */
final class RetrievalEngineTestSupport {

    private RetrievalEngineTestSupport() {
    }

    /** 对照 {@code mockEngineService}：记下被调的方法名，便于断言"扇出到了谁"。 */
    static class FakeEngineService implements RetrieveEngineService {

        final String engineType;
        final List<String> support;
        final List<String> calls = new CopyOnWriteArrayList<>();
        /** 扇出时收到的最后一批索引项（去重断言用）。 */
        final List<IndexInfo> batchPayload = new CopyOnWriteArrayList<>();
        List<RetrieveResult> retrieveResult = List.of();
        long estimateValue = 0L;
        /** 命中即抛的失败方法名（对照 Go 里让某条扇出失败的写法）。 */
        RuntimeException failure;
        String failureOn;

        FakeEngineService(String engineType, String... support) {
            this.engineType = engineType;
            this.support = List.of(support);
        }

        @Override
        public String engineType() {
            return engineType;
        }

        @Override
        public List<String> support() {
            return support;
        }

        @Override
        public List<RetrieveResult> retrieve(RetrieveParams params) {
            calls.add("retrieve:" + params.retrieverType);
            return retrieveResult;
        }

        @Override
        public void index(Embedder embedder, IndexInfo indexInfo, List<String> retrieverTypes) {
            calls.add("index");
            maybeFail("index");
        }

        @Override
        public void batchIndex(Embedder embedder, List<IndexInfo> indexInfoList,
                               List<String> retrieverTypes) {
            calls.add("batchIndex:" + indexInfoList.size());
            batchPayload.addAll(indexInfoList);
            maybeFail("batchIndex");
        }

        @Override
        public long estimateStorageSize(Embedder embedder, List<IndexInfo> indexInfoList,
                                        List<String> retrieverTypes) {
            calls.add("estimateStorageSize");
            return estimateValue;
        }

        @Override
        public void copyIndices(String sourceKnowledgeBaseId,
                                Map<String, String> sourceToTargetKbIdMap,
                                Map<String, String> sourceToTargetChunkIdMap,
                                String targetKnowledgeBaseId, int dimension,
                                String knowledgeType) {
            calls.add("copyIndices");
            maybeFail("copyIndices");
        }

        @Override
        public void deleteByChunkIdList(List<String> chunkIdList, int dimension,
                                        String knowledgeType) {
            calls.add("deleteByChunkIdList");
            maybeFail("deleteByChunkIdList");
        }

        @Override
        public void deleteBySourceIdList(List<String> sourceIdList, int dimension,
                                         String knowledgeType) {
            calls.add("deleteBySourceIdList");
            maybeFail("deleteBySourceIdList");
        }

        @Override
        public void deleteByKnowledgeIdList(List<String> knowledgeIdList, int dimension,
                                            String knowledgeType) {
            calls.add("deleteByKnowledgeIdList");
            maybeFail("deleteByKnowledgeIdList");
        }

        @Override
        public void batchUpdateChunkEnabledStatus(Map<String, Boolean> chunkStatusMap) {
            calls.add("batchUpdateChunkEnabledStatus");
            maybeFail("batchUpdateChunkEnabledStatus");
        }

        @Override
        public void batchUpdateChunkTagID(Map<String, String> chunkTagMap) {
            calls.add("batchUpdateChunkTagID");
            maybeFail("batchUpdateChunkTagID");
        }

        private void maybeFail(String method) {
            if (failure != null && method.equals(failureOn)) {
                throw failure;
            }
        }
    }

    /** 带迁移能力的假件（对照 Go 里实现了 {@code KnowledgeIndexMover} 的引擎）。 */
    static class MoverFakeEngineService extends FakeEngineService
            implements RetrieveEngineService.KnowledgeIndexMover,
            RetrieveEngineService.KnowledgeIndexMoveValidator {

        RuntimeException validateFailure;

        MoverFakeEngineService(String engineType, String... support) {
            super(engineType, support);
        }

        @Override
        public void validateKnowledgeIndexMove() {
            calls.add("validateKnowledgeIndexMove");
            if (validateFailure != null) {
                throw validateFailure;
            }
        }

        @Override
        public void moveKnowledgeIndices(String sourceKb, String targetKb, String knowledgeId,
                                         List<String> chunkIds, int dimension,
                                         String knowledgeType) {
            calls.add("moveKnowledgeIndices");
        }
    }

    /** 对照 {@code fakeOwnership}：内存归属表；{@code error} 非空时每次调用都抛（模拟基础设施故障）。 */
    static class FakeOwnership implements TenantStoreOwnership {

        final Map<String, Long> owned = new HashMap<>();
        RuntimeException error;
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public boolean storeOwnedBy(String storeId, long tenantId) {
            calls.add(storeId + ":" + tenantId);
            if (error != null) {
                throw error;
            }
            Long owner = owned.get(storeId);
            return owner != null && owner == tenantId;
        }

        int callCount() {
            return calls.size();
        }
    }

    /** 对照 {@code fakeStoreRepo}：只服务一个 store；其余方法大声报错（Go 用嵌入接口 nil-panic 表达）。 */
    static class FakeStoreRepo implements VectorStoreRepository {

        VectorStore store;
        RuntimeException error;

        FakeStoreRepo(VectorStore store) {
            this.store = store;
        }

        @Override
        public VectorStore getByID(long tenantId, String id) {
            if (error != null) {
                throw error;
            }
            return store;
        }

        @Override
        public List<VectorStore> list(long tenantId) {
            throw new UnsupportedOperationException("list: not used in these tests");
        }

        @Override
        public int create(VectorStore s, java.time.OffsetDateTime now) {
            throw new UnsupportedOperationException("create: not used in these tests");
        }

        @Override
        public int updateName(VectorStore s, java.time.OffsetDateTime now) {
            throw new UnsupportedOperationException("updateName: not used in these tests");
        }

        @Override
        public int updateConnectionConfig(VectorStore s) {
            throw new UnsupportedOperationException("updateConnectionConfig: not used in these tests");
        }

        @Override
        public int delete(long tenantId, String id) {
            throw new UnsupportedOperationException("delete: not used in these tests");
        }
    }

    /** 造一个只有 id 的 store 行（重建路径只需要它非空）。 */
    static VectorStore store(String id) {
        VectorStore s = new VectorStore();
        s.setId(id);
        s.setEngineType(EngineTypes.ENGINE_ELASTICSEARCH);
        return s;
    }

    static IndexInfo indexInfo(String id, String sourceId, String content) {
        IndexInfo info = new IndexInfo();
        info.id = id;
        info.sourceId = sourceId;
        info.content = content;
        return info;
    }

    /** 被扇出调用收到的 sourceId 列表（按到达顺序；用于去重断言）。 */
    static List<String> sourceIds(List<IndexInfo> infos) {
        List<String> out = new ArrayList<>();
        for (IndexInfo info : infos) {
            out.add(info.sourceId);
        }
        return out;
    }
}
