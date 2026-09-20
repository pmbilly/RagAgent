package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索目标（对照 Go {@code types.SearchTarget}，internal/types/search.go:26-47）。
 *
 * <p>本类是波 4.5b 从 types 包翻进 tools 包的公共类型：Agent 请求入口算好的
 * 检索范围（整库 / 指定文档 / 标签约束），wiki 与知识工具族共享。元素可为 null
 * 的语义对照 Go 的 {@code SearchTargets []*SearchTarget}（nil 元素处处跳过）。</p>
 */
public final class SearchTarget {

    /** 对照 SearchTargetTypeKnowledgeBase。 */
    public static final String TYPE_KNOWLEDGE_BASE = "knowledge_base";
    /** 对照 SearchTargetTypeKnowledge。 */
    public static final String TYPE_KNOWLEDGE = "knowledge";

    private final String type;
    private final String knowledgeBaseId;
    private final long tenantId;
    private final List<String> knowledgeIds;
    private final List<String> tagIds;
    private final List<String> scopeTagIds;
    private final boolean disableRecallThresholds;

    public SearchTarget(String type, String knowledgeBaseId, long tenantId, List<String> knowledgeIds,
                        List<String> tagIds, List<String> scopeTagIds, boolean disableRecallThresholds) {
        this.type = type;
        this.knowledgeBaseId = knowledgeBaseId;
        this.tenantId = tenantId;
        this.knowledgeIds = knowledgeIds;
        this.tagIds = tagIds;
        this.scopeTagIds = scopeTagIds;
        this.disableRecallThresholds = disableRecallThresholds;
    }

    /** 整库目标（对照 {@code &SearchTarget{Type: knowledge_base, KnowledgeBaseID: kb, TenantID: t}}）。 */
    public static SearchTarget wholeKb(String knowledgeBaseId, long tenantId) {
        return new SearchTarget(TYPE_KNOWLEDGE_BASE, knowledgeBaseId, tenantId,
                null, null, null, false);
    }

    public String type() {
        return type;
    }

    public String knowledgeBaseId() {
        return knowledgeBaseId;
    }

    /** 对照 TenantID（uint64 → long）。 */
    public long tenantId() {
        return tenantId;
    }

    /** 可能为 null（对照 Go nil slice）。 */
    public List<String> knowledgeIds() {
        return knowledgeIds;
    }

    public List<String> tagIds() {
        return tagIds;
    }

    public List<String> scopeTagIds() {
        return scopeTagIds;
    }

    public boolean disableRecallThresholds() {
        return disableRecallThresholds;
    }

    /** 对照 {@code (*SearchTarget).RecallThresholds}：禁用阈值时回落 (0,0)。 */
    public double[] recallThresholds(double vectorThreshold, double keywordThreshold) {
        if (disableRecallThresholds) {
            return new double[] {0, 0};
        }
        return new double[] {vectorThreshold, keywordThreshold};
    }

    /** 目标列表（对照 {@code types.SearchTargets}）。元素可为 null。 */
    public static final class SearchTargets {
        private final List<SearchTarget> targets;

        public SearchTargets(List<SearchTarget> targets) {
            this.targets = targets != null ? targets : List.of();
        }

        public List<SearchTarget> list() {
            return targets;
        }

        /** 对照 HasRecallThresholdOverride。 */
        public boolean hasRecallThresholdOverride() {
            for (SearchTarget t : targets) {
                if (t != null && t.disableRecallThresholds()) {
                    return true;
                }
            }
            return false;
        }

        /** 对照 GetAllKnowledgeBaseIDs（保持出现序去重）。 */
        public List<String> getAllKnowledgeBaseIds() {
            Map<String, Boolean> seen = new LinkedHashMap<>();
            for (SearchTarget t : targets) {
                if (t == null || t.knowledgeBaseId() == null || t.knowledgeBaseId().isEmpty()) {
                    continue;
                }
                seen.putIfAbsent(t.knowledgeBaseId(), Boolean.TRUE);
            }
            return new ArrayList<>(seen.keySet());
        }

        /** 对照 GetKBTenantMap（后出现者覆盖先出现者）。 */
        public Map<String, Long> getKbTenantMap() {
            Map<String, Long> result = new LinkedHashMap<>();
            for (SearchTarget t : targets) {
                if (t != null && t.knowledgeBaseId() != null && !t.knowledgeBaseId().isEmpty()) {
                    result.put(t.knowledgeBaseId(), t.tenantId());
                }
            }
            return result;
        }

        /** 对照 GetTenantIDForKB（找不到返回 0）。 */
        public long getTenantIdForKb(String kbId) {
            for (SearchTarget t : targets) {
                if (t != null && t.knowledgeBaseId() != null && t.knowledgeBaseId().equals(kbId)) {
                    return t.tenantId();
                }
            }
            return 0;
        }

        /** 对照 ContainsKB。 */
        public boolean containsKb(String kbId) {
            for (SearchTarget t : targets) {
                if (t != null && t.knowledgeBaseId() != null && t.knowledgeBaseId().equals(kbId)) {
                    return true;
                }
            }
            return false;
        }

        /** 对照 HasKnowledgeRetrievalScope（legacy KB/文档 ID 与 SearchTargets 并集）。 */
        public static boolean hasKnowledgeRetrievalScope(SearchTargets searchTargets,
                                                         List<String> knowledgeBaseIds, List<String> knowledgeIds) {
            if (knowledgeBaseIds != null) {
                for (String id : knowledgeBaseIds) {
                    if (id != null && !id.isEmpty()) {
                        return true;
                    }
                }
            }
            if (knowledgeIds != null) {
                for (String id : knowledgeIds) {
                    if (id != null && !id.isEmpty()) {
                        return true;
                    }
                }
            }
            if (searchTargets != null) {
                for (SearchTarget t : searchTargets.list()) {
                    if (t == null || t.knowledgeBaseId() == null || t.knowledgeBaseId().isEmpty()) {
                        continue;
                    }
                    if (TYPE_KNOWLEDGE_BASE.equals(t.type())
                            || (t.knowledgeIds() != null && !t.knowledgeIds().isEmpty())
                            || (t.tagIds() != null && !t.tagIds().isEmpty())
                            || (t.scopeTagIds() != null && !t.scopeTagIds().isEmpty())) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
