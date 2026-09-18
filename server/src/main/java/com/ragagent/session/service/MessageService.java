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
import com.ragagent.auth.service.TenantService;
import com.ragagent.common.context.TenantContext;
import com.ragagent.knowledge.service.KnowledgeService;
import com.ragagent.session.domain.ChatHistoryKbStats;
import com.ragagent.session.domain.Message;
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
 *   <li>搜：{@code SearchMessages} 的关键词路径 + RRF 融合 + Q&amp;A 补对 + 按
 *       request_id 分组（向量路径见下面的已知差异）；</li>
 *   <li>统计：{@code GetChatHistoryKBStats}。</li>
 * </ul>
 *
 * <h2>已知差异（对照 Go，均不改变 HTTP 契约）</h2>
 * <ol>
 *   <li><b>聊天历史知识清理是同步尽力而为</b>：Go 在 goroutine 里异步做
 *       （{@code context.WithoutCancel}），错误全吞。HTTP 响应不受影响。</li>
 *   <li><b>向量搜索路径未实现</b>：Go 在租户配置了 ChatHistoryConfig 时走
 *       {@code kbService.HybridSearch}（依赖 retrieval/向量检索）——该模块未翻译，
 *       暂恒跳过（等价于"未配置"分支）。未配置时两侧行为一致；配置了才有差异。
 *       TODO(随 retrieval 模块收口)。</li>
 *   <li><b>clarifyReadArtifactVersions 只做短路判定</b>：needsHistory 的完整
 *       澄清逻辑（ClarifyArtifactVersions）随波 1 G6（产物）落地；
 *       无产物的消息两侧都原样返回。</li>
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

    public MessageService(SessionRepository sessionRepository,
                          MessageRepository messageRepository,
                          MessageSuggestionRepository suggestionRepository,
                          KnowledgeService knowledgeService,
                          TenantService tenantService,
                          com.ragagent.knowledge.service.KnowledgeBaseService knowledgeBaseService) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.suggestionRepository = suggestionRepository;
        this.knowledgeService = knowledgeService;
        this.tenantService = tenantService;
        this.knowledgeBaseService = knowledgeBaseService;
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
     * 对照 Go {@code clarifyReadArtifactVersions}（message_artifact_versions.go L13-57）
     * 的短路判定：所有消息都没有产物时原样返回（Go 也是这个开销为零的快路径）。
     * 完整澄清逻辑（需要 ScanResourceReferences + ClarifyArtifactVersions）随 G6 落地。
     */
    private List<Message> clarifyReadArtifactVersions(String sessionId, List<Message> messages) {
        for (Message message : messages) {
            if (message.getArtifacts() != null && !message.getArtifacts().isEmpty()) {
                // TODO(波 1 G6): 对照 Go 走 GetSessionArtifacts + ClarifyArtifactVersions 的版本澄清
                log.warn("Artifact version clarification not implemented yet (session {}), "
                        + "returning messages as-is", sessionId);
                return messages;
            }
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

    // ── 搜索 ────────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code SearchMessages}（L531-617）。
     *
     * <p>搜索范围与列表同构：**按人裁剪**（owner scope），防止搜索框读到同事的私聊
     * （Go 的注释原文，session.go 同款语义）。</p>
     *
     * <p>向量路径的已知差异见类注释第 2 条——未配置聊天历史 KB 时两侧一致
     * （Go 跳过、Java 也跳过），所以 keyword / hybrid 两条路径这里是逐字对照的。</p>
     *
     * @param query      已由 controller 做 SanitizeForLog（Go 在 handler 里做）
     * @param mode       keyword / vector / hybrid（空 → hybrid）
     * @param limit      ≤0 → 20
     * @param sessionIds 可选过滤
     */
    public MessageSearchResult searchMessages(String query, String mode, int limit,
            List<String> sessionIds) {
        long tenantId = requireTenantId();
        String ownerId = SessionOwnerIds.currentSessionOwnerId();

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

        // Step 2：向量搜索（未配置聊天历史 KB → Go 也返回空，行为一致；已配置时 Java 暂跳过）
        List<SearchItem> vectorResults = List.of();

        // Step 3：按模式合并
        List<SearchItem> items;
        if (MODE_KEYWORD.equals(mode)) {
            // ⚠️ keyword 分支不是直接透传：Go 走 convertKeywordResults 赋线性分值
            items = convertKeywordResults(keywordRows);
        } else if (MODE_VECTOR.equals(mode)) {
            items = List.of();
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
     * 排序按融合分降序。Java 向量路径未实现前 vectorResults 恒空，
     * 结果与 Go"未配置 KB 的 hybrid"完全一致。
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
}
