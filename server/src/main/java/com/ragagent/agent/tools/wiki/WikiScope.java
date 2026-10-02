package com.ragagent.agent.tools.wiki;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.ragagent.agent.tools.SearchTarget.SearchTargets;
import com.ragagent.agent.tools.SearchAuth;
import com.ragagent.agent.tools.SearchTarget;

/** wiki 检索范围（@选择/KB 集合）工厂与页面-范围匹配谓词。 */
    public record WikiScope(String knowledgeBaseId, List<String> knowledgeIds, List<String> tagIds) {
        public static WikiScope kb(String knowledgeBaseId) {
            return new WikiScope(knowledgeBaseId, null, null);
        }



    /** 从 KB ID 列表构造 scope（防御性去重——重复 KB ID 会把唯一 slug 误报成 ambiguous）。 */
    public static List<WikiScope> newWikiScopesFromKbIds(List<String> kbIds) {
        List<WikiScope> scopes = new ArrayList<>();
        for (String id : SearchAuth.dedupNonEmptyStrings(kbIds)) {
            scopes.add(WikiScope.kb(id));
        }
        return scopes;
    }

    /**
     * 从检索目标构造 scope：同一 Wiki KB 的所有 target 合并成一个 scope
     * （并集语义）；整库 target 覆盖窄目标；畸形空文档 target 不会悄悄变成整库授权。
     */
    public static List<WikiScope> newWikiScopesFromSearchTargets(SearchTargets searchTargets, List<String> wikiKbIds) {
        Set<String> allowed = Set.copyOf(SearchAuth.dedupNonEmptyStrings(wikiKbIds));
        Map<String, Accumulated> byKb = new LinkedHashMap<>();
        for (SearchTarget target : searchTargets.list()) {
            if (target == null || target.knowledgeBaseId() == null || !allowed.contains(target.knowledgeBaseId())) {
                continue;
            }
            SearchAuth.Scope scope = SearchAuth.searchTargetScope(target);
            boolean wholeKb = SearchAuth.searchTargetIsWholeKb(target);
            List<String> kIDs = scope.knowledgeIds() != null ? scope.knowledgeIds() : List.of();
            List<String> tIDs = scope.tagIds() != null ? scope.tagIds() : List.of();
            if (!wholeKb && kIDs.isEmpty() && tIDs.isEmpty()) {
                continue;
            }
            Accumulated acc = byKb.computeIfAbsent(target.knowledgeBaseId(),
                    k -> new Accumulated(new WikiScope(k, new ArrayList<>(), new ArrayList<>())));
            if (wholeKb) {
                acc.unrestricted = true;
                continue;
            }
            acc.scope.knowledgeIds().addAll(kIDs);
            acc.scope.tagIds().addAll(tIDs);
        }

        List<WikiScope> scopes = new ArrayList<>();
        for (String kbId : SearchAuth.dedupNonEmptyStrings(wikiKbIds)) {
            Accumulated acc = byKb.get(kbId);
            if (acc == null) {
                continue;
            }
            if (acc.unrestricted) {
                scopes.add(WikiScope.kb(kbId));
                continue;
            }
            scopes.add(new WikiScope(kbId,
                    SearchAuth.dedupNonEmptyStrings(acc.scope.knowledgeIds()),
                    SearchAuth.dedupNonEmptyStrings(acc.scope.tagIds())));
        }
        return scopes;
    }

    private static final class Accumulated {
        final WikiScope scope;
        boolean unrestricted;

        Accumulated(WikiScope scope) {
            this.scope = scope;
        }
    }

    /** scope 的 knowledge_ids 过滤集（null = 无过滤）。 */
    public static Map<String, Boolean> scopeKnowledgeFilter(WikiScope scope) {
        Map<String, Boolean> set = new LinkedHashMap<>();
        if (scope.knowledgeIds() == null || scope.knowledgeIds().isEmpty()) {
            return Map.of();
        }
        for (String id : scope.knowledgeIds()) {
            if (id != null && !id.isEmpty()) {
                set.put(id, Boolean.TRUE);
            }
        }
        return set;
    }

    /** 不在给定 KB 集内的 scope。 */
    public static List<WikiScope> scopesOutsideKbs(List<WikiScope> scopes, List<WikiScope> excluded) {
        if (excluded == null || excluded.isEmpty()) {
            return scopes;
        }
        Set<String> excludedKbs = new java.util.HashSet<>();
        for (WikiScope scope : excluded) {
            excludedKbs.add(scope.knowledgeBaseId());
        }
        List<WikiScope> remaining = new ArrayList<>();
        for (WikiScope scope : scopes) {
            if (!excludedKbs.contains(scope.knowledgeBaseId())) {
                remaining.add(scope);
            }
        }
        return remaining;
    }

    /** 结构页（index 等）不受 knowledge_ids scope 过滤。 */
    public static boolean isStructuralPage(PageView page) {
        return page != null && WikiIndexOverview.WIKI_PAGE_TYPE_INDEX.equals(page.pageType());
    }

    /** SourceRefs（"uuid" / "uuid|title"）→ 裸 knowledge ID。 */
    public static List<String> extractSourceKnowledgeIDs(PageView page) {
        List<String> ids = new ArrayList<>();
        if (page == null || page.sourceRefs() == null) {
            return ids;
        }
        for (String ref : page.sourceRefs()) {
            if (ref == null) {
                continue;
            }
            String kid = ref;
            int pipe = ref.indexOf('|');
            if (pipe > 0) {
                kid = ref.substring(0, pipe);
            }
            if (!kid.isEmpty()) {
                ids.add(kid);
            }
        }
        return ids;
    }

    /** 页的源知识是否与 scope 过滤集相交。 */
    public static boolean pageIntersectsKnowledgeIDs(PageView page, Map<String, Boolean> allowed) {
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        for (String kid : extractSourceKnowledgeIDs(page)) {
            if (Boolean.TRUE.equals(allowed.get(kid))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 返回页是否通过 scope。tag 查询失败时
     * fetchTags 的 RuntimeException 自然外抛（调用方捕获后拼 errs 文案）。
     */
    public static boolean pagePassesWikiScope(PageView page, WikiScope scope,
            SearchAuth.KnowledgeTagsFetcher fetchTags) {
        Map<String, Boolean> allowed = scopeKnowledgeFilter(scope);
        boolean hasKnowledgeFilter = !allowed.isEmpty();
        List<String> tagIDs = SearchAuth.dedupNonEmptyStrings(scope.tagIds());
        if (!hasKnowledgeFilter && tagIDs.isEmpty()) {
            return true;
        }
        // 文档/tag 受限 scope 下每页都必须证明出处；结构性页与无引用页不通过。
        if (isStructuralPage(page)) {
            return false;
        }
        List<String> sourceKnowledgeIDs = extractSourceKnowledgeIDs(page);
        if (sourceKnowledgeIDs.isEmpty()) {
            return false;
        }
        if (hasKnowledgeFilter && pageIntersectsKnowledgeIDs(page, allowed)) {
            return true;
        }
        if (tagIDs.isEmpty()) {
            return false;
        }
        Map<String, Boolean> matches =
                SearchAuth.knowledgeIdsMatchingAnyTag(sourceKnowledgeIDs, tagIDs, fetchTags);
        return !matches.isEmpty();
    }
}
