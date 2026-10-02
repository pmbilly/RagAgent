package com.ragagent.agent.tools.wiki;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.ragagent.agent.tools.SearchAuth;

/** wiki 路由解析：页面唯一解析、创建目标 KB 决策、source ref 解析、issue 解析。 */
    public final class WikiRouteResolver {
        private final Map<String, Set<String>> bySlug = new ConcurrentHashMap<>();

        public void remember(String slug, String kbId) {
            if (slug == null || slug.isEmpty() || kbId == null || kbId.isEmpty()) {
                return;
            }
            bySlug.computeIfAbsent(slug, k -> ConcurrentHashMap.newKeySet()).add(kbId);
        }

        public void forget(String slug, String kbId) {
            if (slug == null || slug.isEmpty() || kbId == null || kbId.isEmpty()) {
                return;
            }
            Set<String> owners = bySlug.get(slug);
            if (owners == null) {
                return;
            }
            owners.remove(kbId);
            if (owners.isEmpty()) {
                bySlug.remove(slug, owners);
            }
        }

        /** 记住页的 slug + 出链 + 入链。 */
        public void rememberPage(PageView page, String kbId) {
            if (page == null || kbId == null || kbId.isEmpty()) {
                return;
            }
            remember(page.slug(), kbId);
            for (String slug : page.outLinks()) {
                remember(slug, kbId);
            }
            for (String slug : page.inLinks()) {
                remember(slug, kbId);
            }
        }

        /**
         * 仍在本作用域内的缓存 owner；空结果 = 调用方搜全部作用域。
         * 注意：缓存遍历序不保证确定，resolveUniqueWikiPage 的多 scope 歧义判定
         * 受"排序影响查找序"影响（已知差异点）。
         */
        public List<WikiScope> scopesForSlug(String slug, List<WikiScope> scopes) {
            if (slug == null || slug.isEmpty() || scopes == null || scopes.isEmpty()) {
                return List.of();
            }
            Set<String> owners = bySlug.get(slug);
            if (owners == null || owners.isEmpty()) {
                return List.of();
            }
            List<WikiScope> matched = new ArrayList<>();
            for (WikiScope scope : scopes) {
                if (owners.contains(scope.knowledgeBaseId())) {
                    matched.add(scope);
                }
            }
            return matched;
        }




    /**
     * 共享变更/issue 的唯一页路由。检查每个允许的 KB
     * （缓存 provenance 只影响序），拒绝 ambiguous slug。
     */
    public static ResolvedPage resolveUniqueWikiPage(
            WikiPages service, String slug, List<String> kbIds, WikiRouteResolver routes) {
        slug = slug == null ? "" : slug.trim();
        if (slug.isEmpty()) {
            throw new IllegalArgumentException("slug is required");
        }
        List<WikiScope> scopes = WikiScope.newWikiScopesFromKbIds(kbIds);
        List<WikiScope> preferred = routes.scopesForSlug(slug, scopes);
        List<WikiScope> ordered = new ArrayList<>(preferred);
        ordered.addAll(WikiScope.scopesOutsideKbs(scopes, preferred));

        List<ResolvedPage> hits = new ArrayList<>();
        for (WikiScope scope : ordered) {
            PageView page;
            try {
                page = service.getPageBySlug(scope.knowledgeBaseId(), slug);
            } catch (RuntimeException e) {
                throw new IllegalStateException("failed to resolve wiki page " + slug
                        + " in knowledge base " + scope.knowledgeBaseId() + ": " + e.getMessage());
            }
            if (page == null) {
                continue;
            }
            if (page.knowledgeBaseId() != null && !page.knowledgeBaseId().isEmpty()
                    && !page.knowledgeBaseId().equals(scope.knowledgeBaseId())) {
                throw new IllegalStateException("wiki page " + slug + " returned knowledge base "
                        + page.knowledgeBaseId() + " while resolving allowed scope " + scope.knowledgeBaseId());
            }
            String kbId = scope.knowledgeBaseId();
            hits.add(new ResolvedPage(page, kbId));
            routes.rememberPage(page, kbId);
        }
        switch (hits.size()) {
            case 0 -> throw new IllegalArgumentException(ERR_PAGE_NOT_FOUND_IN_SCOPE + ": " + slug);
            case 1 -> {
                return hits.get(0);
            }
            default -> {
                List<String> owners = new ArrayList<>();
                for (ResolvedPage item : hits) {
                    owners.add(item.kbId());
                }
                throw new IllegalStateException(
                        ERR_PAGE_AMBIGUOUS + ": slug " + slug + " belongs to " + String.join(", ", owners));
            }
        }
    }

    /** 创建路由：只有一个候选 KB 时才允许创建。 */
    public static String resolveWikiCreateKb(String slug, List<String> kbIds,
                                             WikiRouteResolver routes, List<String> serverHints) {
        List<WikiScope> scopes = WikiScope.newWikiScopesFromKbIds(kbIds);
        List<WikiScope> preferred = routes.scopesForSlug(slug == null ? "" : slug.trim(), scopes);
        Set<String> allowed = new java.util.HashSet<>();
        for (WikiScope scope : scopes) {
            allowed.add(scope.knowledgeBaseId());
        }
        List<String> candidates = new ArrayList<>();
        for (WikiScope scope : preferred) {
            candidates.add(scope.knowledgeBaseId());
        }
        if (serverHints != null) {
            for (String kbId : serverHints) {
                if (allowed.contains(kbId)) {
                    candidates.add(kbId);
                }
            }
        }
        candidates = SearchAuth.dedupNonEmptyStrings(candidates);
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.size() > 1) {
            throw new IllegalStateException("cannot choose a knowledge base for new wiki page " + slug
                    + ": server provenance conflicts across " + String.join(", ", candidates));
        }
        if (scopes.size() == 1) {
            return scopes.get(0).knowledgeBaseId();
        }
        throw new IllegalStateException("cannot choose a knowledge base for new wiki page " + slug
                + " from " + scopes.size() + " allowed scopes");
    }

    /**
     * 从 source_refs 里解析出知识所属的 KB
     * （限 allowedKBIDs），作为创建路由的服务端提示。
     */
    public static List<String> wikiKnowledgeBasesForSourceRefs(
            List<String> refs, SearchAuth.KnowledgeScopeReader knowledgeService, List<String> allowedKbIds) {
        if (refs == null || refs.isEmpty()) {
            return null;
        }
        if (knowledgeService == null) {
            throw new IllegalStateException("knowledge service is unavailable");
        }
        Set<String> allowed = Set.copyOf(SearchAuth.dedupNonEmptyStrings(allowedKbIds));
        List<String> kbIds = new ArrayList<>();
        for (String ref : refs) {
            if (ref == null) {
                continue;
            }
            String knowledgeId = ref.split("\\|", 2)[0].trim();
            if (knowledgeId.isEmpty()) {
                continue;
            }
            SearchAuth.KnowledgeView knowledge;
            try {
                knowledge = knowledgeService.byIdOnly(knowledgeId);
            } catch (RuntimeException e) {
                throw new IllegalStateException("failed to resolve source document " + knowledgeId + ": " + e.getMessage());
            }
            if (knowledge == null) {
                throw new IllegalStateException("failed to resolve source document " + knowledgeId + ": empty result");
            }
            if (!allowed.contains(knowledge.knowledgeBaseId())) {
                throw new IllegalStateException("source document " + knowledgeId
                        + " belongs to non-Wiki or unauthorized knowledge base " + knowledge.knowledgeBaseId());
            }
            kbIds.add(knowledge.knowledgeBaseId());
        }
        return SearchAuth.dedupNonEmptyStrings(kbIds);
    }

    /** 按 ID 解析 issue（校验 scope）。 */
    public static IssueView resolveWikiIssue(WikiPages service, String issueId, List<String> kbIds) {
        issueId = issueId == null ? "" : issueId.trim();
        if (issueId.isEmpty()) {
            throw new IllegalArgumentException("issue_id is required");
        }
        IssueView match = null;
        for (String kbId : SearchAuth.dedupNonEmptyStrings(kbIds)) {
            List<IssueView> issues = service.listIssues(kbId, "", "");
            for (IssueView issue : issues) {
                if (issue == null || !issueId.equals(issue.id())) {
                    continue;
                }
                if (issue.knowledgeBaseId() != null && !issue.knowledgeBaseId().isEmpty()
                        && !issue.knowledgeBaseId().equals(kbId)) {
                    throw new IllegalStateException("issue_id " + issueId + " returned knowledge base "
                            + issue.knowledgeBaseId() + " while resolving allowed scope " + kbId);
                }
                if (match != null) {
                    throw new IllegalStateException("issue_id " + issueId + " is ambiguous across current Wiki scopes");
                }
                match = issue;
            }
        }
        if (match == null) {
            throw new IllegalArgumentException("issue_id " + issueId + " is not within the current Wiki scope");
        }
        return match;
    }

    /** 无鉴权路径的 source_refs 富化（"uuid|title"）。 */
    public static List<String> resolveSourceRefs(List<String> refs, SearchAuth.KnowledgeScopeReader knowledgeService) {
        if (refs == null || refs.isEmpty() || knowledgeService == null) {
            return refs;
        }
        List<String> resolved = new ArrayList<>();
        for (String ref : refs) {
            if (ref.contains("|")) {
                resolved.add(ref);
                continue;
            }
            SearchAuth.KnowledgeView kn;
            try {
                kn = knowledgeService.byIdOnly(ref);
            } catch (RuntimeException e) {
                resolved.add(ref);
                continue;
            }
            if (kn == null) {
                resolved.add(ref);
                continue;
            }
            String title = kn.title();
            if (title == null || title.isEmpty()) {
                title = kn.fileName();
            }
            if (title != null && !title.isEmpty()) {
                resolved.add(ref + "|" + title);
            } else {
                resolved.add(ref);
            }
        }
        return resolved;
    }

    /** "页不在 scope 内"的哨兵错误文案。 */
    public static final String ERR_PAGE_NOT_FOUND_IN_SCOPE = "wiki page not found in current scope";
    /** "页名歧义"的哨兵错误文案。 */
    public static final String ERR_PAGE_AMBIGUOUS = "wiki page exists in multiple knowledge bases";
}
