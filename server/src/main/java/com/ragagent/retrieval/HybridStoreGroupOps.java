package com.ragagent.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.error.ErrorCode;
import com.ragagent.common.knowledge.KnowledgeBaseSearchFacts;
import com.ragagent.common.pipeline.SearchParams;
import com.ragagent.retrieval.engine.CompositeRetrieveEngine;
import com.ragagent.retrieval.engine.EffectiveEngines;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.RetrieveEngineException;
import com.ragagent.retrieval.engine.RetrieveEngineFactories;
import com.ragagent.retrieval.engine.RetrieverEngineParams;
import com.ragagent.retrieval.engine.TenantStoreOwnership;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ragagent.retrieval.HybridSearchService.StoreGroup;

/**
 * HybridSearch 的 store-group 解析簇：KB 按 (vectorStoreId, 属主租户) 分桶、逐组经工厂
 * 解析复合引擎并构建基础检索参数；工厂哨兵映射 2200/2201（store UUID 不泄漏给用户）。
 */
final class HybridStoreGroupOps {

    private static final Logger log = LoggerFactory.getLogger(HybridStoreGroupOps.class);

    private final HybridSearchService service;

    private final TenantStoreOwnership storeOwnership;

    /** RETRIEVE_DRIVER 原始串（B6 批 3 起由调用方注入，本类不读进程环境）。 */
    private final String retrieveDriver;

    HybridStoreGroupOps(HybridSearchService service, TenantStoreOwnership storeOwnership,
            String retrieveDriver) {
        this.service = service;
        this.storeOwnership = storeOwnership;
        this.retrieveDriver = retrieveDriver;
    }

    /**
     * KB 按 (vectorStoreId, kb.tenantId) 分桶，逐组经工厂解析复合引擎并构建
     * 基础检索参数。桶序 = KB 首见序（Go 为 map 随机序，本仓确定性备案）。
     */
    List<StoreGroup> resolveStoreGroups(KnowledgeBaseSearchFacts primary, List<KnowledgeBaseSearchFacts> kbs,
                                                SearchParams params, int matchCount) {
        Map<String, List<KnowledgeBaseSearchFacts>> buckets = new LinkedHashMap<>();
        for (KnowledgeBaseSearchFacts kb : kbs) {
            String sid = kb.vectorStoreId() == null ? "" : kb.vectorStoreId();
            long tid = kb.tenantId() == null ? 0L : kb.tenantId();
            buckets.computeIfAbsent(sid + ":" + tid, key -> new ArrayList<>()).add(kb);
        }

        // env-store 组的租户有效引擎（Go 从 ctx 的 TenantInfo 取——按当前租户行解析，
        // 引擎列表为空 = RETRIEVE_DRIVER 未配置 = 检索全关，Go 实测行为）。
        List<RetrieverEngineParams> tenantEngines =
                EffectiveEngines.of(service.currentTenant(), retrieveDriver);

        List<StoreGroup> groups = new ArrayList<>(buckets.size());
        for (List<KnowledgeBaseSearchFacts> groupKbs : buckets.values()) {
            KnowledgeBaseSearchFacts first = groupKbs.get(0);
            String storeId = first.vectorStoreId() == null ? "" : first.vectorStoreId();
            long ownerTenantId = first.tenantId() == null ? 0L : first.tenantId();
            CompositeRetrieveEngine engine;
            try {
                engine = RetrieveEngineFactories.createForKb(service.engineRegistry, storeOwnership,
                        ownerTenantId, storeId, tenantEngines);
            } catch (RuntimeException e) {
                throw classifyFactoryError(e, ownerTenantId, storeId);
            }
            List<RetrieveParams> baseParams =
                    service.buildRetrievalParams(engine, primary, groupKbs, params, matchCount);
            List<String> ids = new ArrayList<>(groupKbs.size());
            for (KnowledgeBaseSearchFacts kb : groupKbs) {
                ids.add(kb.id());
            }
            groups.add(new StoreGroup(storeId, ownerTenantId, ids, engine, baseParams, matchCount));
        }
        return groups;
    }

    /**
     * 对照 classifyFactoryError（storegroup.go L165-190）：工厂哨兵 → 2200/2201 的
     * AppError，不向用户泄漏 store UUID（UUID 只进结构化日志，经 sanitizer）。
     */
    static BizException classifyFactoryError(RuntimeException err, long tenantId,
                                                     String storeId) {
        log.warn("resolve store engine failed: tenant_id={} store_id={} reason={} err={}",
                tenantId, com.ragagent.common.security.LogSanitizer.sanitize(storeId),
                "resolve store engine", err.toString());
        if (err instanceof RetrieveEngineException e) {
            switch (e.kind()) {
                case VECTOR_STORE_FORBIDDEN:
                    return new BizException(new AppError(
                            ErrorCode.VECTOR_STORE_BINDING_INVALID.value(),
                            "vector store bound to the knowledge base is not available", null, 400));
                case VECTOR_STORE_NOT_FOUND:
                case VECTOR_STORE_UNAVAILABLE:
                    return vectorStoreUnavailable();
                case TENANT_INFO_MISSING:
                    return new BizException(new AppError(
                            ErrorCode.VECTOR_STORE_BINDING_INVALID.value(),
                            "tenant information missing in context", null, 400));
                default:
                    break;
            }
        }
        // 解析超时也报 2201（绑定没问题，重试可能成功）——照 Go 的
        // DeadlineExceeded 分支；其余错误原样上抛（handler 折 500 原文）。
        if (RetrieveEngineException.isCancellation(err)) {
            return vectorStoreUnavailable();
        }
        if (err instanceof BizException biz) {
            return biz;
        }
        return new BizException(AppError.internal(
                err.getMessage() == null ? err.toString() : err.getMessage()));
    }


    static BizException vectorStoreUnavailable() {
        return new BizException(new AppError(ErrorCode.VECTOR_STORE_UNAVAILABLE.value(),
                "vector store is currently unavailable", null, 400));
    }
}
