package com.ragagent.memory.service;

import com.ragagent.common.web.JsonMappers;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.service.TenantService;
import com.ragagent.common.web.GoTimeSerializer;
import com.ragagent.memory.MemoryContext;
import com.ragagent.common.settings.MemoryConfig;
import com.ragagent.memory.domain.MemoryDocAffinity;
import com.ragagent.memory.domain.MemoryDocView;
import com.ragagent.memory.domain.MemoryConflictException;
import com.ragagent.memory.domain.MemoryItem;
import com.ragagent.common.settings.MemoryKinds;
import com.ragagent.common.settings.MemoryKeys;
import com.ragagent.memory.domain.MemoryPage;
import com.ragagent.memory.domain.MemoryRender;
import com.ragagent.memory.domain.MemoryScope;
import com.ragagent.memory.domain.MemorySettings;
import com.ragagent.memory.domain.MemorySubject;
import com.ragagent.memory.domain.MemoryText;
import com.ragagent.memory.domain.MemoryTopicStat;
import com.ragagent.memory.domain.MemoryTopicView;
import com.ragagent.memory.mapper.MemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 跨会话长期记忆的写入、召回与记忆管理器（对照 Go
 * {@code internal/application/service/memory/service.go} 全文 +
 * {@code search.go} 的 {@code SearchMemory} / {@code MemoryAvailable} +
 * {@code recall_trace.go} 的 {@code scopeDisableReason} / {@code recallEmptyMeta}）。
 *
 * <h2>本 Java 类与 Go 其他文件的边界</h2>
 * <ul>
 *   <li>向量运算 → {@link MemoryVectorService}（vector.go）</li>
 *   <li>排序与选取 → {@link MemoryRecallSelector}（recall_trace.go + lexical.go）</li>
 *   <li>话题解析 → {@link MemoryTopicResolver}（topic_resolve.go）</li>
 *   <li>后台蒸馏与整仓回顾 → {@link MemoryExtractionService} /
 *       {@link MemoryConsolidationService}（extract.go / consolidate.go）</li>
 * </ul>
 * <p>它们都是同一个包里的兄弟类，靠<b>包级可见性</b>调用本类里的写路径
 * （{@code write*} / {@code rebuildBlock} / {@code enforceCapacity} / {@code observeTopics}）
 * ——Go 里这些是同一个 {@code Service} 上的未导出方法，Java 侧用同一个包保住那个边界，
 * 而不是把它们开成 public 让 handler 也能绕过写路径。</p>
 *
 * <h2>调用方</h2>
 * <p>两个：HTTP handler（下一步）与 chat_pipeline 的 memory_recall。所以这里
 * <b>刻意没有</b>任何 HTTP 关注点（Resource / R 信封 / 状态码）——异常往上抛，
 * 由 handler 层映射。</p>
 */
@Service
public class MemoryService {

    static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    /**
     * 一条被拒绝的消息在这段时间内继续阻止重新推导
     * （对照 Go {@code rejectedMessageWindow}）。
     *
     * <p>要挡住的情形是：用户删掉了某条消息产出记忆之后几分钟，
     * 一次 debounce 的运行又读到同一条消息。过了这个窗口，用户说过的话就重新算数。</p>
     */
    static final Duration REJECTED_MESSAGE_WINDOW = Duration.ofHours(1);

    /** 对照 Go {@code retrievalBackgroundRuneBudget}：到达改写器的背景上限。 */
    static final int RETRIEVAL_BACKGROUND_RUNE_BUDGET = 240;

    /**
     * 读 {@code tenants.memory_config} 用的映射器。
     *
     * <p>必须容忍未知属性：那一列是历史 jsonb，Go 的 {@code json.Unmarshal} 默认忽略
     * 未知字段而 Jackson 默认失败（§7.5 第 6 条）——配置里多一个键就让记忆整体失效
     * 是这里最不该发生的事。</p>
     */
    static final ObjectMapper CONFIG_MAPPER = JsonMappers.lenient()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    final MemoryRepository repo;
    final TenantService tenantService;
    final MemoryVectorService vectorService;
    final MemoryRecallSelector recallSelector;
    final MemoryTopicResolver topicResolver;
    final MemoryModelResolver modelResolver;

    /** 目录管理协作者（构造期装配）。 */
    final MemoryCatalogOps catalogOps;

    /** 检索/亲和协作者（构造期装配）。 */
    final MemoryInsightOps insightOps;

    public MemoryService(MemoryRepository repo,
                         TenantService tenantService,
                         MemoryVectorService vectorService,
                         MemoryRecallSelector recallSelector,
                         MemoryTopicResolver topicResolver,
                         MemoryModelResolver modelResolver) {
        this.repo = repo;
        this.catalogOps = new MemoryCatalogOps(this);
        this.insightOps = new MemoryInsightOps(this);
        this.tenantService = tenantService;
        this.vectorService = vectorService;
        this.recallSelector = recallSelector;
        this.topicResolver = topicResolver;
        this.modelResolver = modelResolver;
    }

    /** 实现随协作者。 */
    boolean topicWasForgotten(MemoryScope scope, String... labels) {
        return catalogOps.topicWasForgotten(scope, labels);
    }

    /** 实现随协作者。 */
    void tombstoneTopic(MemoryScope scope, MemoryTopicStat stat) {
        catalogOps.tombstoneTopic(scope, stat);
    }

    /** 实现随协作者。 */
    List<String> observeTopics(MemoryScope scope, MemoryConfig cfg, String modelId, List<String> topics, MemoryRunBudget budget) {
        return insightOps.observeTopics(scope, cfg, modelId, topics, budget);
    }

    /** 实现随协作者。 */
    void tombstoneEverything(MemoryScope scope) {
        catalogOps.tombstoneEverything(scope);
    }

    /** 实现随协作者。 */
    void renameInterestItem(MemoryScope scope, String oldLabel, String newLabel) {
        catalogOps.renameInterestItem(scope, oldLabel, newLabel);
    }

    /** 实现随协作者。 */
    int topicAliasCount(MemoryScope scope, String key) {
        return catalogOps.topicAliasCount(scope, key);
    }

    /** 实现随协作者。 */
    void invalidateInterestEmbedding(MemoryScope scope, String topic) {
        catalogOps.invalidateInterestEmbedding(scope, topic);
    }

    /** 实现随协作者。 */
    String[] renameTopic(MemoryScope scope, MemoryTopicStat stat, String newLabel, String currentKey) {
        return catalogOps.renameTopic(scope, stat, newLabel, currentKey);
    }

    /** 实现随协作者。 */
    MemoryTopicStat unpromotedTopic(MemoryScope scope, String id) {
        return catalogOps.unpromotedTopic(scope, id);
    }

    /** 实现随协作者。 */
    List<String> topDocumentTitles(MemoryScope scope) {
        return insightOps.topDocumentTitles(scope);
    }

    // ── 记忆管理器/检索侧：实现随协作者（MemoryCatalogOps / MemoryInsightOps） ──

    /** 实现随协作者 {@link MemoryCatalogOps}。 */
        public MemoryPage<MemoryItem> listItems(String status, int limit, int offset) {
        return catalogOps.listItems(status, limit, offset);
    }

    /** 实现随协作者 {@link MemoryCatalogOps}。 */
        public MemoryPage<MemoryTopicView> listTopics(int limit, int offset) {
        return catalogOps.listTopics(limit, offset);
    }

    /** 实现随协作者 {@link MemoryCatalogOps}。 */
        public MemoryItem promoteTopic(String id) {
        return catalogOps.promoteTopic(id);
    }

    /** 实现随协作者 {@link MemoryCatalogOps}。 */
        public void deleteTopic(String id) {
        catalogOps.deleteTopic(id);
    }

    /** 实现随协作者 {@link MemoryCatalogOps}。 */
        public MemoryPage<MemoryDocView> listDocuments(int limit, int offset) {
        return catalogOps.listDocuments(limit, offset);
    }

    /** 实现随协作者 {@link MemoryCatalogOps}。 */
        public void deleteDocument(String id) {
        catalogOps.deleteDocument(id);
    }

    /** 实现随协作者 {@link MemoryCatalogOps}。 */
        public List<String> familiarKnowledgeIds() {
        return catalogOps.familiarKnowledgeIds();
    }

    /** 实现随协作者 {@link MemoryCatalogOps}。 */
        public MemoryItem createItem(String kind, String content, int importance) {
        return catalogOps.createItem(kind, content, importance);
    }

    /** 实现随协作者 {@link MemoryCatalogOps}。 */
        public MemoryItem updateItem(String id, String content, int importance) {
        return catalogOps.updateItem(id, content, importance);
    }

    /** 实现随协作者 {@link MemoryCatalogOps}。 */
        public void deleteItem(String id) {
        catalogOps.deleteItem(id);
    }

    /** 实现随协作者 {@link MemoryCatalogOps}。 */
        public long clear() {
        return catalogOps.clear();
    }

    /** 实现随协作者 {@link MemoryCatalogOps}。 */
        public MemorySettings getSettings() {
        return catalogOps.getSettings();
    }

    /** 实现随协作者 {@link MemoryCatalogOps}。 */
        public void setEnabled(boolean enabled) {
        catalogOps.setEnabled(enabled);
    }

    /** 实现随协作者 {@link MemoryCatalogOps}。 */
        public MemoryItem confirmItem(String id) {
        return catalogOps.confirmItem(id);
    }

    /** 实现随协作者 {@link MemoryCatalogOps}。 */
        public void rejectItem(String id) {
        catalogOps.rejectItem(id);
    }

    /** 实现随协作者 {@link MemoryInsightOps}。 */
        public MemoryRetrievalContext retrievalContextFor() {
        return insightOps.retrievalContextFor();
    }

    /** 实现随协作者 {@link MemoryInsightOps}。 */
        public Map<String, Integer> documentAffinity(List<String> knowledgeIds) {
        return insightOps.documentAffinity(knowledgeIds);
    }

    /** 实现随协作者 {@link MemoryInsightOps}。 */
        public void recordAnswerSources(List<MemoryDocAffinity> refs) {
        insightOps.recordAnswerSources(refs);
    }

    /** 实现随协作者 {@link MemoryInsightOps}。 */
        public List<String> observeQuestionTopics(List<String> topics) {
        return insightOps.observeQuestionTopics(topics);
    }

    /** 实现随协作者 {@link MemoryInsightOps}。 */
        public MemorySearchResult searchMemory(String query, int limit) {
        return insightOps.searchMemory(query, limit);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 开关：工作区配置与三层判定
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 对照 Go {@code workspaceConfig}：读工作区的记忆开关。
     *
     * <p>租户不存在或那一列没配时返回**零值配置**（{@code enabled=false}，
     * 且**不做 normalize**）——也就是"关着"。这个细节有后果：{@code write_mode}
     * 会是空串而不是 {@code explicit_only}，只有 {@link #getSettings} 会去补它。</p>
     */
    MemoryConfig workspaceConfig(long tenantId) {
        Tenant tenant;
        try {
            tenant = tenantService.getTenantById(tenantId);
        } catch (RuntimeException e) {
            log.warn("memory: load workspace config failed: {}", e.toString());
            return new MemoryConfig();
        }
        if (tenant == null || tenant.getMemoryConfig() == null) {
            return new MemoryConfig();
        }
        JsonNode node = tenant.getMemoryConfig();
        if (node.isNull()) {
            return new MemoryConfig();
        }
        MemoryConfig cfg;
        try {
            cfg = CONFIG_MAPPER.treeToValue(node, MemoryConfig.class);
        } catch (Exception e) {
            log.warn("memory: unparsable workspace memory config: {}", e.toString());
            return new MemoryConfig();
        }
        if (cfg == null) {
            return new MemoryConfig();
        }
        cfg.normalize();
        return cfg;
    }

    /**
     * 对照 Go {@code enabledScope}：解析作用域并检查每一层开关。
     *
     * <p>第二个分量在"不许用记忆"时恒为 false，读路径把它当"没有记忆"而不是失败。</p>
     */
    record ScopeState(MemoryScope scope, MemoryConfig cfg, boolean ok) {
    }

    ScopeState enabledScope() {
        MemoryScope scope;
        try {
            scope = MemoryScopes.resolve();
        } catch (MemoryScopeExceptions.NoScope e) {
            return new ScopeState(null, null, false);
        }
        MemoryConfig cfg = workspaceConfig(scope.tenantId());
        if (!cfg.memoryEnabled()) {
            return new ScopeState(scope, cfg, false);
        }
        if (!MemoryContext.allowedForAgent()) {
            return new ScopeState(scope, cfg, false);
        }
        MemorySubject subject;
        try {
            subject = repo.getSubject(scope);
        } catch (RuntimeException e) {
            log.warn("memory: load subject failed: {}", e.toString());
            return new ScopeState(scope, cfg, false);
        }
        // 主体行在第一次写入时创建。它不存在意味着这个人还什么都没存，
        // 对写路径来说仍然算"启用"。
        if (subject != null && !subject.isEnabled()) {
            return new ScopeState(scope, cfg, false);
        }
        return new ScopeState(scope, cfg, true);
    }

    /**
     * 对照 Go {@code scopeDisableReason}：解释这次请求为什么没有记忆。
     * 只在 {@code enabledScope} 返回 false 时调用。
     */
    String scopeDisableReason() {
        MemoryScope scope;
        try {
            scope = MemoryScopes.resolve();
        } catch (MemoryScopeExceptions.NoScope e) {
            return "no_principal";
        }
        MemoryConfig cfg = workspaceConfig(scope.tenantId());
        if (!cfg.memoryEnabled()) {
            return "workspace_disabled";
        }
        if (!MemoryContext.allowedForAgent()) {
            return "agent_disabled";
        }
        MemorySubject subject;
        try {
            subject = repo.getSubject(scope);
        } catch (RuntimeException e) {
            return "subject_load_failed";
        }
        if (subject != null && !subject.isEnabled()) {
            return "user_disabled";
        }
        return "unknown";
    }

    /**
     * 对照 Go {@code MemoryAvailable}：这次请求能不能读记忆。
     *
     * <p>它刻意就是 {@code SearchMemory} 自己用的那个判定，而不是把三个开关再读一遍。
     * 决定"要不要提供一个记忆功能"的调用方与"真用起来时回答"的代码，
     * 绝不能对"记忆开没开"有分歧。</p>
     */
    public boolean memoryAvailable() {
        return enabledScope().ok();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 召回
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 对照 Go {@code Recall}：装配一轮要注入的记忆。
     *
     * <p>它**永不调用模型、永不返回错误**：记忆是增强，任何失败都必须退化成
     * 一个普通回答，而不是一次失败的请求。</p>
     */
    public MemoryRecall recall(String query) {
        MemoryTrace.Span recallSpan = MemoryTrace.start("memory.recall", Map.of(
                "query", MemoryTrace.truncateRunes(query, MemoryRecallSelector.recallQueryPreviewRunes())));

        ScopeState state = enabledScope();
        if (!state.ok()) {
            String reason = scopeDisableReason();
            log.info("memory: recall skipped ({})", reason);
            Map<String, Object> disabled = new LinkedHashMap<>();
            disabled.put("outcome", "disabled");
            disabled.put("reason", reason);
            recallSpan.finish(MemoryTrace.summarizeMemoryRecallOutput(disabled, null), null, null);
            return MemoryRecall.EMPTY;
        }
        MemoryScope scope = state.scope();
        MemoryConfig cfg = state.cfg();

        MemorySubject subject;
        try {
            subject = repo.getSubject(scope);
        } catch (RuntimeException e) {
            subject = null;
            String reason = "subject_load_failed";
            log.warn("memory: load subject for recall failed: {}", e.toString());
            finishSubjectLoadFailure(recallSpan, reason, scope);
            return MemoryRecall.EMPTY;
        }
        if (subject == null) {
            finishSubjectLoadFailure(recallSpan, "no_subject", scope);
            return MemoryRecall.EMPTY;
        }

        List<MemoryItem> residentItems;
        try {
            residentItems = repo.listActiveResident(scope, 60);
        } catch (RuntimeException e) {
            log.warn("memory: load resident items failed: {}", e.toString());
            residentItems = null;
        }
        MemoryRecallSelector.Split split = MemoryRecallSelector.splitResidentInterests(residentItems);
        List<MemoryItem> standing = split.others();
        List<MemoryItem> interests = split.interests();
        MemoryRecallSelector.Selected picked = MemoryRecallSelector.selectResidentInterests(
                query, interests, MemoryKinds.RESIDENT_INTEREST_MAX_ITEMS);
        List<MemoryItem> selectedInterests = picked.selected();
        List<MemoryItem> relevantInterests = picked.relevant();
        List<MemoryItem> blockItems = new ArrayList<>(standing);
        if (selectedInterests != null) {
            blockItems.addAll(selectedInterests);
        }

        // 从条目渲染，而不是用 subject.BlockText。缓存块在这里省不下任何东西——
        // 条目本来就已经加载了——而信它意味着任何改变"什么该进块"的变化
        // （一次写失败、一个新的常驻种类）都要等到用户下次写入才可见。
        // 缓存只是加载失败时的兜底。
        String block = MemoryRender.renderMemoryBlock(blockItems);
        if (block.isEmpty()) {
            block = subject.getBlockText();
        }

        List<MemoryItem> situational;
        try {
            situational = repo.listActiveByKinds(scope,
                    List.of(MemoryKinds.KIND_FACT, MemoryKinds.KIND_TASK),
                    MemoryRecallSelector.lexicalPoolSize(cfg));
        } catch (RuntimeException e) {
            log.warn("memory: load situational items failed: {}", e.toString());
            situational = null;
        }
        // 常驻条目已经在块里了；再匹配一次会把它们印两遍。
        Set<String> resident = new HashSet<>();
        if (residentItems != null) {
            for (MemoryItem item : residentItems) {
                if (item != null) {
                    resident.add(item.getId());
                }
            }
        }
        List<MemoryItem> candidates = new ArrayList<>();
        if (situational != null) {
            for (MemoryItem item : situational) {
                if (item != null && !resident.contains(item.getId())) {
                    candidates.add(item);
                }
            }
        }

        log.info("memory: recall start subject={} resident={} candidates={} block_runes={}",
                scope.subjectId(), residentItems == null ? 0 : residentItems.size(),
                candidates.size(), MemoryKeys.runeLength(block));

        MemoryRecallSelector.Outcome outcome = recallSelector.selectRecallWithTrace(scope, cfg,
                new MemoryRecallSelector.Selection(query, candidates,
                        List.of(MemoryKinds.KIND_FACT, MemoryKinds.KIND_TASK),
                        resident, MemoryKinds.RECALL_MAX_ITEMS, MemoryKinds.RECALL_RUNE_BUDGET));
        List<MemoryItem> matched = outcome.matched();
        MemoryRecallSelector.RankingTrace trace = outcome.trace();

        String prompt = MemoryRender.wrapMemoryForPrompt(block, MemoryRender.renderMemoryRecall(matched));
        if (prompt.isEmpty()) {
            Map<String, Object> emptyMeta = recallEmptyMeta(scope,
                    residentItems == null ? 0 : residentItems.size(), candidates.size(), trace);
            emptyMeta.put("block_runes", MemoryKeys.runeLength(block));
            log.info("memory: recall empty subject={} resident={} candidates={} mode={}",
                    scope.subjectId(), residentItems == null ? 0 : residentItems.size(),
                    candidates.size(), trace.mode);
            recallSpan.finish(MemoryTrace.summarizeMemoryRecallOutput(emptyMeta, null),
                    Map.of("tenant_id", scope.tenantId()), null);
            return MemoryRecall.EMPTY;
        }

        // 注入的东西与上报的东西刻意不是同一个集合。一个仅仅因为上限还有位置而搭车的兴趣
        // 是背景，不是这个问题拉进来的东西；上报它会把一条与回答无关的记忆
        // 每一轮都放到聊天时间线上。
        //
        // 块也是从一份被截断的列表渲染出来的，所以上报"真的放进去的"那些，
        // 而不是加载到的全部。
        List<MemoryItem> used = new ArrayList<>(residentItemsWithinBlock(standing, block));
        used.addAll(residentItemsWithinBlock(relevantInterests, block));
        used.addAll(matched);
        touchAsync(scope, used);

        log.info("memory: recall done subject={} used={} matched={} outside_pool={} "
                        + "interest_injected={} interest_relevant={} mode={} prompt_runes={}",
                scope.subjectId(), used.size(), matched.size(), trace.vectorOutsidePool,
                selectedInterests == null ? 0 : selectedInterests.size(),
                relevantInterests == null ? 0 : relevantInterests.size(),
                trace.mode, MemoryKeys.runeLength(prompt));

        Map<String, Object> okMeta = new LinkedHashMap<>();
        okMeta.put("outcome", "ok");
        okMeta.put("subject_id", scope.subjectId());
        okMeta.put("resident_count", residentItems == null ? 0 : residentItems.size());
        okMeta.put("block_runes", MemoryKeys.runeLength(block));
        okMeta.put("candidate_count", candidates.size());
        okMeta.put("lexical_hits", trace.lexicalHits);
        okMeta.put("vector_hits", trace.vectorHits);
        okMeta.put("vector_outside", trace.vectorOutsidePool);
        okMeta.put("vector_skip", trace.vectorSkipReason);
        okMeta.put("ranking_mode", trace.mode);
        okMeta.put("fused_candidates", trace.fusedCandidates);
        okMeta.put("matched_count", matched.size());
        okMeta.put("interest_total", interests == null ? 0 : interests.size());
        okMeta.put("interest_injected", selectedInterests == null ? 0 : selectedInterests.size());
        okMeta.put("interest_relevant", relevantInterests == null ? 0 : relevantInterests.size());
        okMeta.put("used_count", used.size());
        okMeta.put("prompt_runes", MemoryKeys.runeLength(prompt));
        recallSpan.finish(MemoryTrace.summarizeMemoryRecallOutput(okMeta, used),
                Map.of("tenant_id", scope.tenantId()), null);

        return new MemoryRecall(prompt, used);
    }

    /** 对照 Go 里 recall 的两处"主体取不到"分支（它们只差 reason 文案）。 */
    private void finishSubjectLoadFailure(MemoryTrace.Span span, String reason, MemoryScope scope) {
        log.info("memory: recall skipped ({})", reason);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("outcome", "empty");
        meta.put("reason", reason);
        meta.put("subject_id", scope.subjectId());
        span.finish(MemoryTrace.summarizeMemoryRecallOutput(meta, null),
                Map.of("tenant_id", scope.tenantId()), null);
    }

    /**
     * 对照 Go {@code recallEmptyMeta}：解释 {@code Recall} 为什么没产出提示词。
     */
    private Map<String, Object> recallEmptyMeta(MemoryScope scope, int residentCount,
                                                int candidateCount,
                                                MemoryRecallSelector.RankingTrace trace) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("outcome", "empty");
        meta.put("reason", "no_injectable_memories");
        meta.put("subject_id", scope.subjectId());
        meta.put("resident_count", residentCount);
        meta.put("candidate_count", candidateCount);
        meta.put("lexical_hits", trace.lexicalHits);
        meta.put("vector_hits", trace.vectorHits);
        meta.put("vector_outside", trace.vectorOutsidePool);
        meta.put("vector_skip", trace.vectorSkipReason);
        meta.put("ranking_mode", trace.mode);
        meta.put("fused_candidates", trace.fusedCandidates);
        return meta;
    }

    /**
     * 对照 Go {@code residentItemsWithinBlock}：筛出内容真的在块里活下来的那些条目。
     */
    static List<MemoryItem> residentItemsWithinBlock(List<MemoryItem> items, String block) {
        if (block == null || block.isEmpty()) {
            return List.of();
        }
        List<MemoryItem> within = new ArrayList<>(items == null ? 0 : items.size());
        if (items == null) {
            return within;
        }
        for (MemoryItem item : items) {
            if (item != null && block.contains(MemoryText.sanitizeMemoryContent(item.getContent()))) {
                within.add(item);
            }
        }
        return within;
    }

    /**
     * 对照 Go {@code touchAsync}：记下使用情况，但不给请求的关键路径增加一次写。
     *
     * <p>{@code WithoutCancel} 让它在 HTTP handler 返回之后仍然活着；
     * Java 侧是虚拟线程（无 context）。注意它<b>只</b>用显式传进去的 scope，
     * 不读 {@code TenantContext}——跨线程读 ThreadLocal 正是 §5 禁止的。</p>
     */
    void touchAsync(MemoryScope scope, List<MemoryItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<>(items.size());
        for (MemoryItem item : items) {
            if (item != null) {
                ids.add(item.getId());
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        Thread.ofVirtual().name("memory-touch").start(() -> {
            try {
                repo.touchUsed(scope, ids);
            } catch (RuntimeException e) {
                log.warn("memory: touch used failed: {}", e.toString());
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 写入路径（唯一的插入口）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 对照 Go {@code Remember}：存下一条陈述，并与同一主题上已知的东西解矛盾。
     */
    public MemoryItem remember(MemoryItem item) {
        ScopeState state = enabledScope();
        if (!state.ok()) {
            throw new MemoryScopeExceptions.Disabled();
        }
        return write(state.scope(), state.cfg(), item);
    }

    /**
     * 对照 Go {@code write}：**唯一的插入路径**。
     *
     * <p>"记住这个"这条显式路由与后台蒸馏任务都走它，所以清洗、矛盾消解、块重建
     * 与容量执行都不可能被一个新来的调用方绕过去。</p>
     */
    MemoryItem write(MemoryScope scope, MemoryConfig cfg, MemoryItem item) {
        return writeReplacing(scope, cfg, item, "");
    }

    /**
     * 对照 Go {@code writeReplacing}：写入并可选地"替换"一条已有条目。
     *
     * <p>顺序有语义，逐段照抄：清洗 → 脱敏 → 校验 kind → 墓碑（两查）→ 确保主体存在
     * → 找冲突 → 包含去重 → 构造条目 → save → 容量 → 重建块 → 存向量。</p>
     */
    MemoryItem writeReplacing(MemoryScope scope, MemoryConfig cfg, MemoryItem item, String targetId) {
        String content = MemoryText.sanitizeMemoryContent(item.getContent());
        if (content.isEmpty()) {
            throw new MemoryScopeExceptions.EmptyContent();
        }
        // 在任何东西看这条陈述之前先脱敏。记忆会被注入到之后每一轮的系统提示词里，
        // 所以一条到达存储的凭据不只是被留存，而是被反复发给模型。
        MemoryText.Redaction redaction = MemoryText.redactSensitive(content);
        if (redaction.changed()) {
            if (MemoryText.isMostlyRedacted(redaction.content())) {
                log.info("memory: dropped a statement that was mostly sensitive material");
                throw new MemoryScopeExceptions.SensitiveContent();
            }
            log.info("memory: redacted sensitive material before storing");
            content = MemoryText.sanitizeMemoryContent(redaction.content());
        }
        if (!MemoryKinds.isValid(item.getKind())) {
            item.setKind(MemoryKinds.KIND_FACT);
        }

        // 用户刻意忘掉的东西，不能在蒸馏下次读到那条消息时回来。两次检查，因为重新推导出来的
        // 陈述通常措辞略有不同、哈希对不上：精确指纹，以及"它来自的那条消息曾经产出过
        // 一条被用户拒绝的记忆"。
        boolean forgotten = repo.hasTombstone(scope, MemoryText.fingerprint(content));
        if (!forgotten && !item.getSourceMessageId().isEmpty()
                && MemoryKinds.ORIGIN_EXTRACTED.equals(item.getOrigin())) {
            // 只有后台路径被这样拦。显式的"记住这个"是用户在**再次**要求，永远该赢。
            forgotten = repo.hasTombstoneForMessage(scope, item.getSourceMessageId(),
                    REJECTED_MESSAGE_WINDOW);
        }
        if (forgotten) {
            log.info("memory: skipped a statement the user previously deleted");
            throw new MemoryScopeExceptions.PreviouslyForgotten();
        }
        repo.ensureSubject(scope);

        String topic = MemoryText.sanitizeMemoryTopic(item.getTopic());
        String normalizedKey = MemoryKeys.itemKey(topic, content);
        MemoryItem existing;
        if (targetId != null && !targetId.isEmpty()) {
            existing = repo.getItem(scope, targetId);
            if (existing == null) {
                throw new MemoryConflictException();
            }
        } else {
            existing = repo.findActiveByKey(scope, normalizedKey);
        }
        if (existing != null && MemoryKinds.STATUS_ACTIVE.equals(existing.getStatus())
                && MemoryText.sanitizeMemoryContent(existing.getContent()).equals(content)) {
            // 同一个主题上的同一句话：什么都没变，所以保留原来的时间戳，
            // 而不是每一轮都把这一行翻搅一遍。
            return existing;
        }
        if (existing == null) {
            // 同一个事实常常到两次：一次因为用户说了"记住…"，又一次来自后台蒸馏，
            // 措辞略有不同（"我们的生产库是 X" vs "生产库是 X"）。它们拿到不同的主题
            // key，所以只靠 key 匹配会让两条都进来，用户就看到自己的记忆重复了。
            ContainedDuplicate duplicate = findContainedDuplicate(scope, item.getKind(), content);
            MemoryItem candidate = duplicate.item();
            if (candidate != null && !duplicate.longer()
                    && (MemoryKinds.STATUS_ACTIVE.equals(candidate.getStatus())
                    || MemoryKinds.STATUS_PENDING.equals(statusForWrite(item)))) {
                return candidate;
            }
            // 新陈述包住了旧的，让它取代。
            existing = candidate;
        }

        MemoryItem stored = new MemoryItem();
        stored.setId(UUID.randomUUID().toString());
        stored.setTenantId(scope.tenantId());
        stored.setSubjectId(scope.subjectId());
        stored.setKind(item.getKind());
        stored.setContent(content);
        stored.setTopic(topic);
        stored.setNormalizedKey(normalizedKey);
        stored.setImportance(MemoryText.clampImportance(item.getImportance()));
        stored.setOrigin(item.getOrigin());
        stored.setStatus(statusForWrite(item));
        stored.setSourceSessionId(item.getSourceSessionId());
        stored.setSourceMessageId(item.getSourceMessageId());
        stored.setValidFrom(OffsetDateTime.now());
        stored.setExpiresAt(item.getExpiresAt());
        if (stored.getOrigin().isEmpty()) {
            stored.setOrigin(MemoryKinds.ORIGIN_EXTRACTED);
        }
        if (existing != null) {
            targetId = existing.getId();
        }
        repo.saveItem(scope, stored, targetId);

        enforceCapacity(scope, cfg);
        rebuildBlock(scope);
        // 一条没有向量的记忆对语义召回是不可见的，所以这跑在**每一次**写入上。
        // 尽力而为：嵌入失败不能让写入失败，补扫会捡起漏掉的。
        vectorService.storeItemEmbedding(scope, cfg, stored);
        return stored;
    }

    /** {@link #findContainedDuplicate} 的结果（对照 Go 的双返回值）。 */
    record ContainedDuplicate(MemoryItem item, boolean longer) {
    }

    /**
     * 对照 Go {@code findContainedDuplicate}：在同 kind 的**活着**的记忆里找一条，
     * 其陈述包含（或被包含于）新来的这条。
     *
     * <p>包含是刻意的全部规则。它便宜、对一个读自己记忆列表的用户可解释，
     * 而且它不会把两条仅仅同主题的陈述合并——只合并"短的那条没有说出长的没说的东西"
     * 这种情形。返回的 bool 报告新陈述是不是两者中更长的那条。</p>
     */
    private ContainedDuplicate findContainedDuplicate(MemoryScope scope, String kind, String content) {
        List<MemoryItem> candidates = repo.listLive(scope, kind, 200);
        String normalized = MemoryText.normalizeMemoryForMatch(content);
        if (normalized.isEmpty()) {
            return new ContainedDuplicate(null, false);
        }
        if (candidates == null) {
            return new ContainedDuplicate(null, false);
        }
        for (MemoryItem candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String existing = MemoryText.normalizeMemoryForMatch(candidate.getContent());
            if (existing.isEmpty()) {
                continue;
            }
            if (existing.contains(normalized)) {
                return new ContainedDuplicate(candidate, false);
            }
            if (normalized.contains(existing)) {
                return new ContainedDuplicate(candidate, true);
            }
        }
        return new ContainedDuplicate(null, false);
    }

    /**
     * 对照 Go {@code statusForWrite}：一条记忆是立刻生效还是等用户确认。
     *
     * <p>用户**说**的东西立刻生效。系统**猜**的关于他的东西（他的角色、他的领域，
     * 从提问里推断出来的）则提出来等他确认。推断既是价值所在也是伤害所在：
     * 一个被悄悄当成事实的错误猜测，是记忆功能彻底失去信任的方式；
     * 而且与 ChatGPT 的后台层不同，这一层始终可审计。</p>
     */
    static String statusForWrite(MemoryItem item) {
        if (item.isInferred()
                && !MemoryKinds.ORIGIN_EXPLICIT.equals(item.getOrigin())
                && !MemoryKinds.ORIGIN_MANUAL.equals(item.getOrigin())) {
            return MemoryKinds.STATUS_PENDING;
        }
        return MemoryKinds.STATUS_ACTIVE;
    }

    /**
     * 对照 Go {@code enforceCapacity}：主体超过上限后归档排名最低的条目。
     * 这是系统里**唯一**的自动遗忘。
     */
    void enforceCapacity(MemoryScope scope, MemoryConfig cfg) {
        int maxItems = cfg.effectiveMaxItems();
        long count;
        try {
            count = repo.countActive(scope);
        } catch (RuntimeException e) {
            log.warn("memory: count active failed: {}", e.toString());
            return;
        }
        if (count <= maxItems) {
            return;
        }
        long archived;
        try {
            archived = repo.archiveLowestRanked(scope, maxItems);
        } catch (RuntimeException e) {
            log.warn("memory: archive overflow failed: {}", e.toString());
            return;
        }
        log.info("memory: archived {} items over the {} cap", archived, maxItems);
    }

    /**
     * 对照 Go {@code rebuildBlock}：重新渲染常驻块，让读路径始终只是一次主键查找。
     * 每次变更之后都会调用。
     */
    void rebuildBlock(MemoryScope scope) {
        List<MemoryItem> items;
        try {
            items = repo.listActiveResident(scope, 60);
        } catch (RuntimeException e) {
            log.warn("memory: rebuild block load failed: {}", e.toString());
            return;
        }
        long count;
        try {
            count = repo.countActive(scope);
        } catch (RuntimeException e) {
            log.warn("memory: rebuild block count failed: {}", e.toString());
            return;
        }
        String block = MemoryRender.renderMemoryBlock(items);
        try {
            repo.updateSubjectBlock(scope, block, (int) count);
        } catch (RuntimeException e) {
            log.warn("memory: rebuild block store failed: {}", e.toString());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 记忆管理器（列表 / 主题 / 文档 / CRUD）
    // ═══════════════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════════════
    // 检索条件化
    // ═══════════════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════════════
    // 按需查找（search.go）
    // ═══════════════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════════════
    // 抽取模型的解析（extract.go 的 L800-853，供抽取/归并/话题三处共用）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 对照 Go {@code extractionModelID}：解析记忆管线该用哪个模型。
     *
     * <p>设置界面说"抽取模型留空 = 用对话本身用的那个模型"，所以留空必须**解析出**
     * 一个模型，而不是关掉什么。本包里的每一个调用方都要经过这里：当只有抽取调用
     * 应用了回落时，话题解析器会在每一个没挑过模型的工作区上悄悄失去它的模型层——
     * 而默认情况下**所有**工作区都没挑过。</p>
     */
    String extractionModelId(MemoryConfig cfg, MemoryExtractPayload payload) {
        if (cfg != null && !cfg.getExtractModelId().isEmpty()) {
            return cfg.getExtractModelId();
        }
        if (payload != null && !payload.chatModelId().isEmpty()) {
            return payload.chatModelId();
        }
        // 产生这个任务的那一轮并不总是带着回答它所使用的模型——真正的模型是在 QA 管线里
        // 解析的，而且没有被写回消息。回落到工作区自己的问答模型，能把文档承诺的
        // "留空 = 用对话模型"从"记忆悄悄什么都不做"里救回来。
        return workspaceChatModelId();
    }

    /**
     * 对照 Go {@code workspaceChatModelID}：给这个工作区挑一个可用的问答模型。
     *
     * <p>选择被记进日志，因为它是一个猜测：没有显式配置抽取模型时，没有任何记录说明
     * 这个工作区希望后台工作用哪个模型，而**悄悄**挑一个只有在事后可见时才可接受。</p>
     */
    String workspaceChatModelId() {
        List<com.ragagent.model.domain.Model> models;
        try {
            models = modelResolver.listModels();
        } catch (RuntimeException e) {
            log.warn("memory: list models for extraction fallback failed: {}", e.toString());
            return "";
        }
        if (models == null) {
            return "";
        }
        for (com.ragagent.model.domain.Model model : models) {
            if (model == null || !"KnowledgeQA".equals(model.getType())) {
                continue;
            }
            if (!model.getStatus().isEmpty() && !"active".equals(model.getStatus())) {
                continue;
            }
            log.info("memory: no extraction model configured, using workspace model {}", model.getId());
            return model.getId();
        }
        return "";
    }

    // ── 供兄弟类使用的小访问器 ───────────────────────────────────────────

    MemoryRepository repo() {
        return repo;
    }

    MemoryVectorService vectorService() {
        return vectorService;
    }

    MemoryModelResolver modelResolver() {
        return modelResolver;
    }

    /** 对照 Go {@code time.Now()} 的零星用法（抽取段的截止时间等）。 */
    static OffsetDateTime now() {
        return OffsetDateTime.now();
    }

    /** 对照 Go 的 {@code t.IsZero()} 判定，供兄弟类统一口径。 */
    static boolean isZeroTime(OffsetDateTime t) {
        return GoTimeSerializer.isGoZero(t);
    }

}
