package com.ragagent.retrieval.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.auth.domain.Tenant;

/**
 * 有效引擎解析——租户显式配置与 RETRIEVE_DRIVER 派生默认共用的映射表与派发规则。
 *
 * <p>工厂（{@code RetrieveEngineFactories} 的 env-store 分支）与
 * HybridSearch 的引擎路由共用本类，保证两边用<b>同一份</b>映射表与派发规则。</p>
 *
 * <p><b>RETRIEVE_DRIVER 未配置 → 空集 → 检索全关</b>（"No retrievable indexing pipelines"）。</p>
 */
public final class EffectiveEngines {

    private EffectiveEngines() {
    }

    /** 检索驱动 → 引擎参数的映射表（每驱动列出其支持的检索类型）。 */
    private static final Map<String, List<RetrieverEngineParams>> RETRIEVER_ENGINE_MAPPING =
            buildEngineMapping();

    private static Map<String, List<RetrieverEngineParams>> buildEngineMapping() {
        Map<String, List<RetrieverEngineParams>> m = new LinkedHashMap<>();
        m.put("postgres", List.of(
                new RetrieverEngineParams("keywords", EngineTypes.ENGINE_POSTGRES),
                new RetrieverEngineParams("vector", EngineTypes.ENGINE_POSTGRES)));
        m.put("elasticsearch_v7", List.of(
                new RetrieverEngineParams("keywords", EngineTypes.ENGINE_ELASTICSEARCH)));
        m.put("elasticsearch_v8", List.of(
                new RetrieverEngineParams("keywords", EngineTypes.ENGINE_ELASTICSEARCH),
                new RetrieverEngineParams("vector", EngineTypes.ENGINE_ELASTICSEARCH)));
        m.put("qdrant", List.of(
                new RetrieverEngineParams("keywords", EngineTypes.ENGINE_QDRANT),
                new RetrieverEngineParams("vector", EngineTypes.ENGINE_QDRANT)));
        m.put("milvus", List.of(
                new RetrieverEngineParams("vector", EngineTypes.ENGINE_MILVUS),
                new RetrieverEngineParams("keywords", EngineTypes.ENGINE_MILVUS)));
        m.put("weaviate", List.of(
                new RetrieverEngineParams("keywords", EngineTypes.ENGINE_WEAVIATE),
                new RetrieverEngineParams("vector", EngineTypes.ENGINE_WEAVIATE)));
        m.put("doris", List.of(
                new RetrieverEngineParams("keywords", EngineTypes.ENGINE_DORIS),
                new RetrieverEngineParams("vector", EngineTypes.ENGINE_DORIS)));
        m.put("sqlite", List.of(
                new RetrieverEngineParams("keywords", EngineTypes.ENGINE_SQLITE),
                new RetrieverEngineParams("vector", EngineTypes.ENGINE_SQLITE)));
        m.put("tencent_vectordb", List.of(
                new RetrieverEngineParams("keywords", EngineTypes.ENGINE_TENCENT_VECTORDB),
                new RetrieverEngineParams("vector", EngineTypes.ENGINE_TENCENT_VECTORDB)));
        m.put("opensearch", List.of(
                new RetrieverEngineParams("keywords", EngineTypes.ENGINE_OPENSEARCH),
                new RetrieverEngineParams("vector", EngineTypes.ENGINE_OPENSEARCH)));
        return m;
    }

    /**
     * 租户显式配置优先，否则按
     * {@code RETRIEVE_DRIVER} 派生默认（驱动串由调用方注入，不读进程环境）。
     */
    public static List<RetrieverEngineParams> of(Tenant tenant, String retrieveDriver) {
        if (tenant != null && tenant.getRetrieverEngines() != null
                && tenant.getRetrieverEngines().has("engines")
                && tenant.getRetrieverEngines().get("engines").isArray()
                && !tenant.getRetrieverEngines().get("engines").isEmpty()) {
            List<RetrieverEngineParams> out = new ArrayList<>();
            for (JsonNode n : tenant.getRetrieverEngines().get("engines")) {
                out.add(new RetrieverEngineParams(
                        n.path("retriever_type").asText(""),
                        n.path("retriever_engine_type").asText("")));
            }
            return out;
        }
        return defaults(retrieveDriver);
    }

    /** 按 RETRIEVE_DRIVER 逐段映射并去重。 */
    public static List<RetrieverEngineParams> defaults(String driver) {
        List<RetrieverEngineParams> out = new ArrayList<>();
        if (driver == null || driver.isBlank()) {
            return out;
        }
        for (String d : driver.split(",")) {
            List<RetrieverEngineParams> params = RETRIEVER_ENGINE_MAPPING.get(d.strip());
            if (params != null) {
                for (RetrieverEngineParams p : params) {
                    boolean seen = out.stream().anyMatch(e ->
                            e.retrieverType().equals(p.retrieverType())
                                    && e.retrieverEngineType().equals(p.retrieverEngineType()));
                    if (!seen) {
                        out.add(p);
                    }
                }
            }
        }
        return out;
    }

    /** {@link CompositeRetrieveEngine} 用到的那半：按检索类型判定。 */
    public static boolean supportsRetriever(List<RetrieverEngineParams> engines,
                                            String retrieverType) {
        return engines != null && engines.stream()
                .anyMatch(e -> e.retrieverType().equals(retrieverType));
    }
}
