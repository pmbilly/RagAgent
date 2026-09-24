package com.ragagent.retrieval.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.auth.domain.Tenant;

/**
 * 有效引擎解析——对照 Go {@code types.Tenant.GetEffectiveEngines}（tenant.go L137-142）
 * + {@code GetDefaultRetrieverEngines}（L66-83）+ {@code retrieverEngineMapping}（L17-60）。
 *
 * <p>本类是把原先内嵌在 {@code HybridSearchService} 里的同一段逻辑抽出来共享：
 * 接线批的工厂（{@code CreateRetrieveEngineForKB} 的 env-store 分支）与
 * HybridSearch 的引擎路由要用<b>同一份</b>映射表与派发规则，抽共享件比两处各抄一份安全。</p>
 *
 * <p><b>RETRIEVE_DRIVER 未配置 → 空集 → 检索全关</b>（"No retrievable indexing pipelines"），
 * 这是本部署的 Go 实测行为。</p>
 */
public final class EffectiveEngines {

    private EffectiveEngines() {
    }

    /** 对照 {@code retrieverEngineMapping}（types/tenant.go L17-60，逐条照抄）。 */
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
     * 对照 {@code Tenant.GetEffectiveEngines}：租户显式配置优先，否则按
     * {@code RETRIEVE_DRIVER} 派生默认。
     */
    public static List<RetrieverEngineParams> of(Tenant tenant) {
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
        return defaults();
    }

    /** 对照 {@code GetDefaultRetrieverEngines}：按 RETRIEVE_DRIVER 逐段映射并去重。 */
    public static List<RetrieverEngineParams> defaults() {
        List<RetrieverEngineParams> out = new ArrayList<>();
        String driver = System.getenv("RETRIEVE_DRIVER");
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

    /** 对照 {@code CompositeRetrieveEngine.SupportRetriever} 用到的那半：按检索类型判定。 */
    public static boolean supportsRetriever(List<RetrieverEngineParams> engines,
                                            String retrieverType) {
        return engines != null && engines.stream()
                .anyMatch(e -> e.retrieverType().equals(retrieverType));
    }
}
