package com.ragagent.session.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.tenantconfig.ChatHistoryConfig;
import com.ragagent.auth.service.TenantService;
import com.ragagent.agent.tools.ThinkBlocks;
import com.ragagent.common.context.TenantContext;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.service.KnowledgeService;
import com.ragagent.model.service.ModelRuntimeFactory;
import com.ragagent.retrieval.HybridSearchService;
import com.ragagent.session.domain.ChatHistoryKbStats;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.MessageArtifact;
import com.ragagent.session.domain.MessageAttachment;
import com.ragagent.session.domain.MessageImage;
import com.ragagent.session.domain.MessageSearchResult;
import com.ragagent.session.mapper.MessageRepository;
import com.ragagent.session.mapper.MessageSuggestionRepository;
import com.ragagent.session.mapper.SessionRepository;

/**
 * 消息服务。
 *
 * <h2>覆盖面</h2>
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
 * <h2>已知差异（均不改变 HTTP 契约）</h2>
 * <ol>
 *   <li><b>聊天历史知识清理是同步尽力而为</b>：异步语义（错误全吞）。
 *       HTTP 响应不受影响。</li>
 *   <li><b>向量搜索</b>：
 *       租户配置了 ChatHistoryConfig 时走
 *       {@link HybridSearchService#hybridSearch}（vector-only：
 *       {@code DisableKeywordsMatch=true}，关键词仍在 messages 表上单独搜），
 *       KB 命中经 {@code knowledge_id} 映射回消息并按分排序；配置了 rerank 模型时
 *       先重排（取不到模型 / 调用失败 → 原样返回）。
 *       mode=vector 时 KB 检索失败上抛（500），hybrid 时 Warn 后降级
 *       keyword-only。<b>实现差异</b>：①租户配置经 TenantService 重查租户行
 *       （同一数据源，净效果一致）；②稳定排序，同分消息的相对顺序保留 SQL 顺序。</li>
 *   <li><b>clarifyReadArtifactVersions</b>：
 *       needsHistory 快路径 + 全会话产物表澄清（ArtifactVersions.clarifyArtifactVersions），
 *       存量会话与新生成轮同享澄清。</li>
 * </ol>
 */
@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    /** 检索模式词表。 */
    public static final String MODE_KEYWORD = "keyword";
    public static final String MODE_VECTOR = "vector";
    public static final String MODE_HYBRID = "hybrid";

    /** RRF 融合常数 k = 60.0。 */

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final MessageSuggestionRepository suggestionRepository;
    private final KnowledgeService knowledgeService;
    private final TenantService tenantService;
    private final com.ragagent.knowledge.service.KnowledgeBaseService knowledgeBaseService;
    /** 聊天历史检索切片（关键词/向量/混合 + RRF + 归属过滤 + 分组）。 */
    private final MessageSearch messageSearch;

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
        this.messageSearch = new MessageSearch(messageRepository, tenantService,
                hybridSearchService, modelRuntimeFactory);
    }


    // ── 读 ──────────────────────────────────────────────────────────────────

    /**
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
     * LoadMessages 无
     * {@code before_time} 时的路径。租户恒走 TenantContext。
     */
    public List<Message> getRecentMessages(String sessionId, int limit) {
        long tenantId = requireTenantId();
        SessionService.loadSessionForRead(
                sessionRepository, tenantId, SessionService.sessionUserIDForLookup(), sessionId);
        return clarifyReadArtifactVersions(sessionId,
                messageRepository.getRecentMessagesBySession(sessionId, limit));
    }

    /**
     * LoadMessages 带
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
     * 所有消息的产物 URL 都被正文直接引用时零开销原样返回
     * （快路径）；否则取全会话产物表，把每条消息正文里引用的跨版本 URL 澄清成
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
     * 会话全部 assistant
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
     * **写路径用严格 owner 范围**
     * （无 Admin 回退），消息不存在 → 404 "record not found"；
     * 删完删建议，带 knowledge_id 的消息再
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
     * 软删全部消息 + 删建议 +
     * 尽力而为清聊天历史知识（**先读后删**，避免与消息软删之间的竞态；
     * 清理失败不影响 HTTP）。
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

    // ── 写 ──────────────────────────────────────────────────────────────────

    /**
     * 先按严格 owner 范围
     * 确认会话存在（无 Admin 回退），再落行。
     */
    public Message createMessage(Message message) {
        long tenantId = requireTenantId();
        sessionRepository.get(tenantId, SessionService.sessionUserIDForLookup(), message.getSessionId());
        return messageRepository.create(message);
    }

    /** 更新消息（严格 owner 范围校验后按零值跳过语义落库）。 */
    public void updateMessage(Message message) {
        long tenantId = requireTenantId();
        sessionRepository.get(tenantId, SessionService.sessionUserIDForLookup(), message.getSessionId());
        messageRepository.update(message);
    }

    /** 只写 images 列。 */
    public void updateMessageImages(String sessionId, String messageId, List<MessageImage> images) {
        messageRepository.updateImages(sessionId, messageId, images);
    }

    /** 只写 rendered_content 列。 */
    public void updateMessageRenderedContent(String sessionId, String messageId, String renderedContent) {
        messageRepository.updateRenderedContent(sessionId, messageId, renderedContent);
    }

    /** 仓储直查（sandbox staging 用）。 */
    public List<MessageAttachment> getSessionAttachments(String sessionId) {
        return messageRepository.getSessionAttachments(sessionId);
    }

    /**
     * 把问答对入聊天历史 KB，
     * 并把 knowledge_id 回写到消息上。在后台虚拟线程执行（调用点已起线程）。
     * 未配置/未启用/缺 embedding 模型时
     * 静默跳过——尽力而为，失败只记日志。
     *
     * <p><b>实现形态</b>：passage 走 {@link KnowledgeService#createFromPassageSync}，
     * 落库与索引语义同步完成，异步性由调用方的后台线程承接——HTTP 响应不受影响。</p>
     */
    public void indexMessageToKb(String userQuery, String assistantAnswer, String messageId,
            String sessionId) {
        // 剥 thinking（<think>…</think>）再入 KB：中间推理会污染检索质量
        String answer = ThinkBlocks.stripThinkBlocks(assistantAnswer == null ? "" : assistantAnswer);
        String query = userQuery == null ? "" : userQuery;

        if (query.strip().isEmpty() && answer.isEmpty()) {
            return;
        }

        ChatHistoryConfig cfg = messageSearch.getChatHistoryConfig();
        if (cfg == null) {
            // 说清为什么跳过——stats 端点只看 Enabled，索引还要求
            // embedding 模型与已创建的 KB（否则运维看到 indexed=0 而无日志可查）
            log.info("Skipping message index for message {}: {}", messageId,
                    describeChatHistorySkip());
            return;
        }

        log.info("Indexing message to chat history KB {}, message ID: {}, session ID: {}",
                cfg.getKnowledgeBaseId(), messageId, sessionId);

        // Q&A 合成一条 passage：同段对语义搜索更友好
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
     * 报出跳过索引时
     * 缺的是哪一项前置（IsConfigured 要求 Enabled + 选中 embedding
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


    // ── 检索（实现见同包 MessageSearch） ─────────────────────────

    /**
     * 聊天历史检索（keyword / vector / hybrid）。
     * 实现已拆至 {@link MessageSearch}，本方法只做薄委托；参数与错误语义不变。
     */
    public MessageSearchResult searchMessages(String query, String mode, int limit,
            List<String> sessionIds) {
        return messageSearch.searchMessages(query, mode, limit, sessionIds);
    }

    /** 显式 owner 的搜索变体（search_conversations 工具用），实现同上。 */
    public MessageSearchResult searchMessages(String query, String mode, int limit,
            List<String> sessionIds, String ownerIdOverride) {
        return messageSearch.searchMessages(query, mode, limit, sessionIds, ownerIdOverride);
    }

    // ── 统计 ────────────────────────────────────────────────────────────────

    /**
     * 未配置 / 未启用 → 全零统计。
     * KB 取不到时 warn + 返回已填的部分（不是错误）。
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

    static long requireTenantId() {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("types.TenantIDContextKey not set in context");
        }
        return tenantId;
    }

    /** 抽答案里全部 resource://<handle> 引用。 */
    static final class ResourceReferences {
        // handle 是 base64url 的 22 字符，字符集含 -/_
        // （缺了会漏掉约一半的引用）。
        private static final java.util.regex.Pattern RESOURCE_REF =
                java.util.regex.Pattern.compile("resource://[A-Za-z0-9_-]{22}");

        /** 引用 handle 的合法字符。 */
        private static boolean isResourceHandleChar(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
        }

        static List<String> scan(String content) {
            List<String> out = new ArrayList<>();
            if (content == null || content.isEmpty()) {
                return out;
            }
            // 更长的 handle 字符连串不是「22 字符 handle 带尾随文本」——那是别的
            // 非法 token，绑截断前缀会挂错文件（边界检查）。
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
