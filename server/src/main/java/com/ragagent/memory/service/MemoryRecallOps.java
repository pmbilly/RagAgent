package com.ragagent.memory.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ragagent.common.settings.MemoryConfig;
import com.ragagent.common.settings.MemoryKeys;
import com.ragagent.common.settings.MemoryKinds;
import com.ragagent.memory.domain.MemoryItem;
import com.ragagent.memory.domain.MemoryRender;
import com.ragagent.memory.domain.MemoryScope;
import com.ragagent.memory.domain.MemorySubject;
import com.ragagent.memory.domain.MemoryText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 记忆的召回装配：一轮对话「要往提示词里注入什么」。
 *
 * <p>对应 {@code MemoryService} 原来的「召回」段——{@code Recall} 本身
 * 加上两个 trace 辅助（{@code finishSubjectLoadFailure} / {@code recallEmptyMeta}）
 * 与块内命中筛选 {@code residentItemsWithinBlock}。它**永不调用模型、永不返回错误**：
 * 记忆是增强，任何失败都必须退化成一个普通回答，而不是一次失败的请求。</p>
 *
 * <p>持有 {@link MemoryService} 回引以访问仓储与共享工具（三层开关判定
 * {@code enabledScope} / {@code scopeDisableReason}、用量回写 {@code touchAsync}）；
 * 本类不得独立实例化。</p>
 */
final class MemoryRecallOps {

    private static final Logger log = LoggerFactory.getLogger(MemoryRecallOps.class);

    private final MemoryService service;

    MemoryRecallOps(MemoryService service) {
        this.service = service;
    }

    /**
     * 装配一轮要注入的记忆。
     *
     * <p>它**永不调用模型、永不返回错误**：记忆是增强，任何失败都必须退化成
     * 一个普通回答，而不是一次失败的请求。</p>
     */
    public MemoryRecall recall(String query) {
        MemoryTrace.Span recallSpan = MemoryTrace.start("memory.recall", Map.of(
                "query", MemoryTrace.truncateRunes(query, MemoryRecallSelector.recallQueryPreviewRunes())));

        MemoryService.ScopeState state = service.enabledScope();
        if (!state.ok()) {
            String reason = service.scopeDisableReason();
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
            subject = service.repo.getSubject(scope);
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
            residentItems = service.repo.listActiveResident(scope, 60);
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
            situational = service.repo.listActiveByKinds(scope,
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

        MemoryRecallSelector.Outcome outcome = service.recallSelector.selectRecallWithTrace(scope, cfg,
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
        service.touchAsync(scope, used);

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

    /** recall 的两处"主体取不到"分支（它们只差 reason 文案）。 */
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
     * 解释 {@code Recall} 为什么没产出提示词。
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
     * 筛出内容真的在块里活下来的那些条目。
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
}
