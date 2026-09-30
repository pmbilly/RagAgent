package com.ragagent.chatpipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.error.ErrorCode;
import com.ragagent.llm.extract.PipelineConfig;

/**
 * 回归（W5γ5.14，2026-09-25 E2E 抓回）：KB 检索的 <b>"硬错 vs 降级" 分级</b>。
 *
 * <p><b>场景来源</b>：dev PG 里租户 10002 的 `ks-golden-store` 仍绑定着**已被删除的 store**
 * （`vector_stores` 表 0 行）⇒ 任何把该 KB 纳入范围的检索都在引擎解析处失败（2200
 * "vector store bound to the knowledge base is not available"）⇒ 双端实测**都是硬错中止**
 * （`error`×2 帧、不做 fallback 作答），而**无错但 0 命中**时双端都降级作答
 * （Go `stage_fallback` + 固定/模型 fallback）。</p>
 *
 * <p>钉住的分级（对照 Go `chat_pipeline/search.go:127-133`、`search_parallel.go:115-130` 与
 * `:168-176`，两侧逐字一致）：</p>
 * <ol>
 *   <li><b>chunk 检索抛错 + 0 结果 ⇒ 该硬错上抛</b>（`search_failed`，**不**降级为 `search_nothing`）；</li>
 *   <li><b>无错 + 0 结果 ⇒ `SEARCH_NOTHING`</b>（降级分支）；</li>
 *   <li>任务级 `SEARCH_NOTHING` 被并行插件**吞成"无错"**，只有硬错才进 `errs`；</li>
 *   <li>两条路径都**不推进** `next`（后续管线由返回的错误类型决定）。</li>
 * </ol>
 */
class SearchGradingTest {

    /** 失效 store 绑定的症状：hybridSearch 在某处抛 2200（`HybridSearchService.classifyFactoryError` 的形状）。 */
    private static BizException staleStoreBindingError() {
        return new BizException(new AppError(
                ErrorCode.VECTOR_STORE_BINDING_INVALID.value(),
                "vector store bound to the knowledge base is not available", null, 400));
    }

    private static ChatManage chatManageWithKb(String sessionId, String kbId) {
        ChatManage cm = new ChatManage();
        cm.setSessionId(sessionId);
        cm.setRewriteQuery("向量数据库是什么");
        cm.setEmbeddingTopK(5);
        cm.setTenantId(1);
        cm.setSearchTargets(new ArrayList<>(List.of(
                new com.ragagent.agent.tools.SearchTarget("knowledge_base", kbId, 1, null, null, null, false))));
        return cm;
    }

    private static PluginSearchParallel parallel(Rec46cSupport.StubKBService kbSvc) {
        return new PluginSearchParallel(new EventManager(), kbSvc,
                new Rec46cSupport.StubKnowledgeService(), new Rec46cSupport.StubChunkService(),
                new PipelineConfig(), null, null, null, null, null,
                new Rec46cSupport.StubGraphRepo(), new Rec46cSupport.StubChunkRepo(),
                new Rec46cSupport.StubKnowledgeRepo());
    }

    @Test
    void staleStoreBindingIsHardErrorNotDegrade() {
        Rec46cSupport.StubKBService kbSvc = new Rec46cSupport.StubKBService();
        kbSvc.kbs.put("kb-1", Rec46cSupport.kb("kb-1", "document", true, true, false));
        kbSvc.hybridErr.put("kb-1", staleStoreBindingError());

        // ① 单插件：SEARCH.withError，且文案带 2200 前缀（供 SSE 终止帧使用）
        PluginSearch p = new PluginSearch(kbSvc, null, null, null, null, null, null, null, null);
        boolean[] next = {false};
        PluginError err = p.onEvent(PipelineEventType.CHUNK_SEARCH,
                chatManageWithKb("grading-hard-1", "kb-1"), () -> {
                    next[0] = true;
                    return null;
                });
        assertNotSame(PluginError.SEARCH_NOTHING, err, "硬错不得被降级成 search_nothing");
        assertEquals("search_failed", err.errorType);
        assertNotNull(err.err, "原始错误须带出（SSE 文案来源）");
        assertTrue(err.err.getMessage().contains("error code: 2200"),
                "文案应保留 AppError 前缀，实际：" + err.err.getMessage());
        assertFalse(next[0], "检索硬错时不得推进后续管线");

        // ② 并行阶段：0 结果 + chunk 硬错 ⇒ 上抛该硬错（不降级）
        PluginError perr = parallel(kbSvc).onEvent(PipelineEventType.CHUNK_SEARCH_PARALLEL,
                chatManageWithKb("grading-hard-2", "kb-1"), () -> null);
        assertNotSame(PluginError.SEARCH_NOTHING, perr, "并行聚合不得把硬错吞成降级");
        assertEquals("search_failed", perr.errorType);
    }

    @Test
    void noErrorNoResultsDegradesToSearchNothing() {
        Rec46cSupport.StubKBService kbSvc = new Rec46cSupport.StubKBService();
        kbSvc.kbs.put("kb-2", Rec46cSupport.kb("kb-2", "document", true, true, false));
        // 不注入任何错误：hybrid 未配置 ⇒ 空命中

        PluginSearch p = new PluginSearch(kbSvc, null, null, null, null, null, null, null, null);
        boolean[] next = {false};
        PluginError err = p.onEvent(PipelineEventType.CHUNK_SEARCH,
                chatManageWithKb("grading-degrade-1", "kb-2"), () -> {
                    next[0] = true;
                    return null;
                });
        assertSame(PluginError.SEARCH_NOTHING, err, "无错 + 0 命中须降级为 search_nothing");
        assertFalse(next[0]);

        // 任务级 SEARCH_NOTHING 被吞成"无错" ⇒ 并行阶段同样降级（而不是当成硬错上抛）
        PluginError perr = parallel(kbSvc).onEvent(PipelineEventType.CHUNK_SEARCH_PARALLEL,
                chatManageWithKb("grading-degrade-2", "kb-2"), () -> null);
        assertSame(PluginError.SEARCH_NOTHING, perr, "任务级 search_nothing 不得被当作硬错");
    }
}
