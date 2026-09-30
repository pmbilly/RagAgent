package com.ragagent.session.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.tenantconfig.ChatHistoryConfig;
import com.ragagent.auth.domain.tenantconfig.RetrievalConfig;
import com.ragagent.auth.service.TenantService;
import com.ragagent.agent.tools.ThinkBlocks;
import com.ragagent.common.context.TenantContext;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.service.KnowledgeService;
import com.ragagent.model.service.ModelRuntimeFactory;
import com.ragagent.common.pipeline.SearchParams;
import com.ragagent.rerank.RankResult;
import com.ragagent.rerank.Reranker;
import com.ragagent.retrieval.HybridSearchService;
import com.ragagent.retrieval.domain.SearchResult;
import com.ragagent.session.domain.ChatHistoryKbStats;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.MessageArtifact;
import com.ragagent.session.domain.MessageAttachment;
import com.ragagent.session.domain.MessageImage;
import com.ragagent.session.domain.MessageSearchGroupItem;
import com.ragagent.session.domain.MessageSearchResult;
import com.ragagent.session.domain.MessageWithSession;
import com.ragagent.session.domain.SessionOwnerIds;
import com.ragagent.session.mapper.MessageRepository;
import com.ragagent.session.mapper.MessageSuggestionRepository;
import com.ragagent.session.mapper.SessionRepository;

/**
 * 消息服务（对照 Go {@code internal/application/service/message.go}）。
 *
 * <h2>波 1 G2 落地的部分</h2>
 * <ul>
 *   <li>读：{@code GetRecentMessagesBySession} / {@code GetMessagesBySessionBeforeTime}
 *       （LoadMessages 的两条路径）；</li>
 *   <li>删：{@code DeleteMessage} / {@code ClearSessionMessages}（含建议删除与
 *       聊天历史知识的尽力而为清理）；</li>
 *   <li>搜：{@code SearchMessages} 的关键词路径 + 向量路径（vectorSearchViaKB）+
 *       RRF 融合 + Q&amp;A 补对 + 按 request_id 分组；</li>
 *   <li>统计：{@code GetChatHistoryKBStats}。</li>
 * </ul>
 *
 * <h2>已知差异（对照 Go，均不改变 HTTP 契约）</h2>
 * <ol>
 *   <li><b>聊天历史知识清理是同步尽力而为</b>：Go 在 goroutine 里异步做
 *       （{@code context.WithoutCancel}），错误全吞。HTTP 响应不受影响。</li>
 *   <li><b>向量搜索已接线（2026-09-23 收口；检索引擎批提供 HybridSearch 执行面）</b>：
 *       租户配置了 ChatHistoryConfig 时走
 *       {@link HybridSearchService#hybridSearch}（vector-only：
 *       {@code DisableKeywordsMatch=true}，关键词仍在 messages 表上单独搜），
 *       KB 命中经 {@code knowledge_id} 映射回消息并按分排序；配置了 rerank 模型时
 *       先重排（取不到模型 / 调用失败 → 原样返回，对照 Go {@code rerankResults}）。
 *       mode=vector 时 KB 检索失败上抛（handler 500），hybrid 时 Warn 后降级
 *       keyword-only——与 Go 逐字对齐。<b>残留差异</b>：①Go 从请求上下文读租户配置，
 *       Java 经 TenantService 重查租户行（同一数据源，净效果一致）；②Go 的
 *       {@code sort.Slice} 不稳定、Java 用稳定排序，同分消息的相对顺序可能不同
 *       （Go 自身在该输入下也不确定）。</li>
 *   <li><b>clarifyReadArtifactVersions 全量接线（2026-09-23 走查批）</b>：
 *       needsHistory 快路径 + 全会话产物表澄清（ArtifactVersions.clarifyArtifactVersions），
 *       存量会话与新生成轮同享澄清。</li>
 * </ol>
 */
@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    /** 对照 Go {@code types.MessageSearchMode*}。 */
    public static final String MODE_KEYWORD = "keyword";
    public static final String MODE_VECTOR = "vector";
    public static final String MODE_HYBRID = "hybrid";

    /** RRF 融合常数（对照 Go {@code rrfMerge} 的 {@code const k = 60.0}）。 */
    private static final double RRF_K = 60.0;

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final MessageSuggestionRepository suggestionRepository;
    private final KnowledgeService knowledgeService;
    private final TenantService tenantService;
    private final com.ragagent.knowledge.service.KnowledgeBaseService knowledgeBaseService;
    /** 聊天历史 KB 的向量检索执行面（对照 Go kbService.HybridSearch 的 HybridSearch 段）。 */
    private final HybridSearchService hybridSearchService;
    /** 对照 Go modelService.GetRerankModel（rerankResults 的重排模型工厂）。 */
    private final ModelRuntimeFactory modelRuntimeFactory;

    public MessageService(SessionRepository sessionRepository,
                          MessageRepository messageRepository,
                          MessageSuggestionRepository suggestionRepository,
                          KnowledgeService knowledgeService,
                          TenantService tenantService,
                          com.ragagent.knowledge.service.KnowledgeBaseService knowledgeBaseService,
                          HybridSearchService hybridSearchService,
                          ModelRuntimeFactory modelRuntimeFactory) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.suggestionRepository = suggestionRepository;
        this.knowledgeService = knowledgeService;
        this.tenantService = tenantService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.hybridSearchService = hybridSearchService;
        this.modelRuntimeFactory = modelRuntimeFactory;
    }

    /**
     * 搜索管线的**消息级**中间项（对照 Go {@code types.MessageSearchResultItem}）。
     * Go 是 struct 嵌入；Java 用组合携带消息本体——role / request_id / created_at
     * 在补对与分组两步都要用，GroupItem 装不下这些。
     */
    private record SearchItem(MessageWithSession mws, double score, String matchType) {
        String id() {
            return mws.getMessage().getId();
        }

        String requestId() {
            return mws.getMessage().getRequestId();
        }

        String sessionId() {
            return mws.getMessage().getSessionId();
        }

        String role() {
            return mws.getMessage().getRole();
        }
    }

    // ── 读 ──────────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code GetMessage}（L105-128）：
     * **先按读可见性确认会话可读**，再取消息。
     *
     * <p>会话那一步不能省——只查 {@code (session_id, message_id)} 会绕过
     * 本函数里 {@code loadSessionForRead} 建立的那套可见性判定。</p>
     */
    public Message getMessage(String sessionId, String messageId) {
        long tenantId = requireTenantId();
        SessionService.loadSessionForRead(
                sessionRepository, tenantId, SessionService.sessionUserIDForLookup(), sessionId);
        return messageRepository.getMessage(sessionId, messageId);
    }

    /**
     * 对照 Go {@code GetRecentMessagesBySession}（L162-192）：LoadMessages 无
     * {@code before_time} 时的路径。租户取 {@code sessionTenantIDForLookup} 的回退分支
     * （共享 agent 的租户覆盖属阶段 7，恒走 TenantContext）。
     */
    public List<Message> getRecentMessages(String sessionId, int limit) {
        long tenantId = requireTenantId();
        SessionService.loadSessionForRead(
                sessionRepository, tenantId, SessionService.sessionUserIDForLookup(), sessionId);
        return clarifyReadArtifactVersions(sessionId,
                messageRepository.getRecentMessagesBySession(sessionId, limit));
    }

    /**
     * 对照 Go {@code GetMessagesBySessionBeforeTime}（L195-226）：LoadMessages 带
     * {@code before_time} 时的路径。
     */
    public List<Message> getMessagesBeforeTime(String sessionId, java.time.OffsetDateTime beforeTime,
            int limit) {
        long tenantId = requireTenantId();
        SessionService.loadSessionForRead(
                sessionRepository, tenantId, SessionService.sessionUserIDForLookup(), sessionId);
        return clarifyReadArtifactVersions(sessionId,
                messageRepository.getMessagesBySessionBeforeTime(sessionId, beforeTime, limit));
    }

    /**
     * 对照 Go {@code clarifyReadArtifactVersions}（message_artifact_versions.go 全文，
     * 2026-09-23 走查批接线）：所有消息的产物 URL 都被正文直接引用时零开销原样返回
     * （Go 同款快路径）；否则取全会话产物表，把每条消息正文里引用的跨版本 URL 澄清成
     * 「历史版本 / 本轮生成」对照。存量会话与新生成轮获得同样的澄清——即使产生
     * 旧版本的消息不在本页。调用方已授权会话读取。
     */
    private List<Message> clarifyReadArtifactVersions(String sessionId, List<Message> messages) {
        boolean needsHistory = false;
        for (Message message : messages) {
            if (message == null || message.getArtifacts() == null
                    || message.getArtifacts().isEmpty()) {
                continue;
            }
            Set<String> owned = new java.util.HashSet<>();
            for (MessageArtifact artifact : message.getArtifacts()) {
                owned.add(artifact.getUrl());
            }
            for (String ref : ResourceReferences.scan(message.getContent())) {
                if (!owned.contains(ref)) {
                    needsHistory = true;
                }
            }
        }
        if (!needsHistory) {
            return messages;
        }
        List<MessageArtifact> previous;
        try {
            previous = messageRepository.getSessionArtifacts(sessionId);
        } catch (RuntimeException e) {
            log.warn("Read artifact versions failed: {}", e.toString());
            return messages;
        }
        if (previous == null) {
            previous = List.of();
        }
        for (Message message : messages) {
            if (message == null) {
                continue;
            }
            Set<String> refs = new java.util.HashSet<>(
                    ResourceReferences.scan(message.getContent()));
            List<MessageArtifact> referenced = new java.util.ArrayList<>();
            for (MessageArtifact artifact : previous) {
                if (artifact != null && refs.contains(artifact.getUrl())) {
                    referenced.add(artifact);
                }
            }
            message.setContent(com.ragagent.session.domain.ArtifactVersions.clarifyArtifactVersions(
                    message.getContent(), message.getArtifacts(), referenced,
                    com.ragagent.common.wiki.WikiLanguageSupport.languageFromContextOrDefault()));
        }
        return messages;
    }

    /**
     * 对照 Go {@code GetSessionArtifacts}（message.go L516-523）：会话全部 assistant
     * 消息的产物，按创建序扁平化（空会话 id 返回空列表）。
     */
    public List<com.ragagent.session.domain.MessageArtifact> getSessionArtifacts(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return List.of();
        }
        List<com.ragagent.session.domain.MessageArtifact> artifacts =
                messageRepository.getSessionArtifacts(sessionId);
        return artifacts == null ? List.of() : artifacts;
    }

    // ── 删 ──────────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code DeleteMessage}（L266-310）：**写路径用严格 owner 范围**
     * （{@code sessionRepo.Get}，无 Admin 回退），消息不存在 → gorm.ErrRecordNotFound
     * （handler 落 404 "record not found"）；删完删建议，带 knowledge_id 的消息再
     * 尽力而为清聊天历史知识。
     */
    public void deleteMessage(String sessionId, String messageId) {
        long tenantId = requireTenantId();
        sessionRepository.get(tenantId, SessionService.sessionUserIDForLookup(), sessionId);

        Message msg = messageRepository.getMessage(sessionId, messageId);

        messageRepository.delete(sessionId, messageId);

        try {
            suggestionRepository.deleteByMessageId(tenantId, sessionId, messageId);
        } catch (RuntimeException e) {
            log.warn("Failed to delete suggestions for message {}: {}", messageId, e.toString());
        }

        String knowledgeId = msg.getKnowledgeId();
        if (knowledgeId != null && !knowledgeId.isEmpty()) {
            try {
                knowledgeService.deleteKnowledge(knowledgeId);
            } catch (RuntimeException e) {
                log.warn("Failed to delete chat history knowledge {}: {}", knowledgeId, e.toString());
            }
        }
    }

    /**
     * 对照 Go {@code ClearSessionMessages}（L313-338）：软删全部消息 + 删建议 +
     * 尽力而为清聊天历史知识（Go 在 goroutine 里读 knowledge_ids 后批删——
     * 与消息软删之间存在竞态；Java 刻意**先读后删**，清理更干净且 HTTP 不可见）。
     */
    public void clearSessionMessages(String sessionId) {
        long tenantId = requireTenantId();
        sessionRepository.get(tenantId, SessionService.sessionUserIDForLookup(), sessionId);

        try {
            List<String> knowledgeIds = messageRepository.getKnowledgeIdsBySessionId(sessionId);
            for (String knowledgeId : knowledgeIds) {
                try {
                    knowledgeService.deleteKnowledge(knowledgeId);
                } catch (RuntimeException e) {
                    log.warn("Failed to delete chat history knowledge for session {}: {}",
                            sessionId, e.toString());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Failed to get knowledge IDs for session {}: {}", sessionId, e.toString());
        }

        messageRepository.deleteBySessionId(sessionId);

        try {
            suggestionRepository.deleteBySessionId(tenantId, sessionId);
        } catch (RuntimeException e) {
            log.warn("Failed to delete suggestions for session {}: {}", sessionId, e.toString());
        }
    }

    // ── 写（波 4.6d 补，对照 Go message.go 的写方法） ────────────────────────

    /**
     * 对照 Go {@code CreateMessage}（message.go L79-110）：先按严格 owner 范围
     * 确认会话存在（无 Admin 回退），再落行。
     */
    public Message createMessage(Message message) {
        long tenantId = requireTenantId();
        sessionRepository.get(tenantId, SessionService.sessionUserIDForLookup(), message.getSessionId());
        return messageRepository.create(message);
    }

    /** 对照 Go {@code UpdateMessage}（message.go L229-254）。 */
    public void updateMessage(Message message) {
        long tenantId = requireTenantId();
        sessionRepository.get(tenantId, SessionService.sessionUserIDForLookup(), message.getSessionId());
        messageRepository.update(message);
    }

    /** 对照 Go {@code UpdateMessageImages}（message.go L256-259）：只写 images 列。 */
    public void updateMessageImages(String sessionId, String messageId, List<MessageImage> images) {
        messageRepository.updateImages(sessionId, messageId, images);
    }

    /** 对照 Go {@code UpdateMessageRenderedContent}（message.go L261-263）：只写 rendered_content 列。 */
    public void updateMessageRenderedContent(String sessionId, String messageId, String renderedContent) {
        messageRepository.updateRenderedContent(sessionId, messageId, renderedContent);
    }

    /** 对照 Go {@code GetSessionAttachments}（仓储直查，波 4.6d 的 sandbox staging 用）。 */
    public List<MessageAttachment> getSessionAttachments(String sessionId) {
        return messageRepository.getSessionAttachments(sessionId);
    }

    /**
     * 对照 Go {@code IndexMessageToKB}（message.go L374-412）：把问答对入聊天历史 KB，
     * 并把 knowledge_id 回写到消息上。Go 起协程（WithoutCancel）；Java 同样交虚拟
     * 线程（调用点 KnowledgeQaController 已起）。未配置/未启用/缺 embedding 模型时
     * 静默跳过——尽力而为，失败只记日志。
     *
     * <p><b>已知差异（备案）</b>：Go 的 chunk/向量索引由 asynq worker 异步执行；
     * Java 侧 passage 走既有的 {@link KnowledgeService#createFromPassageSync}
     * （逐字段对照 passage 路径），异步性由调用方的后台线程承接——落库与索引语义
     * 同步完成，HTTP 响应不受影响。</p>
     */
    public void indexMessageToKb(String userQuery, String assistantAnswer, String messageId,
            String sessionId) {
        // 剥 thinking（<think>…</think>）再入 KB：中间推理会污染检索质量（照 regThinkIndex）
        String answer = ThinkBlocks.stripThinkBlocks(assistantAnswer == null ? "" : assistantAnswer);
        String query = userQuery == null ? "" : userQuery;

        if (query.strip().isEmpty() && answer.isEmpty()) {
            return;
        }

        ChatHistoryConfig cfg = getChatHistoryConfig();
        if (cfg == null) {
            // 与 Go 同：说清为什么跳过——stats 端点只看 Enabled，索引还要求
            // embedding 模型与已创建的 KB（否则运维看到 indexed=0 而无日志可查）
            log.info("Skipping message index for message {}: {}", messageId,
                    describeChatHistorySkip());
            return;
        }

        log.info("Indexing message to chat history KB {}, message ID: {}, session ID: {}",
                cfg.getKnowledgeBaseId(), messageId, sessionId);

        // Q&A 合成一条 passage（照 Go 文案）：同段对语义搜索更友好
        String passage = "[Session: " + sessionId + "]\nQ: " + query + "\nA: " + answer;

        Knowledge knowledge;
        try {
            knowledge = knowledgeService.createFromPassageSync(
                    cfg.getKnowledgeBaseId(), List.of(passage), "");
        } catch (RuntimeException e) {
            log.warn("Failed to index message to chat history KB: {}", e.toString());
            return;
        }

        try {
            messageRepository.updateKnowledgeId(messageId, knowledge.getId());
        } catch (RuntimeException e) {
            log.warn("Failed to update message knowledge_id: {}", e.toString());
            return;
        }

        log.info("Message indexed to chat history KB: knowledge_id={}, message_id={}",
                knowledge.getId(), messageId);
    }

    /**
     * 对照 Go {@code describeChatHistorySkip}（message.go L423-441）：报出跳过索引时
     * 缺的是哪一项前置（ChatHistoryConfig.IsConfigured 要求 Enabled + 选中 embedding
     * 模型 + 已创建的 KB，只开开关不够）。
     */
    private String describeChatHistorySkip() {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null) {
            return "no tenant in context";
        }
        Tenant tenant = tenantService.getTenantById(tenantId);
        JsonNode node = tenant == null ? null : tenant.getChatHistoryConfig();
        if (node == null || node.isNull()) {
            return "chat history config not set";
        }
        if (!node.path("enabled").asBoolean(false)) {
            return "chat history indexing disabled";
        }
        if (node.path("embedding_model_id").asText("").isEmpty()) {
            return "enabled but no embedding model selected";
        }
        if (node.path("knowledge_base_id").asText("").isEmpty()) {
            return "enabled but chat history knowledge base not created yet";
        }
        return "chat history config incomplete";
    }

    // ── 搜索 ────────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code SearchMessages}（L531-617）。
     *
     * <p>搜索范围与列表同构：**按人裁剪**（owner scope），防止搜索框读到同事的私聊
     * （Go 的注释原文，session.go 同款语义）。</p>
     *
     * <p>向量路径经聊天历史 KB 的 HybridSearch（见 {@link #vectorSearchViaKb}）：
     * 未配置聊天历史 KB 时两侧一致恒跳过；mode=vector 时 KB 检索失败上抛（500），
     * hybrid 时降级 keyword-only。</p>
     *
     * @param query      已由 controller 做 SanitizeForLog（Go 在 handler 里做）
     * @param mode       keyword / vector / hybrid（空 → hybrid）
     * @param limit      ≤0 → 20
     * @param sessionIds 可选过滤
     */
    public MessageSearchResult searchMessages(String query, String mode, int limit,
            List<String> sessionIds) {
        return searchMessages(query, mode, limit, sessionIds, null);
    }

    /**
     * 显式 owner 的搜索变体（对照 Go {@code MessageSearchParams.OwnerID}——
     * search_conversations 工具在引擎装配期捕获 owner 后按入参传入，不读线程上下文）。
     */
    public MessageSearchResult searchMessages(String query, String mode, int limit,
            List<String> sessionIds, String ownerIdOverride) {
        long tenantId = requireTenantId();
        String ownerId = ownerIdOverride == null || ownerIdOverride.isEmpty()
                ? SessionOwnerIds.currentSessionOwnerId()
                : ownerIdOverride;

        if (mode == null || mode.isEmpty()) {
            mode = MODE_HYBRID;
        }
        if (limit <= 0) {
            limit = 20;
        }

        // Step 1：关键词搜索（PG ILIKE；limit*3 照抄 Go）
        List<MessageWithSession> keywordRows = List.of();
        if (MODE_KEYWORD.equals(mode) || MODE_HYBRID.equals(mode)) {
            keywordRows = messageRepository.searchMessagesByKeyword(
                    tenantId, ownerId, query, sessionIds, limit * 3);
        }

        // Step 2：向量搜索（经聊天历史 KB；未配置 → 空，Go 的 nil, nil 分支）
        List<SearchItem> vectorResults = List.of();
        if (MODE_VECTOR.equals(mode) || MODE_HYBRID.equals(mode)) {
            try {
                vectorResults = vectorSearchViaKb(query, sessionIds);
                log.info("Vector search found {} results", vectorResults.size());
            } catch (RuntimeException e) {
                // Go：两种模式都先 Warnf，vector 模式再上抛（handler 500）、
                // hybrid 模式吞掉降级 keyword-only
                log.warn("Vector search via KB failed, falling back to keyword-only: {}",
                        e.toString());
                if (MODE_VECTOR.equals(mode)) {
                    throw e;
                }
            }
        }

        // Step 3：按模式合并
        List<SearchItem> items;
        if (MODE_KEYWORD.equals(mode)) {
            // ⚠️ keyword 分支不是直接透传：Go 走 convertKeywordResults 赋线性分值
            items = convertKeywordResults(keywordRows);
        } else if (MODE_VECTOR.equals(mode)) {
            items = vectorResults;
        } else {
            items = rrfMerge(toItems(keywordRows, "keyword"), vectorResults);
        }

        // 所有权复核（向量路径经共享 KB，必须重查归属；关键词路径本来就按人裁剪，幂等）
        items = restrictToOwnedSessions(tenantId, ownerId, items);

        // Step 4：补 Q&A 对的另一半
        items = fetchPartnerMessages(items);

        // Step 5：按 request_id 合并成 Q&A 对
        List<MessageSearchGroupItem> grouped = groupByRequestID(items);

        if (grouped.size() > limit) {
            grouped = new ArrayList<>(grouped.subList(0, limit));
        }

        MessageSearchResult result = new MessageSearchResult();
        result.setItems(grouped);
        result.setTotal(grouped.size());
        return result;
    }

    // ── 搜索 · 向量路径（对照 Go vectorSearchViaKB / rerankResults / 两个配置读取） ────

    /**
     * 对照 Go {@code getChatHistoryConfig}（message.go L344-356）：读租户的聊天历史
     * KB 配置，**三要素不全即视为未配置**（{@code IsConfigured} = Enabled +
     * EmbeddingModelID + KnowledgeBaseID 逐字段对照）→ 返回 null，向量搜索恒空。
     *
     * <p>Go 从请求上下文取租户对象；Java 的 TenantContext 只带 id，按本类既有模式
     * （{@link #getChatHistoryKbStats}）经 TenantService 重查同一行。</p>
     */
    private ChatHistoryConfig getChatHistoryConfig() {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null) {
            return null;
        }
        Tenant tenant = tenantService.getTenantById(tenantId);
        if (tenant == null) {
            return null;
        }
        JsonNode node = tenant.getChatHistoryConfig();
        if (node == null || node.isNull()) {
            return null;
        }
        ChatHistoryConfig cfg = new ChatHistoryConfig();
        cfg.setEnabled(node.path("enabled").asBoolean(false));
        cfg.setEmbeddingModelId(node.path("embedding_model_id").asText(""));
        cfg.setKnowledgeBaseId(node.path("knowledge_base_id").asText(""));
        // 对照 Go ChatHistoryConfig.IsConfigured（chat_history_config.go L45-47）
        if (cfg.isEnabled() && !cfg.getEmbeddingModelId().isEmpty()
                && !cfg.getKnowledgeBaseId().isEmpty()) {
            return cfg;
        }
        return null;
    }

    /**
     * 对照 Go {@code getRetrievalConfig}（message.go L358-368）：未配置 → 空配置
     * （各有效值走 GetEffective* 的缺省）。
     */
    private RetrievalConfig getRetrievalConfig() {
        Long tenantId = TenantContext.currentTenantId();
        RetrievalConfig rc = new RetrievalConfig();
        if (tenantId == null) {
            return rc;
        }
        Tenant tenant = tenantService.getTenantById(tenantId);
        JsonNode node = tenant == null ? null : tenant.getRetrievalConfig();
        if (node == null || node.isNull()) {
            return rc;
        }
        rc.setEmbeddingTopK(node.path("embedding_top_k").asInt(0));
        rc.setVectorThreshold(node.path("vector_threshold").asDouble(0));
        rc.setRerankTopK(node.path("rerank_top_k").asInt(0));
        rc.setRerankThreshold(node.path("rerank_threshold").asDouble(0));
        rc.setRerankModelId(node.path("rerank_model_id").asText(""));
        return rc;
    }

    /**
     * 对照 Go {@code vectorSearchViaKB}（message.go L651-724）：聊天历史 KB 的
     * **vector-only** 检索（关键词在 messages 表上单独做）→ 按 {@code knowledge_id}
     * 映射回消息 → 按分排序。失败一律抛 RuntimeException（message 对照 Go 的
     * {@code fmt.Errorf} 原文），由调用方按模式决定上抛还是降级。
     */
    private List<SearchItem> vectorSearchViaKb(String query, List<String> sessionIds) {
        ChatHistoryConfig cfg = getChatHistoryConfig();
        if (cfg == null) {
            return List.of(); // 聊天历史 KB 未配置，跳过向量搜索（Go: return nil, nil）
        }

        RetrievalConfig rc = getRetrievalConfig();

        // vector-only 语义（Go 逐字段）：QueryText + MatchCount(=有效 EmbeddingTopK)
        // + VectorThreshold + DisableKeywordsMatch=true
        SearchParams searchParams = new SearchParams();
        searchParams.setQueryText(query);
        searchParams.setMatchCount(effectiveEmbeddingTopK(rc));
        searchParams.setVectorThreshold(effectiveVectorThreshold(rc));
        searchParams.setDisableKeywordsMatch(true);

        List<SearchResult> kbResults;
        try {
            kbResults = hybridSearchService.hybridSearch(cfg.getKnowledgeBaseId(), searchParams);
        } catch (RuntimeException e) {
            throw new IllegalStateException("KB hybrid search failed: " + e.getMessage(), e);
        }
        if (kbResults == null || kbResults.isEmpty()) {
            return List.of();
        }

        // 配置了 rerank 模型才重排（未配置/失败 → 原样返回）
        kbResults = rerankResults(rc, query, kbResults);
        if (kbResults.isEmpty()) {
            return List.of();
        }

        // KB 命中 → knowledge_id → 消息
        List<String> knowledgeIds = new ArrayList<>(kbResults.size());
        Map<String, Double> scoreByKnowledgeId = new LinkedHashMap<>();
        for (SearchResult r : kbResults) {
            knowledgeIds.add(r.getKnowledgeId());
            scoreByKnowledgeId.put(r.getKnowledgeId(), r.getScore());
        }

        List<MessageWithSession> messages;
        try {
            messages = messageRepository.getMessagesByKnowledgeIds(knowledgeIds);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "failed to get messages by knowledge IDs: " + e.getMessage(), e);
        }

        // SessionIDs 过滤（Go 的 sessionFilter map）
        Set<String> sessionFilter = sessionIds == null ? Set.of() : new HashSet<>(sessionIds);

        List<SearchItem> results = new ArrayList<>();
        for (MessageWithSession msg : messages) {
            String sid = msg.getMessage().getSessionId();
            if (!sessionFilter.isEmpty() && !sessionFilter.contains(sid)) {
                continue;
            }
            double score = scoreByKnowledgeId.getOrDefault(msg.getMessage().getKnowledgeId(), 0.0);
            results.add(new SearchItem(msg, score, "vector"));
        }

        // 按分降序。Go 的 sort.Slice 不稳定；Java 用稳定排序（同分消息的相对顺序
        // Go 自身也不确定，已知差异见类注释第 2 条）
        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        return results;
    }

    /**
     * 对照 Go {@code rerankResults}（message.go L728-773）：配置了 rerank 模型才重排；
     * 取不到模型 / 调用失败都**原样返回**（Go 的 Warnf + return results）。
     * 命中按 threshold 过滤、topK 截断，score 换成重排分——Go 是 struct 值拷贝，
     * Java 用 {@link SearchResult#copy()}，同样不改原结果对象。
     */
    private List<SearchResult> rerankResults(RetrievalConfig rc, String query,
            List<SearchResult> results) {
        if (rc == null || rc.getRerankModelId().isEmpty() || results.isEmpty()) {
            return results;
        }

        Reranker reranker;
        try {
            reranker = modelRuntimeFactory.getRerankModel(rc.getRerankModelId());
        } catch (RuntimeException e) {
            log.warn("Failed to get rerank model {}, skipping rerank: {}",
                    rc.getRerankModelId(), e.toString());
            return results;
        }

        List<String> documents = new ArrayList<>(results.size());
        for (SearchResult r : results) {
            documents.add(r.getContent());
        }

        List<RankResult> rankResults;
        try {
            rankResults = reranker.rerank(query, documents);
        } catch (RuntimeException e) {
            log.warn("Rerank call failed, skipping: {}", e.toString());
            return results;
        }

        double threshold = effectiveRerankThreshold(rc);
        int topK = effectiveRerankTopK(rc);

        List<SearchResult> reranked = new ArrayList<>();
        for (RankResult rr : rankResults) {
            if (rr.getIndex() >= results.size()) {
                continue;
            }
            if (rr.getRelevanceScore() < threshold) {
                continue;
            }
            SearchResult item = results.get(rr.getIndex()).copy(); // Go: item := *results[...]
            item.setScore(rr.getRelevanceScore());
            reranked.add(item);
            if (reranked.size() >= topK) {
                break;
            }
        }

        log.info("Rerank: {} -> {} results (threshold={}, topK={})", results.size(),
                reranked.size(), String.format(java.util.Locale.ROOT, "%.2f", threshold), topK);
        return reranked;
    }

    /** 对照 Go {@code GetEffectiveEmbeddingTopK}（≤0 → DefaultRetrievalTopK=50）。 */
    private static int effectiveEmbeddingTopK(RetrievalConfig rc) {
        if (rc == null || rc.getEmbeddingTopK() <= 0) {
            return 50;
        }
        return rc.getEmbeddingTopK();
    }

    /** 对照 Go {@code GetEffectiveVectorThreshold}（≤0 → 0.15）。 */
    private static double effectiveVectorThreshold(RetrievalConfig rc) {
        if (rc == null || rc.getVectorThreshold() <= 0) {
            return 0.15;
        }
        return rc.getVectorThreshold();
    }

    /** 对照 Go {@code GetEffectiveRerankTopK}（≤0 → 10）。 */
    private static int effectiveRerankTopK(RetrievalConfig rc) {
        if (rc == null || rc.getRerankTopK() <= 0) {
            return 10;
        }
        return rc.getRerankTopK();
    }

    /**
     * 对照 Go {@code GetEffectiveRerankThreshold}：**只有 rc==nil 才回 0.2**——
     * 显式配置 0 是合法值（不设 {@code <= 0} 缺省，别顺手"修好"）。
     */
    private static double effectiveRerankThreshold(RetrievalConfig rc) {
        if (rc == null) {
            return 0.2;
        }
        return rc.getRerankThreshold();
    }

    private static List<SearchItem> toItems(List<MessageWithSession> rows, String matchType) {
        List<SearchItem> items = new ArrayList<>(rows.size());
        for (MessageWithSession row : rows) {
            items.add(new SearchItem(row, 0, matchType));
        }
        return items;
    }

    /** 对照 Go {@code convertKeywordResults}（L776-786）：分值 = (n-i)/n，matchType=keyword。 */
    private static List<SearchItem> convertKeywordResults(List<MessageWithSession> results) {
        List<SearchItem> items = new ArrayList<>(results.size());
        int n = results.size();
        for (int i = 0; i < n; i++) {
            items.add(new SearchItem(results.get(i), (double) (n - i) / n, "keyword"));
        }
        return items;
    }

    /**
     * 对照 Go {@code rrfMerge}（L789-843）：Reciprocal Rank Fusion。
     * 同一条消息两路都命中 → 分数累加、matchType 升级为 hybrid；
     * 排序按融合分降序。keyword 侧的初始 matchType 是 "keyword"、
     * 向量侧是 "vector"（Go 同款，见 {@code accumulate} 的 singleMatchType）。
     */
    private static List<SearchItem> rrfMerge(List<SearchItem> keywordResults,
            List<SearchItem> vectorResults) {
        Map<String, double[]> scoreMap = new LinkedHashMap<>(); // id → {rrfScore}
        Map<String, SearchItem> itemById = new HashMap<>();
        Map<String, String> matchTypeById = new HashMap<>();

        accumulate(keywordResults, scoreMap, itemById, matchTypeById, "keyword");
        accumulate(vectorResults, scoreMap, itemById, matchTypeById, "vector");

        List<SearchItem> items = new ArrayList<>(scoreMap.size());
        for (Map.Entry<String, double[]> e : scoreMap.entrySet()) {
            String id = e.getKey();
            String matchType = matchTypeById.get(id);
            items.add(new SearchItem(itemById.get(id).mws(), e.getValue()[0], matchType));
        }
        items.sort((a, b) -> Double.compare(b.score(), a.score()));
        return items;
    }

    private static void accumulate(List<SearchItem> results, Map<String, double[]> scoreMap,
            Map<String, SearchItem> itemById, Map<String, String> matchTypeById,
            String singleMatchType) {
        double rank = 1;
        for (SearchItem item : results) {
            double rrfScore = 1.0 / (RRF_K + rank);
            double[] existing = scoreMap.get(item.id());
            if (existing != null) {
                existing[0] += rrfScore;
                matchTypeById.put(item.id(), "hybrid");
            } else {
                scoreMap.put(item.id(), new double[] {rrfScore});
                itemById.put(item.id(), item);
                matchTypeById.put(item.id(), singleMatchType);
            }
            rank++;
        }
    }

    /** 对照 Go {@code restrictToOwnedSessions}（L620-649）。 */
    private List<SearchItem> restrictToOwnedSessions(long tenantId, String ownerId,
            List<SearchItem> items) {
        if (ownerId == null || ownerId.isEmpty() || items.isEmpty()) {
            return items;
        }
        List<String> sessionIds = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SearchItem item : items) {
            if (item.sessionId() == null || item.sessionId().isEmpty()) {
                continue;
            }
            if (seen.add(item.sessionId())) {
                sessionIds.add(item.sessionId());
            }
        }
        Map<String, Boolean> owned =
                messageRepository.ownedSessionIds(tenantId, ownerId, sessionIds);
        List<SearchItem> filtered = new ArrayList<>(items.size());
        for (SearchItem item : items) {
            if (Boolean.TRUE.equals(owned.get(item.sessionId()))) {
                filtered.add(item);
            }
        }
        return filtered;
    }

    /**
     * 对照 Go {@code fetchPartnerMessages}（L848-906）：对每个 request_id 检查
     * 是否已同时有 user 与 assistant 两侧，缺侧的从库里补另一条（score=0、
     * matchType 空串——"非直接命中"）。
     */
    private List<SearchItem> fetchPartnerMessages(List<SearchItem> items) {
        Set<String> existingIds = new HashSet<>();
        Map<String, boolean[]> roleSeen = new LinkedHashMap<>(); // rid → {hasUser, hasAssistant}
        for (SearchItem item : items) {
            existingIds.add(item.id());
            String rid = item.requestId();
            if (rid == null || rid.isEmpty()) {
                continue;
            }
            boolean[] roles = roleSeen.computeIfAbsent(rid, x -> new boolean[2]);
            if ("user".equals(item.role())) {
                roles[0] = true;
            } else if ("assistant".equals(item.role())) {
                roles[1] = true;
            }
        }

        List<String> needFetch = new ArrayList<>();
        for (Map.Entry<String, boolean[]> e : roleSeen.entrySet()) {
            if (!(e.getValue()[0] && e.getValue()[1])) {
                needFetch.add(e.getKey());
            }
        }
        if (needFetch.isEmpty()) {
            return items;
        }

        List<SearchItem> out = new ArrayList<>(items);
        try {
            List<MessageWithSession> partners = messageRepository.getMessagesByRequestIds(needFetch);
            for (MessageWithSession p : partners) {
                if (existingIds.contains(p.getMessage().getId())) {
                    continue;
                }
                existingIds.add(p.getMessage().getId());
                out.add(new SearchItem(p, 0, ""));
            }
        } catch (RuntimeException e) {
            log.warn("Failed to fetch partner messages: {}", e.toString());
            return items;
        }
        return out;
    }

    /**
     * 对照 Go {@code groupByRequestID}（L910-978）：按 request_id 合并成 Q&amp;A 对；
     * 无 request_id 的消息按**消息 id** 独立成组（Go 的 {@code key = item.ID} 分支）；
     * 组间保持首次出现序（分数排名即展示序）。
     */
    private static List<MessageSearchGroupItem> groupByRequestID(List<SearchItem> items) {
        Map<String, MessageSearchGroupItem> groups = new LinkedHashMap<>();
        for (SearchItem item : items) {
            String key = item.requestId() == null || item.requestId().isEmpty()
                    ? item.id()
                    : item.requestId();

            MessageSearchGroupItem g = groups.get(key);
            if (g == null) {
                g = new MessageSearchGroupItem();
                g.setRequestId(item.requestId());
                g.setSessionId(item.sessionId());
                g.setSessionTitle(item.mws().getSessionTitle());
                g.setCreatedAt(item.mws().getMessage().getCreatedAt());
                groups.put(key, g);
            }

            switch (item.role() == null ? "" : item.role()) {
                case "user" -> g.setQueryContent(item.mws().getMessage().getContent());
                case "assistant" -> g.setAnswerContent(item.mws().getMessage().getContent());
                default -> {
                }
            }

            if (item.score() > g.getScore()) {
                g.setScore(item.score());
            }
            // ⚠️ Go 的 merge 分支不排除空串：partner 补对的 matchType 是 ""，
            // 与已有的 "keyword" 不同 → 直接升 "hybrid"（golden 实测，search 的
            // match_type 全是 hybrid 就是这么来的）。
            if (g.getMatchType() == null || g.getMatchType().isEmpty()) {
                g.setMatchType(item.matchType());
            } else if (item.matchType() != null && !g.getMatchType().equals(item.matchType())) {
                g.setMatchType("hybrid");
            }

            if (item.mws().getMessage().getCreatedAt() != null
                    && (g.getCreatedAt() == null || item.mws().getMessage().getCreatedAt()
                            .isBefore(g.getCreatedAt()))) {
                g.setCreatedAt(item.mws().getMessage().getCreatedAt());
            }
        }
        return new ArrayList<>(groups.values());
    }

    // ── 统计 ────────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code GetChatHistoryKBStats}（L475-510）：未配置 / 未启用 → 全零统计。
     * KB 取不到时 Go 是 Warnf + 返回已填的部分（不是错误）。
     */
    public ChatHistoryKbStats getChatHistoryKbStats() {
        long tenantId = requireTenantId();
        Tenant tenant = tenantService.getTenantById(tenantId);

        ChatHistoryKbStats stats = new ChatHistoryKbStats();
        if (tenant == null) {
            return stats;
        }
        JsonNode cfg = tenant.getChatHistoryConfig();
        if (cfg == null || !cfg.path("enabled").asBoolean(false)) {
            return stats;
        }

        stats.setEnabled(true);
        stats.setEmbeddingModelId(cfg.path("embedding_model_id").asText(""));
        stats.setKnowledgeBaseId(cfg.path("knowledge_base_id").asText(""));

        String kbId = stats.getKnowledgeBaseId();
        if (kbId.isEmpty()) {
            return stats;
        }

        try {
            com.ragagent.knowledge.domain.KnowledgeBase kb =
                    knowledgeBaseService.getKnowledgeBase(kbId);
            stats.setKnowledgeBaseName(kb.getName() == null ? "" : kb.getName());
            long count = kb.getKnowledgeCount();
            stats.setIndexedMessageCount(count);
            stats.setHasIndexedMessages(count > 0);
        } catch (RuntimeException e) {
            log.warn("Failed to load chat history KB {}: {}", kbId, e.toString());
        }
        return stats;
    }

    // ── 公共 ────────────────────────────────────────────────────────────────

    private static long requireTenantId() {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("types.TenantIDContextKey not set in context");
        }
        return tenantId;
    }

    /** 对照 types.ScanResourceReferences：抽答案里全部 resource://<handle> 引用。 */
    static final class ResourceReferences {
        // 对照 Go resourceReferenceRE（types/resource.go L164）：handle 是 base64url
        // 的 22 字符，字符集含 -/_（缺了会漏掉约一半的引用）。
        private static final java.util.regex.Pattern RESOURCE_REF =
                java.util.regex.Pattern.compile("resource://[A-Za-z0-9_-]{22}");

        /** 对照 isResourceHandleChar（resource.go L159-162）。 */
        private static boolean isResourceHandleChar(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
        }

        static List<String> scan(String content) {
            List<String> out = new ArrayList<>();
            if (content == null || content.isEmpty()) {
                return out;
            }
            // Go：更长的 handle 字符连串不是「22 字符 handle 带尾随文本」——那是别的
            // 非法 token，绑截断前缀会挂错文件（resource.go L181-185 的边界检查）。
            var m = RESOURCE_REF.matcher(content);
            List<int[]> spans = new ArrayList<>();
            while (m.find()) {
                spans.add(new int[]{m.start(), m.end()});
            }
            for (int[] span : spans) {
                if (span[1] < content.length() && isResourceHandleChar(content.charAt(span[1]))) {
                    continue;
                }
                String ref = content.substring(span[0], span[1]);
                if (!out.contains(ref)) {
                    out.add(ref);
                }
            }
            return out;
        }
    }
}
