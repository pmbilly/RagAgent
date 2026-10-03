package com.ragagent.chatpipeline;

import java.util.List;
import java.util.Map;

import com.ragagent.llm.LlmChatClient;
import com.ragagent.memory.service.MemoryRecall;
import com.ragagent.memory.service.MemoryRetrievalContext;
import com.ragagent.common.retrieval.SearchResult;
import com.ragagent.common.session.PipelineMessageImageView;
import com.ragagent.common.session.PipelineMessageView;
import com.ragagent.retrieval.domain.WebSearchResult;
import com.ragagent.websearch.service.WebSearchService;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.retrieval.graph.RetrieveGraphRepository;
import com.ragagent.common.pipeline.SearchParams;

/**
 * chat 管线消费面的窄 seam 接口集合，只收管线实际调用的方法子集。
 *
 * <h2>装配要求</h2>
 * <p>每个接口需要一个 adapter（纯新增文件，不改既有 service）把现有 Java service
 * 适配进来：</p>
 * <ul>
 *   <li>{@link ModelService} → KnowledgeService/model 侧的模型工厂（getChatModel/getRerankModel）。</li>
 *   <li>{@link KnowledgeBaseService} → knowledge 域 HybridSearch 执行面（向量/关键词执行）；
 *       getQueryEmbedding / resolveEmbeddingModelKeys /
 *       getKnowledgeBase(s)ById(s)Only。</li>
 *   <li>{@link ChunkRepository} / {@link KnowledgeRepository} / {@link KnowledgeBaseRepository}
 *       → knowledge.mapper.ChunkRepository / KnowledgeService / KnowledgeBaseMapper。</li>
 *   <li>{@link MessageService} → session.service.MessageService（getImage/RenderedContent 更新
 *       方法需在该类补方法或 adapter 内直写 mapper）。</li>
 *   <li>{@link MemoryService} → memory.service.MemoryService（recall/retrievalContextFor/
 *       documentAffinity 签名已对齐）。</li>
 *   <li>{@link WebSearchService} → websearch.service.WebSearchService.search。</li>
 *   <li>{@link RetrieveGraphRepository} → 图检索仓储（neo4j/图库面）。</li>
 *   <li>{@link TenantService} / {@link SessionService} / {@link WebSearchStateService} /
 *       {@link WebSearchProviderRepository}：占位接口——管线只判空、从不调用其方法。</li>
 * </ul>
 *
 * <p>错误通道：失败一律抛 {@link PipelinePortException}，成功走返回值。</p>
 */
public final class PipelinePorts {

    private PipelinePorts() {}

    /** seam 调用失败通道。 */
    public static final class PipelinePortException extends RuntimeException {
        public PipelinePortException(String message) { super(message); }
        public PipelinePortException(String message, Throwable cause) { super(message, cause); }
    }

    /** 模型面：chat 管线所需的方法子集。 */
    public interface ModelService {
        LlmChatClient getChatModel(String modelId);
        com.ragagent.rerank.Reranker getRerankModel(String modelId);
    }

    /** 知识库面：chat 管线所需的方法子集。 */
    public interface KnowledgeBaseService {
        /** 按 ID 直取（无租户过滤）。 */
        com.ragagent.knowledge.domain.KnowledgeBase getKnowledgeBaseByIdOnly(String id);

        /** 批量直取（无租户过滤；缺失 ID 跳过）。 */
        List<com.ragagent.knowledge.domain.KnowledgeBase> getKnowledgeBasesByIdsOnly(List<String> ids);

        List<SearchResult> hybridSearch(String knowledgeBaseId, SearchParams params);

        float[] getQueryEmbedding(String kbId, String queryText);

        /** KB ID → "模型名|endpoint"（解析失败的 KB 缺键）。 */
        Map<String, String> resolveEmbeddingModelKeys(List<String> kbIds);
    }

    /** 知识面：chat 管线所需的方法子集。 */
    public interface KnowledgeService {
        /** 按租户过滤取单条（adapter 从 TenantContext 取租户）。 */
        com.ragagent.knowledge.domain.Knowledge getKnowledgeById(String id);

        List<com.ragagent.knowledge.domain.Knowledge> getKnowledgeBatch(long tenantId, List<String> ids);

        List<com.ragagent.knowledge.domain.Knowledge> getKnowledgeBatchWithSharedAccess(long tenantId, List<String> ids);
    }

    /** chunk 面：chat 管线所需的方法子集。 */
    public interface ChunkRepository {
        List<com.ragagent.knowledge.domain.Chunk> listChunksById(long tenantId, List<String> ids);

        /** image_info 聚合用。 */
        List<com.ragagent.knowledge.domain.Chunk> listChunksByParentIds(long tenantId, List<String> parentIds);
    }

    /** 知识批量取仓储。 */
    public interface KnowledgeRepository {
        List<com.ragagent.knowledge.domain.Knowledge> getKnowledgeBatch(long tenantId, List<String> ids);
    }

    /** 知识库批量取仓储。 */
    public interface KnowledgeBaseRepository {
        List<com.ragagent.knowledge.domain.KnowledgeBase> getKnowledgeBaseByIDs(List<String> ids);
    }

    /** 消息面：chat 管线所需的方法子集。 */
    public interface MessageService {
        /** 找不到返回 null 或抛异常。 */
        PipelineMessageView getMessage(String sessionId, String messageId);

        List<PipelineMessageView> getRecentMessagesBySession(String sessionId, int limit);

        void updateMessageImages(String sessionId, String messageId,
                List<PipelineMessageImageView> images);

        void updateMessageRenderedContent(String sessionId, String messageId, String renderedContent);
    }

    /** 记忆面端口（签名与 memory.service.MemoryService 对齐）。 */
    public interface MemoryService {
        MemoryRecall recall(String query);

        MemoryRetrievalContext retrievalContextFor();

        Map<String, Integer> documentAffinity(List<String> knowledgeIds);
    }

    /** web 搜索执行（providerID + 执行面配置）。 */
    public interface WebSearch {
        List<WebSearchResult> search(String providerId, WebSearchService.WebSearchConfig config, String query);
    }
    // ----- 占位接口（管线只判空、从不调用方法） -----

    /** 租户面端口——chat 管线取租户信息的最小子集。 */
    public interface TenantService {

        /**
         * 当前租户的 web 搜索配置（TenantContext 实时读取）；无租户上下文 → null
         * （调用方按空配置走缺省合并分支）。default null 保持未装配 port 时的恒空行为。
         */
        default com.ragagent.auth.domain.tenantconfig.WebSearchConfig currentWebSearchConfig() {
            return null;
        }
    }

    /** 占位：PluginSearch 存而不读。 */
    public interface SessionService {
    }

    /** 占位：merge 的父子解析依赖预留，当前未消费。 */
    public interface ChunkService {
    }

    /** 占位：存而不读（状态压缩路径未启用）。 */
    public interface WebSearchStateService {
    }

    /** 占位：存而不读。 */
    public interface WebSearchProviderRepository {
    }

    /**
     * DataAnalysis 插件的工具会话 seam（装载/执行/清理三步）。实现侧可放 agent.tools
     * 包内以触达包私有 loadFromKnowledge，打包 KnowledgeLoader/Materializer/AnalysisDuckDb
     * 三个 seam 与数据库连接句柄。
     */
    public interface DataAnalysisSessionFactory {
        DataAnalysisSession create(String sessionId);
    }

    /** 一次数据装载/执行/清理会话（DataAnalysisTool 的插件可见面）。 */
    public interface DataAnalysisSession {
        com.ragagent.agent.tools.data.DataAnalysisTool.TableSchema loadFromKnowledge(
                com.ragagent.agent.tools.data.DataAnalysisTool.KnowledgeData knowledge);

        com.ragagent.common.llm.ToolResult execute(JsonNode args);

        void cleanup();
    }
}
