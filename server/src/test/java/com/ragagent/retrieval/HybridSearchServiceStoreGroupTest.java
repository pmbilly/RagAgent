package com.ragagent.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ragagent.auth.service.TenantService;
import com.ragagent.chatpipeline.SearchParams;
import com.ragagent.common.error.BizException;
import com.ragagent.knowledge.domain.KnowledgeBaseIndexingStrategy;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.mapper.ChunkRepository;
import com.ragagent.knowledge.client.EmbedderClient;
import com.ragagent.knowledge.service.KnowledgeBaseService;
import com.ragagent.knowledge.service.KnowledgeService;
import com.ragagent.model.domain.Model;
import com.ragagent.model.domain.ModelParameters;
import com.ragagent.model.service.ModelService;
import com.ragagent.retrieval.engine.CompositeRetrieveEngine;
import com.ragagent.retrieval.engine.EngineTypes;
import com.ragagent.retrieval.engine.EngineTypes.IndexWithScore;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;
import com.ragagent.retrieval.engine.KeywordsVectorHybridRetrieveEngineService;
import com.ragagent.retrieval.engine.PgVectorRetrieveRepository;
import com.ragagent.retrieval.engine.RetrieveEngineException;
import com.ragagent.retrieval.engine.RetrieveEngineRegistry;
import com.ragagent.retrieval.engine.RetrieverEngineParams;
import com.ragagent.retrieval.engine.TenantStoreOwnership;

/**
 * 接线批第 4 步的 store-group 面钉子（对照 Go knowledgebase_search_storegroup.go /
 * _fanout.go 的纯逻辑）：工厂哨兵 → 2200/2201 的分类映射、多 KB 嵌入模型一致性闸门、
 * 跨引擎类型判定、TopK 覆写不回写 BaseParams、多组扇出的 all-or-nothing。
 *
 * <p>绑 store 的错误路径用 {@code disableVectorMatch=true} 绕开查询向量预计算——
 * 照 Go：向量预计算先于 store-group 解析，向量开启且模型缺失时先报 embed 错，
 * 这是两侧一致的次序，测试改走不触发预计算的门。</p>
 */
class HybridSearchServiceStoreGroupTest {

    private static final long TENANT = 10002L;

    private KnowledgeBaseService kbService;
    private KnowledgeService knowledgeService;
    private ChunkRepository chunkRepository;
    private ModelService modelService;
    private TenantService tenantService;
    private EmbedderClient embedderClient;
    private PgVectorRetrieveRepository pgRepository;
    private RetrieveEngineRegistry registry;
    private TenantStoreOwnership ownership;
    private HybridSearchService service;

    @BeforeEach
    void setUp() {
        kbService = mock(KnowledgeBaseService.class);
        knowledgeService = mock(KnowledgeService.class);
        chunkRepository = mock(ChunkRepository.class);
        modelService = mock(ModelService.class);
        tenantService = mock(TenantService.class);
        embedderClient = mock(EmbedderClient.class);
        pgRepository = mock(PgVectorRetrieveRepository.class);
        registry = mock(RetrieveEngineRegistry.class);
        ownership = mock(TenantStoreOwnership.class);
        service = new HybridSearchService(kbService, knowledgeService, chunkRepository,
                modelService, tenantService, embedderClient, pgRepository, registry, ownership);
    }

    private KnowledgeBase kb(String id, String vectorStoreId, String embeddingModelId) {
        KnowledgeBase k = new KnowledgeBase();
        k.setId(id);
        k.setTenantId(TENANT);
        k.setType("document");
        k.setEmbeddingModelId(embeddingModelId);
        k.setVectorStoreId(vectorStoreId);
        KnowledgeBaseIndexingStrategy s = new KnowledgeBaseIndexingStrategy();
        s.setVectorEnabled(true);
        s.setKeywordEnabled(true);
        k.setIndexingStrategy(s);
        return k;
    }

    /** 零策略（vector+keyword 全关）的 KB——BaseParams 恒空，与引擎注册状态解耦。 */
    private KnowledgeBase zeroStrategyKb(String id, String embeddingModelId) {
        KnowledgeBase k = kb(id, null, embeddingModelId);
        KnowledgeBaseIndexingStrategy s = new KnowledgeBaseIndexingStrategy();
        s.setVectorEnabled(false);
        s.setKeywordEnabled(false);
        k.setIndexingStrategy(s);
        return k;
    }

    private Model model(String name) {
        Model m = new Model();
        m.setName(name);
        m.setParameters(new ModelParameters());
        m.getParameters().setBaseUrl("http://model-host/v1");
        return m;
    }

    private SearchParams params(String... kbIds) {
        SearchParams p = new SearchParams();
        p.setQueryText("q");
        // 绕开查询向量预计算（见类注释），让请求直达 store-group 解析
        p.setDisableVectorMatch(true);
        p.setKnowledgeBaseIds(List.of(kbIds));
        return p;
    }

    // ── validateSameEmbeddingModel（storegroup.go L248-289） ────────────────

    @Test
    void multiKbWithDifferentEmbeddingModelsIsRejected() {
        when(modelService.getModelByID("emb-a")).thenReturn(model("modelA"));
        when(modelService.getModelByID("emb-b")).thenReturn(model("modelB"));
        when(kbService.getAllTenantById("kb-a")).thenReturn(kb("kb-a", null, "emb-a"));
        when(kbService.getAllTenantById("kb-b")).thenReturn(kb("kb-b", null, "emb-b"));

        assertThatThrownBy(() -> service.hybridSearch("kb-a", params("kb-a", "kb-b")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    // Go NewBadRequestError：HTTP 400，AppError code 1000
                    assertThat(e.appError().httpCode()).isEqualTo(400);
                    assertThat(e.appError().message()).contains(
                            "selected knowledge bases use different embedding models");
                });
    }

    @Test
    void multiKbSharingOneEmbeddingModelPassesValidation() {
        when(modelService.getModelByID(anyString())).thenReturn(model("shared"));
        when(kbService.getAllTenantById("kb-a")).thenReturn(zeroStrategyKb("kb-a", "emb-a"));
        when(kbService.getAllTenantById("kb-b")).thenReturn(zeroStrategyKb("kb-b", "emb-a"));
        // env-store 组的引擎解析在 RETRIEVE_DRIVER 已配置的机器上也会发生——桩成真实件，
        // 零策略 KB 的 BaseParams 恒空，两种环境下都走 allBaseParamsEmpty → data:null
        when(registry.getRetrieveEngineService(anyString()))
                .thenReturn(new KeywordsVectorHybridRetrieveEngineService(
                        new RecordingRepo(new AtomicReference<>(-1)),
                        EngineTypes.ENGINE_POSTGRES));

        assertThat(service.hybridSearch("kb-a", params("kb-a", "kb-b"))).isNull();
    }

    // ── 工厂哨兵 → 2200/2201（storegroup.go classifyFactoryError） ─────────

    @Test
    void crossTenantStoreMapsTo2200() {
        when(kbService.getAllTenantById("kb-1")).thenReturn(kb("kb-1", "store-1", "emb-a"));
        when(ownership.storeOwnedBy("store-1", TENANT)).thenReturn(false);

        assertThatThrownBy(() -> service.hybridSearch("kb-1", params("kb-1")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.appError().code()).isEqualTo(2200);
                    assertThat(e.appError().message())
                            .isEqualTo("vector store bound to the knowledge base is not available");
                });
    }

    @Test
    void unregisteredStoreMapsTo2201() {
        when(kbService.getAllTenantById("kb-1")).thenReturn(kb("kb-1", "ghost-store", "emb-a"));
        when(ownership.storeOwnedBy("ghost-store", TENANT)).thenReturn(true);
        when(registry.getOrLoadByStoreId(TENANT, "ghost-store"))
                .thenThrow(RetrieveEngineException.VECTOR_STORE_NOT_FOUND);

        assertThatThrownBy(() -> service.hybridSearch("kb-1", params("kb-1")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.appError().code()).isEqualTo(2201);
                    assertThat(e.appError().message())
                            .isEqualTo("vector store is currently unavailable");
                });
    }

    @Test
    void unownedStoreWithoutRegistrationWiringMapsTo2200BeforeRegistry() {
        // 归属为假时不查注册表（resolveBoundEngine 的短路序）
        when(kbService.getAllTenantById("kb-1")).thenReturn(kb("kb-1", "store-x", "emb-a"));
        when(ownership.storeOwnedBy("store-x", TENANT)).thenReturn(false);

        assertThatThrownBy(() -> service.hybridSearch("kb-1", params("kb-1")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).appError().code())
                .isEqualTo(2200);
    }

    // ── 扇出纯逻辑（_fanout.go） ────────────────────────────────────────────

    @Test
    void mixedEngineTypesDetection() {
        assertThat(HybridSearchService.hasMixedEngineTypes(List.of())).isFalse();
        assertThat(HybridSearchService.hasMixedEngineTypes(List.of(
                new RetrieveResult(List.of(), "postgres", "vector")))).isFalse();
        assertThat(HybridSearchService.hasMixedEngineTypes(List.of(
                new RetrieveResult(List.of(), "postgres", "vector"),
                new RetrieveResult(List.of(), "postgres", "keywords")))).isFalse();
        assertThat(HybridSearchService.hasMixedEngineTypes(List.of(
                new RetrieveResult(List.of(), "postgres", "vector"),
                new RetrieveResult(List.of(), "elasticsearch", "vector")))).isTrue();
    }

    @Test
    void knownEngineTypesMatchGoTable() {
        assertThat(HybridSearchService.isKnownEngineType("postgres")).isTrue();
        assertThat(HybridSearchService.isKnownEngineType("elasticsearch")).isTrue();
        assertThat(HybridSearchService.isKnownEngineType("elastic_faiss")).isTrue();
        assertThat(HybridSearchService.isKnownEngineType("infinity")).isTrue();
        assertThat(HybridSearchService.isKnownEngineType("tencent_vectordb")).isTrue();
        assertThat(HybridSearchService.isKnownEngineType("sqlite")).isTrue();
        assertThat(HybridSearchService.isKnownEngineType("mystery")).isFalse();
    }

    @Test
    void storeKindLabelNeverEchoesUuid() {
        assertThat(HybridSearchService.storeKindLabel("")).isEqualTo("env");
        assertThat(HybridSearchService.storeKindLabel("some-uuid")).isEqualTo("bound");
    }

    @Test
    void multiStoreTimeoutDefaultsTo30s() {
        assertThat(HybridSearchService.multiStoreRetrieveTimeout()).isEqualTo(30);
    }

    @Test
    void singleGroupFastPathPassesTopKWithoutTouchingBaseParams() {
        AtomicReference<Integer> seenTopK = new AtomicReference<>(-1);
        RetrieveParams base = new RetrieveParams();
        base.query = "q";
        base.topK = 11;
        base.retrieverType = EngineTypes.RETRIEVER_VECTOR;

        KeywordsVectorHybridRetrieveEngineService engine =
                new KeywordsVectorHybridRetrieveEngineService(new RecordingRepo(seenTopK),
                        EngineTypes.ENGINE_POSTGRES);
        HybridSearchService.StoreGroup group = new HybridSearchService.StoreGroup(
                "", TENANT, List.of("kb-1"), compositeOf(engine),
                List.of(base), 42);

        List<RetrieveResult> out = service.retrieveFromStores(List.of(group));

        assertThat(out).hasSize(1).as("RecordingRepo 返回一条命中");
        assertThat(out.get(0).results().get(0).chunkId).isEqualTo("c-42").as("TopK 进了检索参数");
        assertThat(seenTopK.get()).isEqualTo(42).as("Retrieve 收到覆写后的 TopK");
        assertThat(base.topK).isEqualTo(11).as("BaseParams 保持不可变");
    }

    @Test
    void multiGroupFanoutIsAllOrNothing() {
        // 两组：一组成功、一组失败 → 整条检索 2201（all-or-nothing，照 Go errgroup）
        RecordingRepo ok = new RecordingRepo(new AtomicReference<>(-1));
        RecordingRepo bad = new RecordingRepo(new AtomicReference<>(-1));
        bad.failureOnRetrieve = new IllegalStateException("store down");

        HybridSearchService.StoreGroup g1 = new HybridSearchService.StoreGroup("s1", TENANT,
                List.of("kb-1"), compositeOf(engineOf(ok)),
                List.of(vectorParams(5)), 5);
        HybridSearchService.StoreGroup g2 = new HybridSearchService.StoreGroup("s2", TENANT,
                List.of("kb-2"), compositeOf(engineOf(bad)),
                List.of(vectorParams(5)), 5);

        assertThatThrownBy(() -> service.retrieveFromStores(List.of(g1, g2)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.appError().code()).isEqualTo(2201);
                    assertThat(e.appError().message())
                            .isEqualTo("vector retrieval failed for one or more bound stores");
                });
    }

    /** 按引擎类型直查的小注册表（绕开 ofSingle 的包内可见性）。 */
    private static CompositeRetrieveEngine compositeOf(
            com.ragagent.retrieval.engine.RetrieveEngineService engine) {
        return CompositeRetrieveEngine.create(new RetrieveEngineRegistry() {
            @Override
            public void register(com.ragagent.retrieval.engine.RetrieveEngineService service) {
            }

            @Override
            public com.ragagent.retrieval.engine.RetrieveEngineService getRetrieveEngineService(
                    String engineType) {
                return engine;
            }

            @Override
            public List<com.ragagent.retrieval.engine.RetrieveEngineService>
                    getAllRetrieveEngineServices() {
                return List.of(engine);
            }

            @Override
            public void registerWithStoreId(String storeId,
                                            com.ragagent.retrieval.engine.RetrieveEngineService
                                                    service) {
            }

            @Override
            public com.ragagent.retrieval.engine.RetrieveEngineService getByStoreId(
                    String storeId) {
                return engine;
            }

            @Override
            public com.ragagent.retrieval.engine.RetrieveEngineService getOrLoadByStoreId(
                    long tenantId, String storeId) {
                return engine;
            }

            @Override
            public void unregisterByStoreId(String storeId) {
            }

            @Override
            public boolean canRebuildStores() {
                return false;
            }
        }, List.of(new RetrieverEngineParams(EngineTypes.RETRIEVER_VECTOR,
                EngineTypes.ENGINE_POSTGRES)));
    }

    private KeywordsVectorHybridRetrieveEngineService engineOf(RecordingRepo repo) {
        return new KeywordsVectorHybridRetrieveEngineService(repo, EngineTypes.ENGINE_POSTGRES);
    }

    private static RetrieveParams vectorParams(int topK) {
        RetrieveParams p = new RetrieveParams();
        p.query = "q";
        p.topK = topK;
        p.retrieverType = EngineTypes.RETRIEVER_VECTOR;
        return p;
    }

    /** 记录 topK / 可注入失败的假仓库。 */
    static final class RecordingRepo
            implements com.ragagent.retrieval.engine.RetrieveEngineRepository {
        final AtomicReference<Integer> seenTopK;
        RuntimeException failureOnRetrieve;

        RecordingRepo(AtomicReference<Integer> seenTopK) {
            this.seenTopK = seenTopK;
        }

        @Override
        public String engineType() {
            return EngineTypes.ENGINE_POSTGRES;
        }

        @Override
        public List<String> support() {
            return List.of(EngineTypes.RETRIEVER_VECTOR);
        }

        @Override
        public void save(EngineTypes.IndexInfo indexInfo, Map<String, Object> params) {
        }

        @Override
        public void batchSave(List<EngineTypes.IndexInfo> indexInfoList,
                              Map<String, Object> params) {
        }

        @Override
        public long estimateStorageSize(List<EngineTypes.IndexInfo> indexInfoList,
                                        Map<String, Object> params) {
            return 0;
        }

        @Override
        public void deleteByChunkIdList(List<String> chunkIdList, int dimension,
                                        String knowledgeType) {
        }

        @Override
        public void deleteBySourceIdList(List<String> sourceIdList, int dimension,
                                         String knowledgeType) {
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
            seenTopK.set(params.topK);
            if (failureOnRetrieve != null) {
                throw failureOnRetrieve;
            }
            IndexWithScore hit = new IndexWithScore();
            hit.chunkId = "c-" + params.topK;
            return List.of(new RetrieveResult(List.of(hit),
                    EngineTypes.ENGINE_POSTGRES, params.retrieverType));
        }
    }
}
