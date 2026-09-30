package com.ragagent.memory.service;

import com.ragagent.common.web.JsonMappers;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
import com.ragagent.memory.domain.MemoryConfig;
import com.ragagent.memory.domain.MemoryDocAffinity;
import com.ragagent.memory.domain.MemoryDocView;
import com.ragagent.memory.domain.MemoryConflictException;
import com.ragagent.memory.domain.MemoryItem;
import com.ragagent.memory.domain.MemoryKinds;
import com.ragagent.memory.domain.MemoryKeys;
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

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

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
    private static final ObjectMapper CONFIG_MAPPER = JsonMappers.lenient()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final MemoryRepository repo;
    private final TenantService tenantService;
    private final MemoryVectorService vectorService;
    private final MemoryRecallSelector recallSelector;
    private final MemoryTopicResolver topicResolver;
    private final MemoryModelResolver modelResolver;

    public MemoryService(MemoryRepository repo,
                         TenantService tenantService,
                         MemoryVectorService vectorService,
                         MemoryRecallSelector recallSelector,
                         MemoryTopicResolver topicResolver,
                         MemoryModelResolver modelResolver) {
        this.repo = repo;
        this.tenantService = tenantService;
        this.vectorService = vectorService;
        this.recallSelector = recallSelector;
        this.topicResolver = topicResolver;
        this.modelResolver = modelResolver;
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

    /** 对照 Go {@code ListItems}：记忆管理器的条目列表。 */
    public MemoryRepository.Page<MemoryItem> listItems(String status, int limit, int offset) {
        MemoryScope scope = MemoryScopes.resolve();
        return repo.listItems(scope, status, limit, offset);
    }

    /** 对照 Go {@code ListTopics}：已计数但还没被提升的主体的视图。 */
    public MemoryRepository.Page<MemoryTopicView> listTopics(int limit, int offset) {
        MemoryScope scope = MemoryScopes.resolve();
        MemoryRepository.Page<MemoryTopicStat> page = repo.listUnpromotedTopics(scope, limit, offset);
        int threshold = workspaceConfig(scope.tenantId()).effectiveInterestThreshold();
        List<MemoryTopicView> views = new ArrayList<>(page.items().size());
        for (MemoryTopicStat stat : page.items()) {
            MemoryTopicView view = MemoryTopicView.fromStat(stat, threshold);
            if (view != null) {
                views.add(view);
            }
        }
        return new MemoryRepository.Page<>(views, page.total());
    }

    /**
     * 对照 Go {@code unpromotedTopic}：取一条还没被提升的主题，否则
     * {@link MemoryScopeExceptions.ItemNotFound}。
     *
     * <p>"已经提升过"与"不存在"刻意回同一个错误——与 {@code ErrItemNotFound} 的
     * 那条注释同一个理由。</p>
     */
    private MemoryTopicStat unpromotedTopic(MemoryScope scope, String id) {
        MemoryTopicStat stat = repo.topicById(scope, id);
        if (stat == null || stat.getPromotedAt() != null) {
            throw new MemoryScopeExceptions.ItemNotFound();
        }
        return stat;
    }

    /** 对照 Go {@code PromoteTopic}：把一个被计数的主体立刻变成兴趣，不再等剩余命中数。 */
    public MemoryItem promoteTopic(String id) {
        ScopeState state = enabledScope();
        if (!state.ok()) {
            throw new MemoryScopeExceptions.Disabled();
        }
        MemoryTopicStat stat = unpromotedTopic(state.scope(), id);
        MemoryItem item = new MemoryItem();
        item.setKind(MemoryKinds.KIND_INTEREST);
        item.setTopic(stat.getTopic());
        item.setContent(stat.getTopic());
        item.setImportance(3);
        item.setOrigin(MemoryKinds.ORIGIN_MANUAL);
        MemoryItem created = write(state.scope(), state.cfg(), item);
        try {
            repo.markTopicPromoted(state.scope(), stat.getNormalizedKey());
        } catch (RuntimeException e) {
            log.warn("memory: mark topic promoted failed: {}", e.toString());
        }
        return created;
    }

    /**
     * 对照 Go {@code DeleteTopic}：停止跟踪一个主体，并记住这次拒绝，
     * 免得自动提升又把这个标签带回来。
     */
    public void deleteTopic(String id) {
        MemoryScope scope = MemoryScopes.resolve();
        MemoryTopicStat stat = unpromotedTopic(scope, id);
        tombstoneTopic(scope, stat);
        repo.deleteTopic(scope, id);
    }

    /** 对照 Go {@code ListDocuments}：当作习惯被引用过足够多次的文档。 */
    public MemoryRepository.Page<MemoryDocView> listDocuments(int limit, int offset) {
        MemoryScope scope = MemoryScopes.resolve();
        MemoryRepository.Page<MemoryDocAffinity> page = repo.listFamiliarDocs(
                scope, MemoryConfig.MEMORY_DOC_AFFINITY_MIN_HITS, limit, offset);
        List<MemoryDocView> views = new ArrayList<>(page.items().size());
        for (MemoryDocAffinity row : page.items()) {
            MemoryDocView view = MemoryDocView.fromAffinity(row);
            if (view != null) {
                views.add(view);
            }
        }
        return new MemoryRepository.Page<>(views, page.total());
    }

    /** 对照 Go {@code DeleteDocument}：不再把某个文档当个人检索信号。 */
    public void deleteDocument(String id) {
        MemoryScope scope = MemoryScopes.resolve();
        if (repo.docAffinityById(scope, id) == null) {
            throw new MemoryScopeExceptions.ItemNotFound();
        }
        repo.deleteDocAffinity(scope, id);
    }

    /**
     * 对照 Go {@code FamiliarKnowledgeIDs}：这个人反复引用的文档 id。
     *
     * <p>任何失败都回空，好让调用方无条件使用它。</p>
     */
    public List<String> familiarKnowledgeIds() {
        MemoryScope scope;
        try {
            scope = MemoryScopes.resolve();
        } catch (MemoryScopeExceptions.NoScope e) {
            return null;
        }
        List<MemoryDocAffinity> rows;
        try {
            rows = repo.topDocAffinity(scope, 200);
        } catch (RuntimeException e) {
            log.warn("memory: load familiar documents failed: {}", e.toString());
            return null;
        }
        if (rows == null) {
            return null;
        }
        List<String> ids = new ArrayList<>(rows.size());
        for (MemoryDocAffinity row : rows) {
            if (row == null || row.getKnowledgeId().isEmpty()
                    || row.getHits() < MemoryConfig.MEMORY_DOC_AFFINITY_MIN_HITS) {
                continue;
            }
            ids.add(row.getKnowledgeId());
        }
        return ids;
    }

    /**
     * 对照 Go {@code topicWasForgotten}：这个主题（或它的任一个别名）被刻意忘掉过吗。
     *
     * <p>查墓碑失败时**继续**（Go 的 {@code continue}），而不是当成"没忘过"就返回——
     * 它只是跳过那一个标签。</p>
     */
    boolean topicWasForgotten(MemoryScope scope, String... labels) {
        Set<String> seen = new LinkedHashSet<>();
        for (String label : labels) {
            String fingerprint = MemoryText.fingerprint(MemoryText.sanitizeMemoryContent(label));
            if (fingerprint.isEmpty()) {
                continue;
            }
            if (!seen.add(fingerprint)) {
                continue;
            }
            boolean forgotten;
            try {
                forgotten = repo.hasTombstone(scope, fingerprint);
            } catch (RuntimeException e) {
                log.warn("memory: check forgotten topic failed: {}", e.toString());
                continue;
            }
            if (forgotten) {
                return true;
            }
        }
        return false;
    }

    /** 对照 Go {@code tombstoneTopic}：把一条主题连同它的全部别名记成"被拒绝"。 */
    void tombstoneTopic(MemoryScope scope, MemoryTopicStat stat) {
        if (stat == null) {
            return;
        }
        List<String> labels = new ArrayList<>();
        if (!stat.getTopic().isEmpty()) {
            labels.add(stat.getTopic());
        }
        if (stat.getAliases() != null) {
            labels.addAll(stat.getAliases());
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String label : labels) {
            String content = MemoryText.sanitizeMemoryContent(label);
            String fingerprint = MemoryText.fingerprint(content);
            if (fingerprint.isEmpty()) {
                continue;
            }
            if (!seen.add(fingerprint)) {
                continue;
            }
            try {
                repo.addTombstone(scope, stat.getTopic(), fingerprint, "");
            } catch (RuntimeException e) {
                log.warn("memory: record topic tombstone failed: {}", e.toString());
            }
        }
    }

    /**
     * 对照 Go {@code CreateItem}：加一条用户自己敲进来的记忆。
     *
     * <p>它走与其它一切相同的写路径，所以一条手写的记忆可以**取代**同主题上
     * 抽取出来的一条，而不是并排躺着。</p>
     */
    public MemoryItem createItem(String kind, String content, int importance) {
        ScopeState state = enabledScope();
        if (!state.ok()) {
            throw new MemoryScopeExceptions.Disabled();
        }
        if (!MemoryKinds.isValid(kind)) {
            kind = MemoryKinds.KIND_FACT;
        }
        if (importance <= 0) {
            importance = 3;
        }
        MemoryItem item = new MemoryItem();
        item.setKind(kind);
        item.setContent(content);
        item.setImportance(importance);
        item.setOrigin(MemoryKinds.ORIGIN_MANUAL);
        return write(state.scope(), state.cfg(), item);
    }

    /**
     * 对照 Go {@code UpdateItem}：从记忆管理器里编辑一条。
     * 被编辑过的条目会变成 manual，这样之后的抽取不会悄悄撤销用户的更正。
     */
    public MemoryItem updateItem(String id, String content, int importance) {
        MemoryScope scope = MemoryScopes.resolve();
        MemoryItem existing = repo.getItem(scope, id);
        if (existing == null) {
            throw new MemoryScopeExceptions.ItemNotFound();
        }
        String sanitized = MemoryText.sanitizeMemoryContent(content);
        if (sanitized.isEmpty()) {
            throw new MemoryScopeExceptions.EmptyContent();
        }
        MemoryText.Redaction redaction = MemoryText.redactSensitive(sanitized);
        if (redaction.changed()) {
            if (MemoryText.isMostlyRedacted(redaction.content())) {
                throw new MemoryScopeExceptions.SensitiveContent();
            }
            sanitized = MemoryText.sanitizeMemoryContent(redaction.content());
        }
        // 保留原来的主题：用户在更正陈述，不是把它重新归到另一个主题下，
        // 而复用主题正是让这条更正能够取代未来某次抽取的原因。
        String normalizedKey = MemoryKeys.itemKey(existing.getTopic(), sanitized);
        int clamped = MemoryText.clampImportance(importance);
        repo.updateItemContent(scope, id, sanitized, normalizedKey, clamped);
        rebuildBlock(scope);
        MemoryItem updated = repo.getItem(scope, id);
        vectorService.storeItemEmbedding(scope, workspaceConfig(scope.tenantId()), updated);
        return updated;
    }

    /**
     * 对照 Go {@code DeleteItem}：永久忘掉一条记忆。
     */
    public void deleteItem(String id) {
        MemoryScope scope = MemoryScopes.resolve();
        MemoryItem existing = repo.getItem(scope, id);
        if (existing == null) {
            throw new MemoryScopeExceptions.ItemNotFound();
        }
        // 在删行之前先记下这次拒绝。删掉一条蒸馏马上要从同一条消息重新推导出来的记忆，
        // 正是用户"同一个东西删两次然后不再信任这个功能"的来路。
        try {
            repo.addTombstone(scope, existing.getTopic(),
                    MemoryText.fingerprint(existing.getContent()), existing.getSourceMessageId());
        } catch (RuntimeException e) {
            log.warn("memory: record tombstone failed: {}", e.toString());
        }
        repo.deleteItem(scope, id);
        rebuildBlock(scope);
    }

    /** 对照 Go {@code Clear}：忘掉调用者记忆空间里的一切。 */
    public long clear() {
        MemoryScope scope = MemoryScopes.resolve();
        // 清空是对当前存着的一切的拒绝，所以它留下的墓碑与逐条删除一样。
        tombstoneEverything(scope);
        long removed = repo.deleteAll(scope);
        repo.deleteAllTopics(scope);
        repo.deleteAllDocAffinity(scope);
        rebuildBlock(scope);
        return removed;
    }

    /**
     * 对照 Go {@code tombstoneEverything}：为清空删掉的每一条记忆记一次拒绝。
     *
     * <p>一个主体最多保留 {@code MaxMemoryTombstones} 条拒绝，而存储能持有的行远多于此：
     * {@code max_items} 只管活跃记忆，被取代与被归档的行可以无上限堆积。所以读一页平铺的
     * 列表会把整个预算花在"恰好最新的那些"上，一条活着的记忆可能一条墓碑都没有，
     * 于是又可以自由地被重新推导出来。</p>
     *
     * <p>按状态逐个走，是把预算花在**能改变行为**的地方：用户还在被服务的，
     * 然后是在等他决定的，最后是其余。总数封顶，好让这次调用不会把自己更早、
     * 更重要的那些行挤掉。</p>
     */
    private void tombstoneEverything(MemoryScope scope) {
        int budget = MemoryKinds.MAX_TOMBSTONES;
        for (String status : List.of(MemoryKinds.STATUS_ACTIVE, MemoryKinds.STATUS_PENDING,
                MemoryKinds.STATUS_ARCHIVED, MemoryKinds.STATUS_SUPERSEDED)) {
            if (budget <= 0) {
                return;
            }
            MemoryRepository.Page<MemoryItem> page;
            try {
                page = repo.listItems(scope, status, budget, 0);
            } catch (RuntimeException e) {
                log.warn("memory: list {} items during clear failed: {}", status, e.toString());
                continue;
            }
            for (MemoryItem item : page.items()) {
                if (item == null) {
                    continue;
                }
                try {
                    repo.addTombstone(scope, item.getTopic(), MemoryText.fingerprint(item.getContent()),
                            item.getSourceMessageId());
                } catch (RuntimeException e) {
                    log.warn("memory: record tombstone during clear failed: {}", e.toString());
                }
                budget--;
            }
        }
    }

    /** 对照 Go {@code GetSettings}：设置界面渲染的那个合并视图。 */
    public MemorySettings getSettings() {
        MemoryScope scope = MemoryScopes.resolve();
        MemoryConfig cfg = workspaceConfig(scope.tenantId());
        MemorySettings settings = new MemorySettings();
        settings.setWorkspaceEnabled(cfg.memoryEnabled());
        settings.setUserEnabled(true);
        settings.setWriteMode(cfg.getWriteMode());
        settings.setMaxItems(cfg.effectiveMaxItems());
        if (settings.getWriteMode().isEmpty()) {
            settings.setWriteMode(MemoryConfig.WRITE_MODE_EXPLICIT_ONLY);
        }
        MemorySubject subject = repo.getSubject(scope);
        if (subject != null) {
            settings.setUserEnabled(subject.isEnabled());
            settings.setItemCount(subject.getItemCount());
        }
        try {
            settings.setItemCount((int) repo.countActive(scope));
        } catch (RuntimeException e) {
            // Go 只在 err == nil 时覆盖；失败时保留主体上的那一份。
        }
        settings.setEffective(settings.isWorkspaceEnabled() && settings.isUserEnabled());
        return settings;
    }

    /** 对照 Go {@code SetEnabled}：翻转调用者自己的退出开关。 */
    public void setEnabled(boolean enabled) {
        MemoryScope scope = MemoryScopes.resolve();
        repo.updateSubjectEnabled(scope, enabled);
    }

    /** 对照 Go {@code ConfirmItem}：接受系统推断出来的东西，让它开始被使用。 */
    public MemoryItem confirmItem(String id) {
        MemoryScope scope = MemoryScopes.resolve();
        MemoryItem existing = repo.getItem(scope, id);
        if (existing == null) {
            throw new MemoryScopeExceptions.ItemNotFound();
        }
        repo.confirmPendingItem(scope, id);
        enforceCapacity(scope, workspaceConfig(scope.tenantId()));
        rebuildBlock(scope);
        return repo.getItem(scope, id);
    }

    /**
     * 对照 Go {@code RejectItem}：拒绝一条推断。
     *
     * <p>它删掉而不是归档，这样墓碑就能阻止同一个猜测下周再被提出来。</p>
     */
    public void rejectItem(String id) {
        deleteItem(id);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 检索条件化
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 对照 Go {@code RetrievalContextFor}：返回记忆对**检索**的贡献。
     *
     * <p>与 {@code Recall} 一样，它不做模型调用：两次带索引的读加上字符串拼装，
     * 因为它跑在每一个检索回合的第一个 token 之前。</p>
     */
    public MemoryRetrievalContext retrievalContextFor() {
        MemoryTrace.Span condSpan = MemoryTrace.start("memory.retrieval_context", null);
        ScopeState state = enabledScope();
        if (!state.ok() || !state.cfg().retrievalConditioningEnabled()) {
            String reason;
            if (!state.ok()) {
                reason = scopeDisableReason();
            } else if (!state.cfg().retrievalConditioningEnabled()) {
                reason = "retrieval_conditioning_disabled";
            } else {
                reason = "disabled";
            }
            Map<String, Object> skipped = new LinkedHashMap<>();
            skipped.put("outcome", "skipped");
            skipped.put("reason", reason);
            condSpan.finish(skipped, null, null);
            return MemoryRetrievalContext.EMPTY;
        }
        MemoryScope scope = state.scope();

        List<MemoryItem> items;
        try {
            items = repo.listActiveByKinds(scope,
                    List.of(MemoryKinds.KIND_PROFILE, MemoryKinds.KIND_INTEREST), 30);
        } catch (RuntimeException e) {
            log.warn("memory: load retrieval context failed: {}", e.toString());
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("outcome", "error");
            error.put("error", e.toString());
            condSpan.finish(error, null, e);
            return MemoryRetrievalContext.EMPTY;
        }

        List<String> background = new ArrayList<>();
        List<String> interests = new ArrayList<>();
        List<MemoryItem> used = new ArrayList<>();
        int budget = 0;
        if (items != null) {
            for (MemoryItem item : items) {
                if (item == null) {
                    continue;
                }
                String line = MemoryText.sanitizeMemoryContent(item.getContent());
                if (line.isEmpty()) {
                    continue;
                }
                int cost = MemoryKeys.runeLength(line) + 2;
                if (budget + cost > RETRIEVAL_BACKGROUND_RUNE_BUDGET) {
                    break;
                }
                budget += cost;
                used.add(item);
                if (MemoryKinds.KIND_INTEREST.equals(item.getKind())) {
                    interests.add(line);
                    continue;
                }
                background.add(line);
            }
        }

        List<String> documents = topDocumentTitles(scope);

        MemoryRetrievalContext retrievalCtx = new MemoryRetrievalContext(
                String.join("；", background), interests, documents, used);
        log.info("memory: retrieval context subject={} interests={} documents={} items={}",
                scope.subjectId(), interests.size(), documents == null ? 0 : documents.size(),
                used.size());
        condSpan.finish(MemoryTrace.summarizeRetrievalContextOutput(
                        retrievalCtx.background(), interests, documents, used),
                Map.of("tenant_id", scope.tenantId()), null);
        return retrievalCtx;
    }

    /**
     * 对照 Go {@code topDocumentTitles}：把这个人答案通常取材自的词汇交给改写器。
     * 用标题而不是 id，因为改写器的任务是产出更好的检索文本，不是寻址文档。
     */
    private List<String> topDocumentTitles(MemoryScope scope) {
        List<MemoryDocAffinity> rows;
        try {
            rows = repo.topDocAffinity(scope, 5);
        } catch (RuntimeException e) {
            log.warn("memory: load document affinity failed: {}", e.toString());
            return null;
        }
        if (rows == null) {
            return null;
        }
        List<String> titles = new ArrayList<>(rows.size());
        for (MemoryDocAffinity row : rows) {
            if (row == null || row.getTitle().strip().isEmpty()) {
                continue;
            }
            // 见过一次不算习惯。
            if (row.getHits() < MemoryConfig.MEMORY_DOC_AFFINITY_MIN_HITS) {
                continue;
            }
            titles.add(row.getTitle());
        }
        return titles;
    }

    /** 对照 Go {@code DocumentAffinity}：按这个人以前对它们的依赖给文档打分。 */
    public Map<String, Integer> documentAffinity(List<String> knowledgeIds) {
        ScopeState state = enabledScope();
        if (!state.ok() || !state.cfg().retrievalConditioningEnabled()
                || knowledgeIds == null || knowledgeIds.isEmpty()) {
            return null;
        }
        try {
            return repo.docAffinity(state.scope(), knowledgeIds);
        } catch (RuntimeException e) {
            log.warn("memory: read document affinity failed: {}", e.toString());
            return null;
        }
    }

    /**
     * 对照 Go {@code RecordAnswerSources}：记下一个回答取材于哪些文档。
     *
     * <p>挂在回答上的引用是比显式点赞更弱的信号，但它是不问用户任何东西就能拿到的
     * 唯一一个，而且正是它让重排器能够偏爱这个人反复回来的材料。</p>
     */
    public void recordAnswerSources(List<MemoryDocAffinity> refs) {
        if (refs == null || refs.isEmpty()) {
            return;
        }
        ScopeState state = enabledScope();
        if (!state.ok() || !state.cfg().retrievalConditioningEnabled()) {
            return;
        }
        try {
            repo.ensureSubject(state.scope());
        } catch (RuntimeException e) {
            log.warn("memory: ensure subject for affinity failed: {}", e.toString());
            return;
        }
        try {
            repo.bumpDocAffinity(state.scope(), refs);
        } catch (RuntimeException e) {
            log.warn("memory: record answer sources failed: {}", e.toString());
        }
    }

    /**
     * 对照 Go {@code ObserveQuestionTopics}：统计一个人问过什么，
     * 并在某个主体复现之后把它提升成记忆。返回本次提升出来的兴趣。
     */
    public List<String> observeQuestionTopics(List<String> topics) {
        if (topics == null || topics.isEmpty()) {
            return null;
        }
        ScopeState state = enabledScope();
        if (!state.ok()) {
            return null;
        }
        return observeTopics(state.scope(), state.cfg(),
                extractionModelId(state.cfg(), MemoryExtractPayload.empty()), topics,
                MemoryRunBudget.UNBOUNDED);
    }

    /**
     * 对照 Go {@code observeTopics}：显式传 scope 的形态。
     *
     * <p>蒸馏跑在一个没有主体的后台 worker 上——它的 scope 来自任务负载——
     * 所以蒸馏调用的任何东西都必须被**交给** scope，而不是从请求里重新推导。</p>
     */
    List<String> observeTopics(MemoryScope scope, MemoryConfig cfg, String modelId,
                               List<String> topics, MemoryRunBudget budget) {
        if (topics == null || topics.isEmpty() || cfg == null || !cfg.autoExtractEnabled()) {
            return null;
        }
        try {
            repo.ensureSubject(scope);
        } catch (RuntimeException e) {
            log.warn("memory: ensure subject for topics failed: {}", e.toString());
            return null;
        }

        // 先把标签洗干净，再把它们对着这个人已经有的主体解析。统计原始字符串
        // 正是让这个功能悄悄失效的原因：模型每次给同一个主体起不同的名字，
        // 于是每次出现都落在自己的 key 下，没有任何主题会复现。
        List<String> surfaces = new ArrayList<>(topics.size());
        for (String topic : topics) {
            String cleaned = MemoryText.sanitizeMemoryTopic(topic);
            if (!cleaned.isEmpty()) {
                surfaces.add(cleaned);
            }
        }
        if (surfaces.isEmpty()) {
            return null;
        }
        List<MemoryTopicResolver.Resolution> resolutions =
                topicResolver.resolveTopics(scope, modelId, surfaces, budget);

        int threshold = cfg.effectiveInterestThreshold();
        List<String> promoted = new ArrayList<>();
        for (MemoryTopicResolver.Resolution resolution : resolutions) {
            // 存下来的标签保持这个主体**第一次**被记下时的那个，这样一个人的主题列表
            // 不会每次模型换个说法就翻搅一遍。新的措辞留作别名。
            String canonicalTopic = resolution.surface();
            if (resolution.canonical() != null) {
                canonicalTopic = resolution.canonical().getTopic();
            }
            String key = MemoryKeys.normalizeTopicKey(canonicalTopic);
            if (key.isEmpty()) {
                continue;
            }
            if (topicWasForgotten(scope, canonicalTopic, resolution.surface())) {
                continue;
            }
            int aliasesBefore = topicAliasCount(scope, key);
            MemoryTopicStat stat;
            try {
                stat = repo.bumpTopic(scope, canonicalTopic, key, resolution.surface());
            } catch (RuntimeException e) {
                log.warn("memory: count topic {} failed: {}", canonicalTopic, e.toString());
                continue;
            }
            if (stat == null) {
                log.warn("memory: topic {} produced no row", canonicalTopic);
                continue;
            }
            // 没有这一行，从外面就无从判断一个主题到底有没有被计数、被折进了哪个主体、
            // 是哪一层判定的——而"hits 永远是 1"和"什么都没跑"看起来一模一样。
            log.info("memory: topic {} -> {} (tier={}, hits={}, threshold={})",
                    resolution.surface(), canonicalTopic, resolution.tierOrNew(), stat.getHits(),
                    threshold);
            if (MemoryKeys.topicLooksLikeOneQuestion(canonicalTopic)) {
                log.warn("memory: topic {} names one question rather than a subject, so it will never "
                        + "recur and can never reach the threshold", canonicalTopic);
            }
            // 新的措辞改变了这个主体的兴趣应当嵌入成什么，而向量是在提升那一刻写的一次。
            // 丢掉它，让维护补扫带上新措辞重建。
            if (stat.getAliases() != null && stat.getAliases().size() > aliasesBefore) {
                invalidateInterestEmbedding(scope, canonicalTopic);
            }
            if (resolution.mergedLabel() != null && !resolution.mergedLabel().isEmpty()) {
                String[] renamed = renameTopic(scope, stat, resolution.mergedLabel(), key);
                canonicalTopic = renamed[0];
                key = renamed[1];
            }

            if (stat.getPromotedAt() != null || stat.getHits() < threshold) {
                continue;
            }
            MemoryItem interest = new MemoryItem();
            interest.setKind(MemoryKinds.KIND_INTEREST);
            interest.setTopic(canonicalTopic);
            interest.setContent(canonicalTopic);
            interest.setImportance(3);
            interest.setOrigin(MemoryKinds.ORIGIN_EXTRACTED);
            try {
                write(scope, cfg, interest);
            } catch (MemoryScopeExceptions.PreviouslyForgotten e) {
                // 用户忘过一次的主题不该在之后每一个问题上重新自我提议。
            } catch (MemoryScopeExceptions.SensitiveContent e) {
                // 同上：不算失败。
            } catch (RuntimeException e) {
                log.warn("memory: promote interest failed: {}", e.toString());
            }
            try {
                repo.markTopicPromoted(scope, key);
            } catch (RuntimeException e) {
                log.warn("memory: mark topic promoted failed: {}", e.toString());
            }
            promoted.add(canonicalTopic);
        }
        if (!promoted.isEmpty()) {
            log.info("memory: promoted {} recurring topics into interests", promoted.size());
        }
        return promoted;
    }

    /**
     * 对照 Go {@code renameTopic}：给一个主体采纳更好的标签，并让一切指向它的东西跟上。
     *
     * <p>一次合并留下的标签否则就只是"先到的那个措辞"，而那个标签不是装饰性的：
     * 它被当作这个人的词汇喂给查询改写器，也展示给他看我们以为他在乎什么。</p>
     *
     * @return {@code [label, key]}——继续用下去的那两个值
     */
    private String[] renameTopic(MemoryScope scope, MemoryTopicStat stat, String newLabel, String currentKey) {
        String newKey = MemoryKeys.normalizeTopicKey(newLabel);
        boolean renamed;
        try {
            renamed = repo.renameTopic(scope, currentKey, newKey, newLabel);
        } catch (RuntimeException e) {
            log.warn("memory: rename topic {} failed: {}", stat.getTopic(), e.toString());
            return new String[]{stat.getTopic(), currentKey};
        }
        if (!renamed) {
            return new String[]{stat.getTopic(), currentKey};
        }
        log.info("memory: renamed topic {} to {}", stat.getTopic(), newLabel);
        renameInterestItem(scope, stat.getTopic(), newLabel);
        return new String[]{newLabel, newKey};
    }

    /**
     * 对照 Go {@code renameInterestItem}：让提升出来的兴趣与它的主体保持同步。
     *
     * <p>它只碰"仍然一字不差地读作旧标签"的条目。别的都被用户编辑过，
     * 悄悄覆盖别人自己的措辞比让两者稍微不同步更糟。</p>
     */
    private void renameInterestItem(MemoryScope scope, String oldLabel, String newLabel) {
        List<MemoryItem> items;
        try {
            items = repo.listActiveByKinds(scope, List.of(MemoryKinds.KIND_INTEREST), 100);
        } catch (RuntimeException e) {
            log.warn("memory: load interests for rename failed: {}", e.toString());
            return;
        }
        if (items == null) {
            return;
        }
        for (MemoryItem item : items) {
            if (item == null || !item.getContent().equals(oldLabel)) {
                continue;
            }
            try {
                repo.updateItemContent(scope, item.getId(), newLabel,
                        MemoryKeys.itemKey(newLabel, newLabel), item.getImportance());
            } catch (RuntimeException e) {
                log.warn("memory: rename interest item failed: {}", e.toString());
                continue;
            }
            // 向量里还拼着旧标签，所以语义召回会继续匹配一个这个主体已经不再用的名字。
            try {
                repo.deleteItemEmbedding(scope, item.getId());
            } catch (RuntimeException e) {
                log.warn("memory: drop renamed interest embedding failed: {}", e.toString());
            }
            rebuildBlock(scope);
            return;
        }
    }

    /** 对照 Go {@code topicAliasCount}：一个主体已经以多少种措辞被认识。 */
    private int topicAliasCount(MemoryScope scope, String key) {
        MemoryTopicStat stat;
        try {
            stat = repo.topicByKey(scope, key);
        } catch (RuntimeException e) {
            return 0;
        }
        if (stat == null || stat.getAliases() == null) {
            return 0;
        }
        return stat.getAliases().size();
    }

    /**
     * 对照 Go {@code invalidateInterestEmbedding}：丢掉从这个主体提升出来的那条兴趣的向量。
     * 尽力而为：在一个维护周期里没有向量只损失一条记忆的语义召回，而这条记忆全程都还能
     * 靠措辞被找到。
     */
    private void invalidateInterestEmbedding(MemoryScope scope, String topic) {
        List<MemoryItem> items;
        try {
            items = repo.listActiveByKinds(scope, List.of(MemoryKinds.KIND_INTEREST), 100);
        } catch (RuntimeException e) {
            log.warn("memory: load interests for re-embedding failed: {}", e.toString());
            return;
        }
        if (items == null) {
            return;
        }
        for (MemoryItem item : items) {
            if (item == null || !item.getContent().equals(topic)) {
                continue;
            }
            try {
                repo.deleteItemEmbedding(scope, item.getId());
            } catch (RuntimeException e) {
                log.warn("memory: drop interest embedding failed: {}", e.toString());
            }
            return;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 按需查找（search.go）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 对照 Go {@code SearchMemory}：把这个用户存下的记忆对着一个任意查询排序。
     *
     * <p>召回每轮跑一次、对着用户开场的那个问题，而且它放行什么被卡得很死：
     * 五条情境条目，600 rune 预算。有两类东西落在外面——一个已经跑了十轮、
     * 早就离开开场问题的 agent 循环，此时它在做的事与召回排序所依据的东西
     * 一个词都不重合；以及一个有几十条已存事实的主体，其中大多数静静躺在切线下，
     * 没有任何办法够到。两者都不是"把每轮预算开大"能修的，
     * 因为那份预算是每一轮都要付的，包括那些一条都不需要的轮次。</p>
     *
     * <p>只有 active 的条目会被搜到。被取代与被归档的记忆刻意留在够不到的地方：
     * 一条被更新的陈述取代掉的东西，正是取代机制存在的理由，
     * 从侧门把它翻出来会让那套机制前功尽弃。</p>
     */
    public MemorySearchResult searchMemory(String query, int limit) {
        String trimmed = query == null ? "" : query.strip();

        MemoryTrace.Span searchSpan = MemoryTrace.start("memory.search", Map.of(
                "query", MemoryTrace.truncateRunes(trimmed, MemoryRecallSelector.recallQueryPreviewRunes()),
                "limit", limit));

        ScopeState state = enabledScope();
        if (!state.ok()) {
            String reason = scopeDisableReason();
            log.info("memory: search skipped ({})", reason);
            Map<String, Object> disabled = new LinkedHashMap<>();
            disabled.put("outcome", "disabled");
            disabled.put("reason", reason);
            searchSpan.finish(MemoryTrace.summarizeMemoryRecallOutput(disabled, null), null, null);
            return MemorySearchResult.UNAVAILABLE;
        }
        MemoryScope scope = state.scope();
        MemoryConfig cfg = state.cfg();

        // 空查询走到这里，而不是在上面短路，是为了让"记忆关着"仍然赢过"你要了空的东西"：
        // 调用方自己的参数就算写错了，也需要那个"不可用"的答案。
        if (trimmed.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("outcome", "empty");
            empty.put("reason", "blank_query");
            searchSpan.finish(MemoryTrace.summarizeMemoryRecallOutput(empty, null), null, null);
            return new MemorySearchResult(true, null);
        }

        int effectiveLimit = limit;
        if (effectiveLimit <= 0) {
            effectiveLimit = MemoryKinds.SEARCH_DEFAULT_ITEMS;
        }
        if (effectiveLimit > MemoryKinds.SEARCH_MAX_ITEMS) {
            effectiveLimit = MemoryKinds.SEARCH_MAX_ITEMS;
        }

        List<MemoryItem> candidates;
        try {
            candidates = repo.listActiveByKinds(scope, MemoryKinds.ALL,
                    MemoryRecallSelector.lexicalPoolSize(cfg));
        } catch (RuntimeException e) {
            log.warn("memory: load search candidates failed: {}", e.toString());
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("outcome", "error");
            error.put("error", e.toString());
            searchSpan.finish(MemoryTrace.summarizeMemoryRecallOutput(error, null), null, e);
            return new MemorySearchResult(true, null);
        }
        if (candidates == null) {
            candidates = List.of();
        }

        MemoryRecallSelector.Outcome outcome = recallSelector.selectRecallWithTrace(scope, cfg,
                new MemoryRecallSelector.Selection(trimmed, candidates, MemoryKinds.ALL,
                        null, effectiveLimit, MemoryKinds.SEARCH_RUNE_BUDGET));
        List<MemoryItem> matched = outcome.matched();
        MemoryRecallSelector.RankingTrace trace = outcome.trace();

        // 被搜索到的记忆与注入的一样确凿地被模型读过，所以它算"用过"。没有这一步，
        // 只有通过搜索够得到的条目会看起来永远没用过，在容量上限下次决定归档谁时排名最低。
        touchAsync(scope, matched);

        log.info("memory: search done subject={} candidates={} vector_hits={} outside_pool={} "
                        + "matched={} mode={}",
                scope.subjectId(), candidates.size(), trace.vectorHits, trace.vectorOutsidePool,
                matched.size(), trace.mode);

        Map<String, Object> okMeta = new LinkedHashMap<>();
        okMeta.put("outcome", "ok");
        okMeta.put("subject_id", scope.subjectId());
        okMeta.put("candidate_count", candidates.size());
        okMeta.put("lexical_hits", trace.lexicalHits);
        okMeta.put("vector_hits", trace.vectorHits);
        okMeta.put("vector_outside", trace.vectorOutsidePool);
        okMeta.put("vector_skip", trace.vectorSkipReason);
        okMeta.put("ranking_mode", trace.mode);
        okMeta.put("matched_count", matched.size());
        searchSpan.finish(MemoryTrace.summarizeMemoryRecallOutput(okMeta, matched),
                Map.of("tenant_id", scope.tenantId()), null);

        return new MemorySearchResult(true, matched);
    }

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
