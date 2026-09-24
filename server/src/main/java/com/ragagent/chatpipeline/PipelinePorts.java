package com.ragagent.chatpipeline;

import java.util.List;
import java.util.Map;

import com.ragagent.llm.LlmChatClient;
import com.ragagent.memory.service.MemoryRecall;
import com.ragagent.memory.service.MemoryRetrievalContext;
import com.ragagent.retrieval.domain.SearchResult;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.MessageImage;
import com.ragagent.retrieval.domain.WebSearchResult;
import com.ragagent.websearch.service.WebSearchService;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * chat 管线消费面的窄 seam 接口集合（对照 Go {@code types/interfaces} 的对应接口，
 * 只收 chat_pipeline 实际调用的方法子集——波 4.5b/c 先例）。
 *
 * <h2>4.6d 装配要求</h2>
 * <p>每个接口需要一个 adapter（纯新增文件，不改既有 service）把现有 Java service
 * 适配进来：</p>
 * <ul>
 *   <li>{@link ModelService} → KnowledgeService/model 侧的模型工厂（GetChatModel/GetRerankModel）。</li>
 *   <li>{@link KnowledgeBaseService} → knowledge 域 HybridSearch 执行面（波 4.4 起的地基 +
 *       阶段 7 的向量/关键词执行）；GetQueryEmbedding / ResolveEmbeddingModelKeys /
 *       GetKnowledgeBase(s)ById(s)Only。</li>
 *   <li>{@link ChunkRepository} / {@link KnowledgeRepository} / {@link KnowledgeBaseRepository}
 *       → knowledge.mapper.ChunkRepository / KnowledgeService / KnowledgeBaseMapper。</li>
 *   <li>{@link MessageService} → session.service.MessageService（getImage/RenderedContent 更新
 *       方法需在该类补方法或 adapter 内直写 mapper）。</li>
 *   <li>{@link MemoryService} → memory.service.MemoryService（recall/retrievalContextFor/
 *       documentAffinity 签名已对齐）。</li>
 *   <li>{@link WebSearchService} → websearch.service.WebSearchService.search。</li>
 *   <li>{@link RetrieveGraphRepository} → 图检索仓储（neo4j/图库面，尚未翻译）。</li>
 *   <li>{@link TenantService} / {@link SessionService} / {@link WebSearchStateService} /
 *       {@link WebSearchProviderRepository}：占位接口——Go 侧同样只判 nil / 从不读方法
 *       （search.go 的 webSearchStateService/webSearchProviderRepo 同）。</li>
 * </ul>
 *
 * <p>错误通道：Go 的 {@code (value, error)} 折叠为「返回值或抛 {@link PipelinePortException}」；
 * 与 4.5b/c 的接口 seam 一致。</p>
 */
public final class PipelinePorts {

    private PipelinePorts() {}

    /** seam 调用失败通道（对照 Go 接口方法的 error 返回值）。 */
    public static final class PipelinePortException extends RuntimeException {
        public PipelinePortException(String message) { super(message); }
        public PipelinePortException(String message, Throwable cause) { super(message, cause); }
    }

    /** 对照 interfaces.ModelService 的 chat_pipeline 子集。 */
    public interface ModelService {
        /** 对照 GetChatModel。 */
        LlmChatClient getChatModel(String modelId);
        /** 对照 GetRerankModel。 */
        com.ragagent.rerank.Reranker getRerankModel(String modelId);
    }

    /** 对照 interfaces.KnowledgeBaseService 的 chat_pipeline 子集。 */
    public interface KnowledgeBaseService {
        /** 对照 GetKnowledgeBaseByIDOnly（无租户过滤）。 */
        com.ragagent.knowledge.domain.KnowledgeBase getKnowledgeBaseByIdOnly(String id);

        /** 对照 GetKnowledgeBasesByIDsOnly（批量、无租户过滤；缺失 ID 跳过）。 */
        List<com.ragagent.knowledge.domain.KnowledgeBase> getKnowledgeBasesByIdsOnly(List<String> ids);

        /** 对照 HybridSearch。 */
        List<SearchResult> hybridSearch(String knowledgeBaseId, SearchParams params);

        /** 对照 GetQueryEmbedding。 */
        float[] getQueryEmbedding(String kbId, String queryText);

        /** 对照 ResolveEmbeddingModelKeys：KB ID → "模型名|endpoint"（解析失败缺键）。 */
        Map<String, String> resolveEmbeddingModelKeys(List<com.ragagent.knowledge.domain.KnowledgeBase> kbs);
    }

    /** 对照 interfaces.KnowledgeService 的 chat_pipeline 子集。 */
    public interface KnowledgeService {
        /** 对照 GetKnowledgeByID（ctx 租户过滤——Java 侧 adapter 从 TenantContext 取）。 */
        com.ragagent.knowledge.domain.Knowledge getKnowledgeById(String id);

        /** 对照 GetKnowledgeBatch。 */
        List<com.ragagent.knowledge.domain.Knowledge> getKnowledgeBatch(long tenantId, List<String> ids);

        /** 对照 GetKnowledgeBatchWithSharedAccess。 */
        List<com.ragagent.knowledge.domain.Knowledge> getKnowledgeBatchWithSharedAccess(long tenantId, List<String> ids);
    }

    /** 对照 interfaces.ChunkRepository 的 chat_pipeline 子集。 */
    public interface ChunkRepository {
        /** 对照 ListChunksByID。 */
        List<com.ragagent.knowledge.domain.Chunk> listChunksById(long tenantId, List<String> ids);

        /** 对照 ListChunksByParentIDs（image_info 聚合用）。 */
        List<com.ragagent.knowledge.domain.Chunk> listChunksByParentIds(long tenantId, List<String> parentIds);
    }

    /** 对照 interfaces.KnowledgeRepository 的 GetKnowledgeBatch。 */
    public interface KnowledgeRepository {
        List<com.ragagent.knowledge.domain.Knowledge> getKnowledgeBatch(long tenantId, List<String> ids);
    }

    /** 对照 interfaces.KnowledgeBaseRepository 的 GetKnowledgeBaseByIDs。 */
    public interface KnowledgeBaseRepository {
        List<com.ragagent.knowledge.domain.KnowledgeBase> getKnowledgeBaseByIDs(List<String> ids);
    }

    /** 对照 interfaces.MessageService 的 chat_pipeline 子集。 */
    public interface MessageService {
        /** 对照 GetMessage。找不到返回 null（Go (nil, nil)）或抛异常。 */
        Message getMessage(String sessionId, String messageId);

        /** 对照 GetRecentMessagesBySession。 */
        List<Message> getRecentMessagesBySession(String sessionId, int limit);

        /** 对照 UpdateMessageImages。 */
        void updateMessageImages(String sessionId, String messageId, List<MessageImage> images);

        /** 对照 UpdateMessageRenderedContent。 */
        void updateMessageRenderedContent(String sessionId, String messageId, String renderedContent);
    }

    /** 对照 interfaces.MemoryService 的 chat_pipeline 子集（签名与 memory.service.MemoryService 已对齐）。 */
    public interface MemoryService {
        MemoryRecall recall(String query);

        MemoryRetrievalContext retrievalContextFor();

        Map<String, Integer> documentAffinity(List<String> knowledgeIds);
    }

    /** 对照 interfaces.WebSearchService 的 Search（providerID + 执行面配置）。 */
    public interface WebSearch {
        List<WebSearchResult> search(String providerId, WebSearchService.WebSearchConfig config, String query);
    }

    /**
     * 对照 interfaces.RetrieveGraphRepository（types/interfaces/retriever_graph.go 全文）：
     * 图库的写（AddGraph）/删（DelGraph）/读（SearchNode）。
     *
     * <p>D 批起由 {@code com.ragagent.retrieval.graph.Neo4jGraphRepository} 提供真实实现
     * （NEO4J_ENABLE=true 才建驱动；否则 driver 为 null，三个操作都告警并静默——照 Go 的
     * nil driver 分支）。流水线只用 {@link #searchNode}；写/删由抽取与清理链路调用。</p>
     */
    public interface RetrieveGraphRepository {
        /** 对照 AddGraph：把一批图写进仓库（driver 缺失 → 告警 + 静默返回）。 */
        void addGraph(ChatManage.NameSpace namespace, List<ChatManage.GraphData> graphs);

        /** 对照 DelGraph：按命名空间删除（driver 缺失 → 告警 + 静默返回）。 */
        void delGraph(List<ChatManage.NameSpace> namespaces);

        /** 对照 SearchNode：按节点名（CONTAINS）匹配并取回一跳子图。 */
        ChatManage.GraphData searchNode(ChatManage.NameSpace namespace, List<String> nodes);
    }

    // ----- 占位接口（Go 侧同样只判 nil 或从不读方法） -----

    /** 对照 interfaces.TenantService：search.go 只判 nil（web 检索启用性开关的装配凭证）。 */
    public interface TenantService {
    }

    /** 对照 interfaces.SessionService：PluginSearch 存而不读（与 Go 一致）。 */
    public interface SessionService {
    }

    /** 对照 interfaces.ChunkService：merge 的父子解析依赖占位（当前与 Go 一致未消费）。 */
    public interface ChunkService {
    }

    /** 对照 interfaces.WebSearchStateService：存而不读（Go 当前也是注释掉的状态压缩路径）。 */
    public interface WebSearchStateService {
    }

    /** 对照 interfaces.WebSearchProviderRepository：存而不读（同上）。 */
    public interface WebSearchProviderRepository {
    }

    /**
     * DataAnalysis 插件的工具会话 seam（对照 Go 直接构造 tools.NewDataAnalysisTool +
     * LoadFromKnowledge/Execute/Cleanup 三步）。实现侧（4.6d，可放 agent.tools 包内以
     * 触达包私有 loadFromKnowledge）打包 KnowledgeLoader/Materializer/AnalysisDuckDb
     * 三个 seam 与 *sql.DB 的对应物。
     */
    public interface DataAnalysisSessionFactory {
        DataAnalysisSession create(String sessionId);
    }

    /** 一次数据装载/执行/清理会话（对照 DataAnalysisTool 的插件可见面）。 */
    interface DataAnalysisSession {
        com.ragagent.agent.tools.DataAnalysisTool.TableSchema loadFromKnowledge(
                com.ragagent.agent.tools.DataAnalysisTool.KnowledgeData knowledge);

        com.ragagent.agent.domain.ToolResult execute(JsonNode args);

        void cleanup();
    }
}
