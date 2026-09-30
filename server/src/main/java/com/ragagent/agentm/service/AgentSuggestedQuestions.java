package com.ragagent.agentm.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.agentm.dto.CustomAgentResult;
import com.ragagent.agentm.mapper.AgentQuestionMapper;
import com.ragagent.agentm.mapper.CustomAgentMapper;
import com.ragagent.auth.apikey.domain.TenantAPIKeyScope;
import com.ragagent.knowledge.domain.KnowledgeBase;

/**
 * 智能体推荐问题流：按知识库/FAQ 桶轮询取问题、混合模式归并、tag 范围解析与
 * 跨租户 KB 分组。纯逻辑协作者，由 {@link CustomAgentService} 装配调用。
 */
public final class AgentSuggestedQuestions {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final int SUGGESTION_DEFAULT_LIMIT = 6;
    static final int SUGGESTION_MAX_LIMIT = 30;


    private final CustomAgentService agents;
    private final CustomAgentMapper agentMapper;
    private final AgentQuestionMapper questionMapper;

    AgentSuggestedQuestions(CustomAgentService agents, CustomAgentMapper agentMapper,
            AgentQuestionMapper questionMapper) {
        this.agents = agents;
        this.agentMapper = agentMapper;
        this.questionMapper = questionMapper;
    }

    /** 推荐问题入口（静态面：curated / 无范围 / FAQ+document chunk 池）。 */
    public ArrayNode getSuggestedQuestions(String agentId, List<String> kbIds,
            List<String> knowledgeIds, List<TagScope> tagScopes, int limit, String locale) {
        return getSuggestedQuestions(agentId, kbIds, knowledgeIds, tagScopes, limit, locale, true);
    }

    /**
     * 知识推荐分支：includeCurated=false
     * ——不走 starters 门/curated 收集，直接进 FAQ+document chunk 池
     * （追问建议 knowledge 模式的候选来源）。
     */
    public ArrayNode getKnowledgeSuggestedQuestions(String agentId, List<String> kbIds,
            List<String> knowledgeIds, List<TagScope> tagScopes, int limit, String locale) {
        return getSuggestedQuestions(agentId, kbIds, knowledgeIds, tagScopes, limit, locale, false);
    }

    private ArrayNode getSuggestedQuestions(String agentId, List<String> kbIds,
            List<String> knowledgeIds, List<TagScope> tagScopes, int limit, String locale,
            boolean includeCurated) {
        boolean limitProvided = limit > 0;
        if (!limitProvided) {
            limit = SUGGESTION_DEFAULT_LIMIT;
        }
        if (limit > SUGGESTION_MAX_LIMIT) {
            limit = SUGGESTION_MAX_LIMIT;
        }

        TenantAPIKeyScope.authorizeKnowledgeTargets(orEmpty(kbIds), orEmpty(knowledgeIds));
        List<String> scopeTagIds = flattenTagScopeIds(tagScopes);
        TenantAPIKeyScope.authorizeOptionalTagIds(scopeTagIds);

        CustomAgentResult agent = agents.getAgentByID(agentId, locale);
        ObjectNode cfg = agent.config();

        List<Object[]> curated = new ArrayList<>(); // [question, source, kbId]
        String starterMode = AgentConfigJson.SUGGESTION_KNOWLEDGE;

        JsonNode qs = cfg.get("question_suggestions");
        boolean startersEnabled = qs != null && qs.path("starters").path("enabled").asBoolean(false);
        if (includeCurated) {
            if (!startersEnabled) {
                return MAPPER.createArrayNode();
            }
            JsonNode starters = qs.get("starters");
            if (!limitProvided && starters.path("count").asInt(0) > 0) {
                limit = starters.path("count").asInt();
            }
            String mode = starters.path("mode").asText("");
            starterMode = mode;
            if (AgentConfigJson.SUGGESTION_CURATED.equals(mode) || AgentConfigJson.SUGGESTION_HYBRID.equals(mode)) {
                JsonNode items = starters.get("items");
                if (items != null && items.isArray()) {
                    for (JsonNode item : items) {
                        String prompt = item.asText("");
                        if (prompt.trim().isEmpty()) {
                            continue;
                        }
                        curated.add(new Object[] {prompt, "agent_config", ""});
                    }
                }
            }
            if (AgentConfigJson.SUGGESTION_CURATED.equals(mode)) {
                return truncate(curated, limit);
            }
        }

        // tag scopes 解析
        List<String> tagKnowledgeBaseIds = new ArrayList<>();
        List<String> tagKnowledgeIds = new ArrayList<>();
        Map<Long, List<String>> tagIdsByTenant = new LinkedHashMap<>();
        if (!scopeTagIds.isEmpty()) {
            ResolvedTags resolved = resolveTagScopes(tagScopes);
            tagKnowledgeBaseIds = resolved.knowledgeBaseIds();
            tagKnowledgeIds = resolved.knowledgeIds();
            tagIdsByTenant = resolved.tagIdsByTenant();
            knowledgeIds = mergeUnique(knowledgeIds, resolved.knowledgeIds());
            if ((knowledgeIds == null || knowledgeIds.isEmpty())
                    && resolved.tagIdsByTenant().isEmpty()) {
                return finalize(curated, null, starterMode, limit);
            }
        }

        // KB 范围
        List<String> effectiveKbIds = new ArrayList<>(orEmpty(kbIds));
        if (effectiveKbIds.isEmpty() && (knowledgeIds == null || knowledgeIds.isEmpty())
                && tagIdsByTenant.isEmpty()) {
            String kbMode = cfg.path("kb_selection_mode").asText("");
            switch (kbMode) {
                case "all" -> {
                    List<String> ids = new ArrayList<>();
                    boolean quickAnswer = "quick-answer".equals(cfg.path("agent_mode").asText(""));
                    for (KnowledgeBase kb : agents.kbService().listKnowledgeBases(null)) {
                        var caps = kb.capabilities();
                        if (quickAnswer && !(caps.vector() || caps.keyword())) {
                            continue;
                        }
                        ids.add(kb.getId());
                    }
                    effectiveKbIds = ids;
                }
                case "none" -> {
                    return finalize(curated, null, starterMode, limit);
                }
                default -> {
                    JsonNode kbs = cfg.get("knowledge_bases");
                    if (kbs != null && kbs.isArray()) {
                        for (JsonNode k : kbs) {
                            effectiveKbIds.add(k.asText(""));
                        }
                    }
                }
            }
        }
        effectiveKbIds = excludeStrings(effectiveKbIds, tagKnowledgeBaseIds);
        effectiveKbIds = TenantAPIKeyScope.filterKnowledgeBases(orEmpty(kbIds), effectiveKbIds);
        if (effectiveKbIds.isEmpty() && (knowledgeIds == null || knowledgeIds.isEmpty())
                && tagIdsByTenant.isEmpty()) {
            return finalize(curated, null, starterMode, limit);
        }

        Set<String> seen = new HashSet<>();
        for (Object[] q : curated) {
            seen.add((String) q[0]);
        }
        int remaining = limit;
        int fetchLimit = Math.max(remaining * 5, 20);

        List<String> scopeKbIds = mergeUnique(effectiveKbIds, tagKnowledgeBaseIds);
        Map<Long, List<String>> kbGroups = groupKbIdsByEffectiveTenant(scopeKbIds);
        if (scopeKbIds.isEmpty()) {
            kbGroups.computeIfAbsent(CustomAgentService.tenantId(), k -> new ArrayList<>());
        }
        List<String> queryKnowledgeIds = knowledgeIds == null ? List.of() : knowledgeIds;

        Map<String, List<Object[]>> buckets = new LinkedHashMap<>();

        for (Map.Entry<Long, List<String>> g : kbGroups.entrySet()) {
            List<String> explicit = intersect(g.getValue(), effectiveKbIds);
            List<String> gTagIds = tagIdsByTenant.getOrDefault(g.getKey(), List.of());
            faqQuery(g.getKey(), explicit, queryKnowledgeIds, gTagIds, fetchLimit, buckets, seen);
        }
        for (Map.Entry<Long, List<String>> g : kbGroups.entrySet()) {
            List<String> explicit = intersect(g.getValue(), effectiveKbIds);
            docQuery(g.getKey(), explicit, queryKnowledgeIds, fetchLimit, buckets, seen);
        }

        // wiki fallback 未实现（类注释差异声明）；桶内/桶间随机序不进契约（golden 单元素池）
        List<Object[]> knowledgeResult = roundRobin(buckets, limit);
        return finalize(curated, knowledgeResult, starterMode, limit);
    }

    private void faqQuery(long groupTenant, List<String> explicitKbIds, List<String> knowledgeIds,
            List<String> tagIds, int fetchLimit, Map<String, List<Object[]>> buckets, Set<String> seen) {
        if (explicitKbIds.isEmpty() && knowledgeIds.isEmpty() && tagIds.isEmpty()) {
            return; // 三个范围全空 → 直接返回空
        }
        List<Map<String, Object>> rows = questionMapper.listRecommendedFaqChunks(groupTenant,
                explicitKbIds, !explicitKbIds.isEmpty(),
                knowledgeIds, !knowledgeIds.isEmpty(),
                tagIds, !tagIds.isEmpty(), fetchLimit);
        for (Map<String, Object> row : rows) {
            String question = faqStandardQuestion((String) row.get("metadata"));
            if (question == null || question.isEmpty() || seen.contains(question)) {
                continue;
            }
            seen.add(question);
            buckets.computeIfAbsent((String) row.get("knowledgeId"), k -> new ArrayList<>())
                    .add(new Object[] {question, "faq", row.get("knowledgeBaseId")});
        }
    }

    private void docQuery(long groupTenant, List<String> explicitKbIds, List<String> knowledgeIds,
            int fetchLimit, Map<String, List<Object[]>> buckets, Set<String> seen) {
        List<Map<String, Object>> rows = questionMapper.listRecentDocumentChunksWithQuestions(
                groupTenant, explicitKbIds, !explicitKbIds.isEmpty(),
                knowledgeIds, !knowledgeIds.isEmpty(), fetchLimit);
        for (Map<String, Object> row : rows) {
            String q = firstGeneratedQuestion((String) row.get("metadata"));
            if (q == null || q.isEmpty() || seen.contains(q)) {
                continue;
            }
            seen.add(q);
            buckets.computeIfAbsent((String) row.get("knowledgeId"), k -> new ArrayList<>())
                    .add(new Object[] {q, "document", row.get("knowledgeBaseId")});
        }
    }

    /** metadata jsonb → faq standard_question（trim）。 */
    static String faqStandardQuestion(String metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readTree(metadata).path("standard_question").asText("").trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** metadata jsonb → generated_questions[0].question。 */
    static String firstGeneratedQuestion(String metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            JsonNode qs = MAPPER.readTree(metadata).get("generated_questions");
            if (qs == null || !qs.isArray() || qs.isEmpty()) {
                return null;
            }
            JsonNode first = qs.get(0);
            String q = first.isObject() ? first.path("question").asText("") : first.asText("");
            return q == null ? "" : q;
        } catch (Exception e) {
            return null;
        }
    }

    private List<Object[]> roundRobin(Map<String, List<Object[]>> buckets, int limit) {
        List<String> keys = new ArrayList<>(buckets.keySet());
        Map<String, Integer> offsets = new LinkedHashMap<>();
        List<Object[]> out = new ArrayList<>();
        boolean picked = true;
        while (out.size() < limit && picked) {
            picked = false;
            for (String key : keys) {
                if (out.size() >= limit) {
                    break;
                }
                List<Object[]> qs = buckets.get(key);
                int idx = offsets.getOrDefault(key, 0);
                if (idx < qs.size()) {
                    out.add(qs.get(idx));
                    offsets.put(key, idx + 1);
                    picked = true;
                }
            }
        }
        return out;
    }

    /** starter 收口（curated/hybrid/knowledge 三分支）。 */
    private ArrayNode finalize(List<Object[]> curated, List<Object[]> knowledge, String mode,
            int limit) {
        if (limit <= 0) {
            return MAPPER.createArrayNode();
        }
        switch (mode) {
            case AgentConfigJson.SUGGESTION_CURATED:
                return truncate(curated, limit);
            case AgentConfigJson.SUGGESTION_HYBRID:
                return mergeHybrid(curated, knowledge == null ? List.of() : knowledge, limit);
            default:
                return truncate(knowledge == null ? List.of() : knowledge, limit);
        }
    }

    /** hybrid 合并：knowledge 槽 = ⌈limit/3⌉（limit>1），curated 优先。 */
    private ArrayNode mergeHybrid(List<Object[]> curated, List<Object[]> knowledge, int limit) {
        int knowledgeSlots = limit > 1 ? (limit + 1) / 3 : 0;
        int curatedSlots = limit - knowledgeSlots;
        List<Object[]> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        appendFrom(curated, curatedSlots, result, seen, limit);
        appendFrom(knowledge, knowledgeSlots, result, seen, limit);
        appendFrom(curated, -1, result, seen, limit);
        appendFrom(knowledge, -1, result, seen, limit);
        ArrayNode out = MAPPER.createArrayNode();
        for (Object[] q : result) {
            addQuestion(out, q);
        }
        return out;
    }

    private static void appendFrom(List<Object[]> items, int max, List<Object[]> result,
            Set<String> seen, int limit) {
        int added = 0;
        for (Object[] item : items) {
            if (result.size() == limit || (max >= 0 && added == max)) {
                return;
            }
            String key = ((String) item[0]).toLowerCase().trim();
            if (key.isEmpty() || seen.contains(key)) {
                continue;
            }
            seen.add(key);
            result.add(item);
            added++;
        }
    }

    private static void addQuestion(ArrayNode out, Object[] q) {
        ObjectNode n = out.addObject();
        n.put("question", (String) q[0]);
        n.put("source", (String) q[1]);
        String kbId = (String) q[2];
        if (kbId != null && !kbId.isEmpty()) {
            n.put("knowledge_base_id", kbId);
        }
    }

    private ArrayNode truncate(List<Object[]> questions, int limit) {
        ArrayNode out = MAPPER.createArrayNode();
        int n = Math.min(questions.size(), limit);
        for (int i = 0; i < n; i++) {
            addQuestion(out, questions.get(i));
        }
        return out;
    }

    public record TagScope(String knowledgeBaseId, List<String> tagIds) {}

    private record ResolvedTags(List<String> knowledgeBaseIds, List<String> knowledgeIds,
            Map<Long, List<String>> tagIdsByTenant) {}

    /** tag 行校验 + knowledge 展开。 */
    private ResolvedTags resolveTagScopes(List<TagScope> scopes) {
        Map<String, List<String>> byKb = new LinkedHashMap<>();
        for (TagScope s : scopes) {
            if (s.knowledgeBaseId() == null || s.knowledgeBaseId().isEmpty()) {
                continue;
            }
            for (String tagId : s.tagIds()) {
                if (tagId == null || tagId.isEmpty()) {
                    continue;
                }
                byKb.computeIfAbsent(s.knowledgeBaseId(), k -> new ArrayList<>()).add(tagId);
            }
        }
        if (byKb.isEmpty()) {
            return new ResolvedTags(List.of(), List.of(), new LinkedHashMap<>());
        }
        Map<Long, List<String>> kbGroups = groupKbIdsByEffectiveTenant(new ArrayList<>(byKb.keySet()));
        List<String> outKbIds = new ArrayList<>();
        List<String> outKnowledgeIds = new ArrayList<>();
        Map<Long, List<String>> tagIdsByTenant = new LinkedHashMap<>();
        for (Map.Entry<Long, List<String>> g : kbGroups.entrySet()) {
            for (String kbId : g.getValue()) {
                List<String> requested = mergeUnique(null, byKb.get(kbId));
                List<Map<String, Object>> tags = questionMapper.findTags(g.getKey(), requested);
                Set<String> requestedSet = new HashSet<>(requested);
                List<String> valid = new ArrayList<>();
                for (Map<String, Object> t : tags) {
                    if (kbId.equals(t.get("knowledgeBaseId"))
                            && requestedSet.contains((String) t.get("id"))) {
                        valid.add((String) t.get("id"));
                    }
                }
                if (valid.isEmpty()) {
                    continue;
                }
                outKbIds = mergeUnique(outKbIds, List.of(kbId));
                tagIdsByTenant.computeIfAbsent(g.getKey(), k -> new ArrayList<>())
                        .addAll(mergeUnique(null, valid));
                List<String> knowledge = questionMapper.listKnowledgeIdsByTagIds(
                        g.getKey(), kbId, valid);
                outKnowledgeIds = mergeUnique(outKnowledgeIds, knowledge);
            }
        }
        return new ResolvedTags(outKbIds, outKnowledgeIds, tagIdsByTenant);
    }

    /** 本空间 KB → 调用者租户；不可达 → 静默丢弃。 */
    private Map<Long, List<String>> groupKbIdsByEffectiveTenant(List<String> kbIds) {
        Map<Long, List<String>> out = new LinkedHashMap<>();
        if (kbIds == null || kbIds.isEmpty()) {
            return out;
        }
        List<Map<String, Object>> rows = questionMapper.findKbs(kbIds);
        Map<String, Long> tenantByKb = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            tenantByKb.put((String) row.get("id"), ((Number) row.get("tenantId")).longValue());
        }
        for (String kbId : kbIds) {
            Long kbTenant = tenantByKb.get(kbId);
            if (kbTenant == null) {
                continue;
            }
            out.computeIfAbsent(kbTenant, k -> new ArrayList<>()).add(kbId);
        }
        return out;
    }

    // ═══════════════════ 小工具 ═══════════════════

    public static ObjectNode parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return MAPPER.createObjectNode();
        }
        try {
            JsonNode n = MAPPER.readTree(raw);
            return n == null || n.isNull() || !n.isObject()
                    ? MAPPER.createObjectNode() : (ObjectNode) n;
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    /** system-{tenantId} 合成用户不落 created_by。 */
    public static boolean isSyntheticUserId(String uid) {
        return uid != null && uid.startsWith("system-");
    }

    public static List<String> orEmpty(List<String> in) {
        return in == null ? List.of() : in;
    }

    private static List<String> flattenTagScopeIds(List<TagScope> scopes) {
        List<String> ids = null;
        if (scopes != null) {
            for (TagScope s : scopes) {
                ids = mergeUnique(ids, s.tagIds());
            }
        }
        return ids == null ? List.of() : ids;
    }

    private static List<String> intersect(List<String> values, List<String> allowed) {
        if (values == null || values.isEmpty() || allowed == null || allowed.isEmpty()) {
            return List.of();
        }
        Set<String> allowedSet = new HashSet<>(allowed);
        List<String> out = new ArrayList<>();
        for (String v : values) {
            if (v != null && !v.isEmpty() && allowedSet.contains(v)) {
                out.add(v);
            }
        }
        return out;
    }

    private static List<String> excludeStrings(List<String> values, List<String> excluded) {
        if (values == null || values.isEmpty() || excluded == null || excluded.isEmpty()) {
            return values == null ? List.of() : values;
        }
        Set<String> excludedSet = new HashSet<>(excluded);
        List<String> out = new ArrayList<>();
        for (String v : values) {
            if (v != null && !v.isEmpty() && !excludedSet.contains(v)) {
                out.add(v);
            }
        }
        return out;
    }

    private static List<String> mergeUnique(List<String> base, List<String> extra) {
        if (extra == null || extra.isEmpty()) {
            return base;
        }
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        if (base != null) {
            for (String s : base) {
                if (s == null || s.isEmpty() || seen.contains(s)) {
                    continue;
                }
                seen.add(s);
                out.add(s);
            }
        }
        for (String s : extra) {
            if (s == null || s.isEmpty() || seen.contains(s)) {
                continue;
            }
            seen.add(s);
            out.add(s);
        }
        return out;
    }

    public static String currentLocale() {
        return com.ragagent.common.wiki.WikiLanguageSupport.defaultLanguage();
    }
}
