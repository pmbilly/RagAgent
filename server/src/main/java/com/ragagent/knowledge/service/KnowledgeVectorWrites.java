package com.ragagent.knowledge.service;

import com.ragagent.common.context.TenantContext;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.retrieval.engine.CompositeRetrieveEngine;
import com.ragagent.retrieval.engine.RetrieveEngineFactories;
import com.ragagent.retrieval.engine.RetrieveEngineRegistry;
import com.ragagent.retrieval.engine.TenantStoreOwnership;
import org.springframework.stereotype.Component;

/**
 * 知识写链的向量店路由网关（W5γ4.6 follow-up：知识写链引擎改道）。
 * knowledge_delete.go / knowledge_faq.go / knowledge_clone_move.go）都经
 * {@code retriever.CreateRetrieveEngineForKB} 路由——绑定外部 store 的 KB 写进自己的店。
 * Java 侧历史路径直连 pg JDBC（{@code VectorStoreService}），绑定店写落错店。</p>
 * <p><b>改道策略（风险最小切分）</b>：绑定 store（{@code hasVectorStore()}）的 KB 走引擎口
 * （本网关解析）；未绑定的 KB 保持既有 pg 直连路径不变——golden 锁定的错误形态
 * （kg-image 族、ChunkServiceTest 桩）全部在未绑定路径上，行为逐字节不变。
 * no-op（无引擎），向量行属残留数据，差异无观测面（备案）。</p>
 */
@Component
public class KnowledgeVectorWrites {

    private final RetrieveEngineRegistry retrieveEngineRegistry;
    private final TenantStoreOwnership storeOwnership;

    public KnowledgeVectorWrites(RetrieveEngineRegistry retrieveEngineRegistry,
                                 TenantStoreOwnership storeOwnership) {
        this.retrieveEngineRegistry = retrieveEngineRegistry;
        this.storeOwnership = storeOwnership;
    }

    /**
     * 绑定 store 的 KB → 复合引擎；未绑定 → {@code null}（调用方保持 pg 直连路径）。
     * 解析失败（跨租户 FORBIDDEN / 店不可用 UNAVAILABLE / 店未注册 NOT_FOUND）原样抛
     * {@link com.ragagent.retrieval.engine.RetrieveEngineException}——调用方按各自的
     */
    public CompositeRetrieveEngine boundEngine(KnowledgeBase kb) {
        if (kb == null || !kb.hasVectorStore()) {
            return null;
        }
        Long tid = TenantContext.currentTenantId();
        long tenantId = tid == null ? 0L : tid;
        // storeId 非空时 createForKb 走 resolveBoundEngine（tenantEngines 不参与）
        return RetrieveEngineFactories.createForKb(retrieveEngineRegistry, storeOwnership,
                tenantId, kb.getVectorStoreId(), null);
    }
}
