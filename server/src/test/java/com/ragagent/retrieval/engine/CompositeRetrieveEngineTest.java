package com.ragagent.retrieval.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;
import com.ragagent.retrieval.engine.RetrievalEngineTestSupport.FakeEngineService;
import com.ragagent.retrieval.engine.RetrievalEngineTestSupport.MoverFakeEngineService;

/**
 * 复合引擎（W5γ4.5）对照 Go {@code service/retriever/composite.go}：按检索类型分派、
 * 对全部引擎扇出、首个错误上抛、估算求和取部分和、迁移前整体预检。
 */
class CompositeRetrieveEngineTest {

    private static CompositeRetrieveEngine composite(FakeEngineService... engines) {
        EngineRegistry registry = new EngineRegistry(null, null);
        List<RetrieverEngineParams> params = new java.util.ArrayList<>();
        for (FakeEngineService svc : engines) {
            registry.register(svc);
            for (String retrieverType : svc.support) {
                params.add(new RetrieverEngineParams(retrieverType, svc.engineType));
            }
        }
        return CompositeRetrieveEngine.create(registry, params);
    }

    private static RetrieveParams retrieveParams(String retrieverType) {
        RetrieveParams params = new RetrieveParams();
        params.retrieverType = retrieverType;
        return params;
    }

    @Test
    @DisplayName("create：注册表缺该引擎类型 → 报错")
    void createFailsWhenEngineTypeNotRegistered() {
        EngineRegistry registry = new EngineRegistry(null, null);
        RetrieveEngineException e = assertThrows(RetrieveEngineException.class,
                () -> CompositeRetrieveEngine.create(registry,
                        List.of(new RetrieverEngineParams("vector", "elasticsearch"))));
        assertTrue(e.getMessage().contains("not found"), e.getMessage());
    }

    @Test
    @DisplayName("create：引擎不支持该检索类型 → 报错")
    void createFailsWhenRetrieverTypeUnsupported() {
        EngineRegistry registry = new EngineRegistry(null, null);
        registry.register(new FakeEngineService("elasticsearch", "keywords"));
        RetrieveEngineException e = assertThrows(RetrieveEngineException.class,
                () -> CompositeRetrieveEngine.create(registry,
                        List.of(new RetrieverEngineParams("vector", "elasticsearch"))));
        assertTrue(e.getMessage().contains("does not support retriever type"), e.getMessage());
    }

    @Test
    @DisplayName("create：同一引擎类型多条参数 → 检索类型并成一条")
    void createMergesRetrieverTypesPerEngine() {
        EngineRegistry registry = new EngineRegistry(null, null);
        registry.register(new FakeEngineService("elasticsearch", "keywords", "vector"));
        CompositeRetrieveEngine engine = CompositeRetrieveEngine.create(registry, List.of(
                new RetrieverEngineParams("keywords", "elasticsearch"),
                new RetrieverEngineParams("vector", "elasticsearch")));

        assertEquals(1, engine.engineCount());
        assertEquals(List.of("keywords", "vector"), engine.retrieverTypesAt(0));
        assertTrue(engine.supportRetriever("vector"));
        assertFalse(engine.supportRetriever("rerank"));
    }

    @Test
    @DisplayName("retrieve：按检索类型分派；无人承载时报 retriever type not found")
    void retrieveDispatchesByRetrieverType() throws Exception {
        FakeEngineService pg = new FakeEngineService("postgres", "keywords", "vector");
        pg.retrieveResult = List.of(new RetrieveResult(List.of(), "postgres", "vector"));
        CompositeRetrieveEngine engine = composite(pg);

        List<RetrieveResult> out = engine.retrieve(List.of(retrieveParams("vector")));
        assertEquals(1, out.size());
        assertEquals(List.of("retrieve:vector"), pg.calls);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> engine.retrieve(List.of(retrieveParams("rerank"))));
        assertEquals("retriever type rerank not found", e.getMessage());
    }

    @Test
    @DisplayName("retrieve：多引擎各自分派，结果按入参序拼接")
    void retrieveAcrossEngines() throws Exception {
        FakeEngineService pg = new FakeEngineService("postgres", "keywords");
        pg.retrieveResult = List.of(new RetrieveResult(List.of(), "postgres", "keywords"));
        FakeEngineService es = new FakeEngineService("elasticsearch", "vector");
        es.retrieveResult = List.of(new RetrieveResult(List.of(), "elasticsearch", "vector"));
        CompositeRetrieveEngine engine = composite(pg, es);

        List<RetrieveResult> out = engine.retrieve(
                List.of(retrieveParams("vector"), retrieveParams("keywords")));

        assertEquals(2, out.size());
        // 入参序（vector 在前）而非完成序——本仓的确定性备案
        assertEquals("vector", out.get(0).retrieverType());
        assertEquals("keywords", out.get(1).retrieverType());
    }

    @Test
    @DisplayName("写入/删除/复制/标签：扇出到全部引擎")
    void fanOutOperations() throws Exception {
        FakeEngineService a = new FakeEngineService("postgres", "vector");
        FakeEngineService b = new FakeEngineService("elasticsearch", "vector");
        CompositeRetrieveEngine engine = composite(a, b);

        IndexInfo info = RetrievalEngineTestSupport.indexInfo("i1", "s1", "content");
        engine.index(null, info);
        engine.deleteByChunkIdList(List.of("c1"), 3, "file");
        engine.deleteBySourceIdList(List.of("s1"), 3, "file");
        engine.deleteByKnowledgeIdList(List.of("k1"), 3, "file");
        engine.copyIndices("kb1", Map.of(), Map.of(), "kb2", 3, "file");
        engine.batchUpdateChunkEnabledStatus(Map.of("c1", true));
        engine.batchUpdateChunkTagID(Map.of("c1", "t1"));

        for (FakeEngineService svc : List.of(a, b)) {
            assertTrue(svc.calls.contains("index"), svc.engineType());
            assertTrue(svc.calls.contains("copyIndices"), svc.engineType());
            assertTrue(svc.calls.contains("deleteByChunkIdList"), svc.engineType());
            assertTrue(svc.calls.contains("deleteBySourceIdList"), svc.engineType());
            assertTrue(svc.calls.contains("deleteByKnowledgeIdList"), svc.engineType());
            assertTrue(svc.calls.contains("batchUpdateChunkEnabledStatus"), svc.engineType());
            assertTrue(svc.calls.contains("batchUpdateChunkTagID"), svc.engineType());
        }
    }

    @Test
    @DisplayName("batchIndex：按 SourceID 去重（保首次出现）后扇出")
    void batchIndexDedupesBySourceId() throws Exception {
        FakeEngineService a = new FakeEngineService("postgres", "vector");
        CompositeRetrieveEngine engine = composite(a);

        engine.batchIndex(null, List.of(
                RetrievalEngineTestSupport.indexInfo("i1", "s1", "a"),
                RetrievalEngineTestSupport.indexInfo("i2", "s2", "b"),
                RetrievalEngineTestSupport.indexInfo("i3", "s1", "c")));

        assertEquals(List.of("s1", "s2"), RetrievalEngineTestSupport.sourceIds(a.batchPayload));
    }

    @Test
    @DisplayName("扇出中任一引擎失败 → 首个错误上抛")
    void firstErrorPropagates() {
        FakeEngineService ok = new FakeEngineService("postgres", "vector");
        FakeEngineService bad = new FakeEngineService("elasticsearch", "vector");
        bad.failureOn = "index";
        bad.failure = new IllegalStateException("es is down");
        CompositeRetrieveEngine engine = composite(ok, bad);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> engine.index(null, RetrievalEngineTestSupport.indexInfo("i1", "s1", "x")));
        assertEquals("es is down", e.getMessage());
    }

    @Test
    @DisplayName("estimateStorageSize：逐引擎求和")
    void estimateSumsAcrossEngines() {
        FakeEngineService a = new FakeEngineService("postgres", "vector");
        a.estimateValue = 10L;
        FakeEngineService b = new FakeEngineService("elasticsearch", "vector");
        b.estimateValue = 32L;
        assertEquals(42L, composite(a, b).estimateStorageSize(null, List.of()));

        FakeEngineService solo = new FakeEngineService("qdrant", "vector");
        solo.estimateValue = 8L;
        assertEquals(8L, composite(solo).estimateStorageSize(null, List.of()));
    }

    @Test
    @DisplayName("迁移：非迁移引擎 → 整体预检失败")
    void validateRejectsNonMover() {
        CompositeRetrieveEngine engine = composite(new FakeEngineService("postgres", "vector"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                engine::validateKnowledgeIndexMove);
        assertEquals("retriever postgres does not support moving indices", e.getMessage());
    }

    @Test
    @DisplayName("迁移：校验器失败即止；通过则扇出执行")
    void moveValidatesThenFansOut() throws Exception {
        MoverFakeEngineService a = new MoverFakeEngineService("elasticsearch", "vector");
        CompositeRetrieveEngine engine = composite(a);

        engine.validateKnowledgeIndexMove();
        assertTrue(a.calls.contains("validateKnowledgeIndexMove"));

        engine.moveKnowledgeIndices("kb1", "kb2", "k1", List.of("c1"), 3, "file");
        assertTrue(a.calls.contains("moveKnowledgeIndices"));

        MoverFakeEngineService bad = new MoverFakeEngineService("elasticsearch", "vector");
        bad.validateFailure = new IllegalStateException("move requires refresh=true");
        CompositeRetrieveEngine broken = composite(bad);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> broken.moveKnowledgeIndices("kb1", "kb2", "k1", List.of("c1"), 3, "file"));
        assertEquals("move requires refresh=true", e.getMessage());
        assertFalse(bad.calls.contains("moveKnowledgeIndices"), "预检失败不得开始迁移");
    }

    @Test
    @DisplayName("工厂路径：单引擎合成保留该店全部检索类型")
    void ofSingleUsesServiceSupport() {
        FakeEngineService es = new FakeEngineService("elasticsearch", "keywords", "vector");
        CompositeRetrieveEngine engine = CompositeRetrieveEngine.ofSingle(es);
        assertEquals(1, engine.engineCount());
        assertSame(es, engine.engineAt(0));
        assertEquals(List.of("keywords", "vector"), engine.retrieverTypesAt(0));
    }
}
