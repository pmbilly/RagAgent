package com.ragagent.retrieval.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.auth.domain.Tenant;

/**
 * 有效引擎解析：
 *
 * <p>只验不依赖环境的那些分支：租户显式配置优先；无配置时回落到
 * {@code RETRIEVE_DRIVER} 派生的默认（未设该环境变量即空集——本部署的实测行为，
 * 用对照 {@code defaults()} 的方式断言以免测试与运行环境耦合）。</p>
 */
class EffectiveEnginesTest {

    /** 驱动串由调用方注入（读点不读进程环境）——测试用固定值。 */
    private static final String DRIVER = "postgres,elasticsearch";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("租户显式配置优先（数组非空即用，不看 RETRIEVE_DRIVER）")
    void tenantExplicitEnginesWin() {
        ObjectNode root = MAPPER.createObjectNode();
        var enginesNode = root.putArray("engines");
        ObjectNode first = enginesNode.addObject();
        first.put("retriever_type", "vector");
        first.put("retriever_engine_type", "elasticsearch");
        ObjectNode second = enginesNode.addObject();
        second.put("retriever_type", "keywords");
        second.put("retriever_engine_type", "elasticsearch");

        Tenant tenant = new Tenant();
        tenant.setRetrieverEngines(root);

        List<RetrieverEngineParams> engines = EffectiveEngines.of(tenant, DRIVER);
        assertEquals(2, engines.size());
        assertEquals(new RetrieverEngineParams("vector", "elasticsearch"), engines.get(0));
        assertEquals(new RetrieverEngineParams("keywords", "elasticsearch"), engines.get(1));
        assertTrue(EffectiveEngines.supportsRetriever(engines, "vector"));
        assertFalse(EffectiveEngines.supportsRetriever(engines, "rerank"));
    }

    @Test
    @DisplayName("engines 为空数组 → 回落 RETRIEVE_DRIVER 默认（与 defaults() 一致）")
    void emptyEnginesFallBackToDefaults() {
        ObjectNode root = MAPPER.createObjectNode();
        root.putArray("engines");
        Tenant tenant = new Tenant();
        tenant.setRetrieverEngines(root);

        assertEquals(EffectiveEngines.defaults(DRIVER), EffectiveEngines.of(tenant, DRIVER));
    }

    @Test
    @DisplayName("tenant 为 null / 无 retriever_engines → 同样回落默认")
    void missingTenantFallsBackToDefaults() {
        assertEquals(EffectiveEngines.defaults(DRIVER), EffectiveEngines.of(null, DRIVER));
        assertEquals(EffectiveEngines.defaults(DRIVER), EffectiveEngines.of(new Tenant(), DRIVER));
    }

    @Test
    @DisplayName("supportsRetriever 对 null 安全（无引擎即无能力）")
    void supportsRetrieverHandlesNull() {
        assertFalse(EffectiveEngines.supportsRetriever(null, "vector"));
        assertFalse(EffectiveEngines.supportsRetriever(List.of(), "vector"));
    }
}
