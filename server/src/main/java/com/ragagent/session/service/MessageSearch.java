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

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.tenantconfig.ChatHistoryConfig;
import com.ragagent.auth.domain.tenantconfig.RetrievalConfig;
import com.ragagent.auth.service.TenantService;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.pipeline.SearchParams;
import com.ragagent.common.retrieval.SearchResult;
import com.ragagent.model.service.ModelRuntimeFactory;
import com.ragagent.rerank.RankResult;
import com.ragagent.rerank.Reranker;
import com.ragagent.retrieval.HybridSearchService;
import com.ragagent.session.domain.MessageSearchGroupItem;
import com.ragagent.session.domain.MessageSearchResult;
import com.ragagent.session.domain.MessageWithSession;
import com.ragagent.session.domain.SessionOwnerIds;
import com.ragagent.session.mapper.MessageRepository;


/**
 * {@code MessageService} 的**聊天历史检索切片**（§14 步骤 2）：messages 表的关键词 / KB 向量 /
 * 混合检索 + RRF 融合 + 会话归属过滤 + 补对 + 按 request_id 分组。
 *
 * <p>对外入口仍是门面的 {@code searchMessages} 两个重载（薄委托都指向这里），返回类型与
 * 错误语义不变。为什么单独一类：检索簇自成一条管线（配置 → 关键词与向量两路 → RRF →
 * 归属过滤 → 补对 → 分组），与门面的消息 CRUD / 附件 / 入 KB / 统计无交集；只依赖
 * {@code messageRepository} 与检索面（{@code tenantService} / {@code hybridSearchService} /
 * {@code modelRuntimeFactory}）。共用的 {@code requireTenantId} 与检索模式词表
 * （{@code MODE_*}）留在门面，本类按类名引用；{@code getChatHistoryConfig} 反过来被门面的
 * 入 KB 路径（{@code indexMessageToKb}）共用，故包内可见。</p>
 */
final class MessageSearch {

    private static final Logger log = LoggerFactory.getLogger(MessageSearch.class);

    private static final double RRF_K = 60.0;

    private final MessageRepository messageRepository;
    private final TenantService tenantService;
    /** 聊天历史 KB 的向量检索执行面（对照 Go kbService.HybridSearch 的 HybridSearch 段）。 */
    private final HybridSearchService hybridSearchService;
    /** 对照 Go modelService.GetRerankModel（rerankResults 的重排模型工厂）。 */
    private final ModelRuntimeFactory modelRuntimeFactory;

    MessageSearch(MessageRepository messageRepository,
                  TenantService tenantService,
                  HybridSearchService hybridSearchService,
                  ModelRuntimeFactory modelRuntimeFactory) {
        this.messageRepository = messageRepository;
        this.tenantService = tenantService;
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
    MessageSearchResult searchMessages(String query, String mode, int limit,
            List<String> sessionIds) {
        return searchMessages(query, mode, limit, sessionIds, null);
    }

    /**
     * 显式 owner 的搜索变体（对照 Go {@code MessageSearchParams.OwnerID}——
     * search_conversations 工具在引擎装配期捕获 owner 后按入参传入，不读线程上下文）。
     */
    MessageSearchResult searchMessages(String query, String mode, int limit,
            List<String> sessionIds, String ownerIdOverride) {
        long tenantId = MessageService.requireTenantId();
        String ownerId = ownerIdOverride == null || ownerIdOverride.isEmpty()
                ? SessionOwnerIds.currentSessionOwnerId()
                : ownerIdOverride;

        if (mode == null || mode.isEmpty()) {
            mode = MessageService.MODE_HYBRID;
        }
        if (limit <= 0) {
            limit = 20;
        }

        // Step 1：关键词搜索（PG ILIKE；limit*3 照抄 Go）
        List<MessageWithSession> keywordRows = List.of();
        if (MessageService.MODE_KEYWORD.equals(mode) || MessageService.MODE_HYBRID.equals(mode)) {
            keywordRows = messageRepository.searchMessagesByKeyword(
                    tenantId, ownerId, query, sessionIds, limit * 3);
        }

        // Step 2：向量搜索（经聊天历史 KB；未配置 → 空，Go 的 nil, nil 分支）
        List<SearchItem> vectorResults = List.of();
        if (MessageService.MODE_VECTOR.equals(mode) || MessageService.MODE_HYBRID.equals(mode)) {
            try {
                vectorResults = vectorSearchViaKb(query, sessionIds);
                log.info("Vector search found {} results", vectorResults.size());
            } catch (RuntimeException e) {
                // Go：两种模式都先 Warnf，vector 模式再上抛（handler 500）、
                // hybrid 模式吞掉降级 keyword-only
                log.warn("Vector search via KB failed, falling back to keyword-only: {}",
                        e.toString());
                if (MessageService.MODE_VECTOR.equals(mode)) {
                    throw e;
                }
            }
        }

        // Step 3：按模式合并
        List<SearchItem> items;
        if (MessageService.MODE_KEYWORD.equals(mode)) {
            // ⚠️ keyword 分支不是直接透传：Go 走 convertKeywordResults 赋线性分值
            items = convertKeywordResults(keywordRows);
        } else if (MessageService.MODE_VECTOR.equals(mode)) {
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
     * （{@link MessageService#getChatHistoryKbStats}）经 TenantService 重查同一行。</p>
     */
    ChatHistoryConfig getChatHistoryConfig() {
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
        rc.setEmbeddingTopK(node.path("embeddingTopK").asInt(0));
        rc.setVectorThreshold(node.path("vectorThreshold").asDouble(0));
        rc.setRerankTopK(node.path("rerankTopK").asInt(0));
        rc.setRerankThreshold(node.path("rerankThreshold").asDouble(0));
        rc.setRerankModelId(node.path("rerankModelId").asText(""));
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
}
