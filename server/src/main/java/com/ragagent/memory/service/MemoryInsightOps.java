package com.ragagent.memory.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.common.settings.MemoryConfig;
import com.ragagent.common.settings.MemoryKeys;
import com.ragagent.common.settings.MemoryKinds;
import com.ragagent.memory.domain.MemoryDocAffinity;
import com.ragagent.memory.domain.MemoryItem;
import com.ragagent.memory.domain.MemoryScope;
import com.ragagent.memory.domain.MemoryText;
import com.ragagent.memory.domain.MemoryTopicStat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 记忆的检索侧：召回上下文装配、文档亲和（读写与标题聚合）、问题主题观察与搜索。
 *
 * <p>持有 {@link MemoryService} 回引以访问仓储与共享工具；本类不得独立实例化。</p>
 */
final class MemoryInsightOps {

    private static final Logger log = LoggerFactory.getLogger(MemoryInsightOps.class);

    private final MemoryService service;

    MemoryInsightOps(MemoryService service) {
        this.service = service;
    }

    /**
     * 返回记忆对**检索**的贡献。
     *
     * <p>与 {@code Recall} 一样，它不做模型调用：两次带索引的读加上字符串拼装，
     * 因为它跑在每一个检索回合的第一个 token 之前。</p>
     */
    public MemoryRetrievalContext retrievalContextFor() {
        MemoryTrace.Span condSpan = MemoryTrace.start("memory.retrieval_context", null);
        MemoryService.ScopeState state = service.enabledScope();
        if (!state.ok() || !state.cfg().retrievalConditioningEnabled()) {
            String reason;
            if (!state.ok()) {
                reason = service.scopeDisableReason();
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
            items = service.repo.listActiveByKinds(scope,
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
                if (budget + cost > MemoryService.RETRIEVAL_BACKGROUND_RUNE_BUDGET) {
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
     * 把这个人答案通常取材自的词汇交给改写器。
     * 用标题而不是 id，因为改写器的任务是产出更好的检索文本，不是寻址文档。
     */
    List<String> topDocumentTitles(MemoryScope scope) {
        List<MemoryDocAffinity> rows;
        try {
            rows = service.repo.topDocAffinity(scope, 5);
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

    /** 按这个人以前对它们的依赖给文档打分。 */
    public Map<String, Integer> documentAffinity(List<String> knowledgeIds) {
        MemoryService.ScopeState state = service.enabledScope();
        if (!state.ok() || !state.cfg().retrievalConditioningEnabled()
                || knowledgeIds == null || knowledgeIds.isEmpty()) {
            return null;
        }
        try {
            return service.repo.docAffinity(state.scope(), knowledgeIds);
        } catch (RuntimeException e) {
            log.warn("memory: read document affinity failed: {}", e.toString());
            return null;
        }
    }

    /**
     * 记下一个回答取材于哪些文档。
     *
     * <p>挂在回答上的引用是比显式点赞更弱的信号，但它是不问用户任何东西就能拿到的
     * 唯一一个，而且正是它让重排器能够偏爱这个人反复回来的材料。</p>
     */
    public void recordAnswerSources(List<MemoryDocAffinity> refs) {
        if (refs == null || refs.isEmpty()) {
            return;
        }
        MemoryService.ScopeState state = service.enabledScope();
        if (!state.ok() || !state.cfg().retrievalConditioningEnabled()) {
            return;
        }
        try {
            service.repo.ensureSubject(state.scope());
        } catch (RuntimeException e) {
            log.warn("memory: ensure subject for affinity failed: {}", e.toString());
            return;
        }
        try {
            service.repo.bumpDocAffinity(state.scope(), refs);
        } catch (RuntimeException e) {
            log.warn("memory: record answer sources failed: {}", e.toString());
        }
    }

    /**
     * 统计一个人问过什么，
     * 并在某个主体复现之后把它提升成记忆。返回本次提升出来的兴趣。
     */
    public List<String> observeQuestionTopics(List<String> topics) {
        if (topics == null || topics.isEmpty()) {
            return null;
        }
        MemoryService.ScopeState state = service.enabledScope();
        if (!state.ok()) {
            return null;
        }
        return observeTopics(state.scope(), state.cfg(),
    service.extractionModelId(state.cfg(), MemoryExtractPayload.empty()), topics,
                MemoryRunBudget.UNBOUNDED);
    }

    /**
     * 显式传 scope 的形态。
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
            service.repo.ensureSubject(scope);
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
                service.topicResolver.resolveTopics(scope, modelId, surfaces, budget);

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
            if (service.topicWasForgotten(scope, canonicalTopic, resolution.surface())) {
                continue;
            }
            int aliasesBefore = service.topicAliasCount(scope, key);
            MemoryTopicStat stat;
            try {
                stat = service.repo.bumpTopic(scope, canonicalTopic, key, resolution.surface());
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
                service.catalogOps.invalidateInterestEmbedding(scope, canonicalTopic);
            }
            if (resolution.mergedLabel() != null && !resolution.mergedLabel().isEmpty()) {
                String[] renamed = service.renameTopic(scope, stat, resolution.mergedLabel(), key);
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
    service.write(scope, cfg, interest);
            } catch (MemoryScopeExceptions.PreviouslyForgotten e) {
                // 用户忘过一次的主题不该在之后每一个问题上重新自我提议。
            } catch (MemoryScopeExceptions.SensitiveContent e) {
                // 同上：不算失败。
            } catch (RuntimeException e) {
                log.warn("memory: promote interest failed: {}", e.toString());
            }
            try {
                service.repo.markTopicPromoted(scope, key);
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
     * 把这个用户存下的记忆对着一个任意查询排序。
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

        MemoryService.ScopeState state = service.enabledScope();
        if (!state.ok()) {
            String reason = service.scopeDisableReason();
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
            candidates = service.repo.listActiveByKinds(scope, MemoryKinds.ALL,
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

        MemoryRecallSelector.Outcome outcome = service.recallSelector.selectRecallWithTrace(scope, cfg,
                new MemoryRecallSelector.Selection(trimmed, candidates, MemoryKinds.ALL,
                        null, effectiveLimit, MemoryKinds.SEARCH_RUNE_BUDGET));
        List<MemoryItem> matched = outcome.matched();
        MemoryRecallSelector.RankingTrace trace = outcome.trace();

        // 被搜索到的记忆与注入的一样确凿地被模型读过，所以它算"用过"。没有这一步，
        // 只有通过搜索够得到的条目会看起来永远没用过，在容量上限下次决定归档谁时排名最低。
    service.touchAsync(scope, matched);

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
}
