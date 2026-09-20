package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.tools.SearchTarget.SearchTargets;

/**
 * Wiki 工具族共享支撑（对照 Go {@code wiki_route_resolver.go} / {@code wiki_link_mutation.go} /
 * {@code wiki_write_page.go} 的 normalizeAndValidateWikiSlug/isSummaryNamespace /
 * {@code wiki_tools.go} 的 WikiScope/resolveSourceRefs，逐字移植）。
 *
 * <p>seam：{@link WikiPages} 函数式接口对照 interfaces.WikiPageService 的被用子集。
 * 契约（对齐 Go 的 error 约定）：{@code getPageBySlug} 返回 null = repository.ErrWikiPageNotFound
 * （resolveUniqueWikiPage 会跳过该 KB）；其余方法失败抛 RuntimeException。</p>
 *
 * <p>写操作的归因（Go 的 {@code types.WithWikiEditSource(ctx, WikiEditSourceAgent)}）在 Java
 * 无 ctx 可挂，改为写方法显式收 editSource 参数，工具恒传 {@link #WIKI_EDIT_SOURCE_AGENT}。</p>
 */
public final class WikiSupport {

    /** 对照 types.WikiEditSourceAgent。 */
    public static final String WIKI_EDIT_SOURCE_AGENT = "agent";
    /** 对照 types.WikiPageTypeSummary。 */
    public static final String WIKI_PAGE_TYPE_SUMMARY = "summary";

    /** 对照 errWikiPageNotFoundInScope（resolveUniqueWikiPage 的哨兵错误文案）。 */
    public static final String ERR_PAGE_NOT_FOUND_IN_SCOPE = "wiki page not found in current scope";
    /** 对照 errWikiPageAmbiguous。 */
    public static final String ERR_PAGE_AMBIGUOUS = "wiki page exists in multiple knowledge bases";

    private WikiSupport() {
    }

    // ==================== WikiScope（对照 wiki_tools.go:107） ====================

    /** 对照 WikiScope。 */
    public record WikiScope(String knowledgeBaseId, List<String> knowledgeIds, List<String> tagIds) {
        public static WikiScope kb(String knowledgeBaseId) {
            return new WikiScope(knowledgeBaseId, null, null);
        }
    }

    /** 对照 NewWikiScopesFromKBIDs（防御性去重——重复 KB ID 会把唯一 slug 误报成 ambiguous）。 */
    public static List<WikiScope> newWikiScopesFromKbIds(List<String> kbIds) {
        List<WikiScope> scopes = new ArrayList<>();
        for (String id : SearchAuth.dedupNonEmptyStrings(kbIds)) {
            scopes.add(WikiScope.kb(id));
        }
        return scopes;
    }

    /**
     * 对照 NewWikiScopesFromSearchTargets：同一 Wiki KB 的所有 target 合并成一个 scope
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

    /** 对照 scopeKnowledgeFilter：返回 (filterSet, hasFilter)。 */
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

    // ==================== WikiRouteResolver（对照 wiki_route_resolver.go） ====================

    /** 请求级 slug→KB 路由记忆（对照 WikiRouteResolver；Go 指针接收者 nil 语义 → 本类恒非 null）。 */
    public static final class WikiRouteResolver {
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

        /** 对照 rememberPage：slug + OutLinks + InLinks 全部记住。 */
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
         * 对照 scopesForSlug：仍在本作用域内的缓存 owner；空结果 = 调用方搜全部作用域。
         * 注意：ConcurrentHashMap 的 keySet 无序——Go 的 map 同样无序，二者都不保证序，
         * resolveUniqueWikiPage 只受"排序影响查找序"的潜在差异约束（见报告已知差异）。
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
    }

    /** 对照 scopesOutsideKBs。 */
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

    /** 页 + 命中 KB（对照 resolveUniqueWikiPage 的匿名 hit struct）。 */
    public record ResolvedPage(PageView page, String kbId) {
    }

    /**
     * 对照 resolveUniqueWikiPage：共享变更/issue 路由边界。检查每个允许的 KB
     * （缓存 provenance 只影响序），拒绝 ambiguous slug。
     */
    public static ResolvedPage resolveUniqueWikiPage(
            WikiPages service, String slug, List<String> kbIds, WikiRouteResolver routes) {
        slug = slug == null ? "" : slug.trim();
        if (slug.isEmpty()) {
            throw new IllegalArgumentException("slug is required");
        }
        List<WikiScope> scopes = newWikiScopesFromKbIds(kbIds);
        List<WikiScope> preferred = routes.scopesForSlug(slug, scopes);
        List<WikiScope> ordered = new ArrayList<>(preferred);
        ordered.addAll(scopesOutsideKbs(scopes, preferred));

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

    /** 对照 resolveWikiCreateKB：只有一个候选时才允许创建。 */
    public static String resolveWikiCreateKb(String slug, List<String> kbIds,
                                             WikiRouteResolver routes, List<String> serverHints) {
        List<WikiScope> scopes = newWikiScopesFromKbIds(kbIds);
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
     * 对照 wikiKnowledgeBasesForSourceRefs：从 source_refs 里解析出知识所属的 KB
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

    /** 对照 resolveWikiIssue。 */
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

    // ==================== link mutation（对照 wiki_link_mutation.go） ====================

    /** 对照 wikiContentRewrite func(string) (string, bool)。 */
    @FunctionalInterface
    public interface WikiContentRewrite {
        RewriteResult apply(String content);
    }

    /** 重写结果（Java 无多返回值）。 */
    public record RewriteResult(String content, boolean changed) {
    }

    /** 已应用的变更（对照 appliedWikiContentChange）。 */
    public record AppliedChange(PageView page, String originalContent) {
    }

    /** 重写半途失败：携带已应用的变更供调用方回滚（对照 Go 的多返回值）。 */
    public static final class WikiRewriteException extends RuntimeException {
        private final List<AppliedChange> changes;

        public WikiRewriteException(String message, List<AppliedChange> changes) {
            super(message);
            this.changes = changes;
        }

        public List<AppliedChange> changes() {
            return changes;
        }
    }

    /**
     * 对照 applyIncomingWikiContentRewrite：更新机器维护的链接（不递增页面版本）。
     * 首个失败即停，返回已应用的变更供调用方补偿。
     */
    public static List<AppliedChange> applyIncomingWikiContentRewrite(
            WikiPages service, String kbId, List<String> inLinks, String editSource,
            WikiContentRewrite rewrite, List<String> updatedSlugsOut) {
        List<AppliedChange> changes = new ArrayList<>();
        for (String sourceSlug : SearchAuth.dedupNonEmptyStrings(inLinks)) {
            PageView page = service.getPageBySlug(kbId, sourceSlug);
            if (page == null) {
                throw new WikiRewriteException(
                        "load incoming page " + sourceSlug + ": empty result", changes);
            }
            RewriteResult result = rewrite.apply(page.content());
            if (!result.changed()) {
                continue;
            }
            String original = page.content();
            page.setContent(result.content());
            try {
                service.updateAutoLinkedContent(page, editSource);
            } catch (RuntimeException e) {
                page.setContent(original);
                throw new WikiRewriteException(
                        "update incoming page " + sourceSlug + ": " + e.getMessage(), changes);
            }
            changes.add(new AppliedChange(page, original));
            updatedSlugsOut.add(sourceSlug);
        }
        return changes;
    }

    /** 对照 rollbackWikiContentChanges：倒序回滚；失败聚合。 */
    public static void rollbackWikiContentChanges(WikiPages service, List<AppliedChange> changes, String editSource) {
        List<String> failures = new ArrayList<>();
        for (int i = changes.size() - 1; i >= 0; i--) {
            AppliedChange change = changes.get(i);
            change.page().setContent(change.originalContent());
            try {
                service.updateAutoLinkedContent(change.page(), editSource);
            } catch (RuntimeException e) {
                failures.add(change.page().slug() + ": " + e.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("failed to roll back incoming pages: " + String.join("; ", failures));
        }
    }

    /** 对照 joinWikiMutationErrors。 */
    public static String joinWikiMutationErrors(String primary, String... extras) {
        List<String> parts = new ArrayList<>();
        parts.add(primary);
        for (String extra : extras) {
            if (extra != null && !extra.isEmpty()) {
                parts.add(extra);
            }
        }
        return String.join("; ", parts);
    }

    // ==================== slug 工具（对照 wiki_write_page.go:252 起） ====================

    /**
     * 对照 normalizeAndValidateWikiSlug：lowercase + trim + 空格转 '-'；
     * 只许小写字母/数字/'-'/'/'/CJK（0x4E00-0x9FFF）；拒绝空、前/后/重复 '/'。
     */
    public static String normalizeAndValidateWikiSlug(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase();
        s = s.replace(" ", "-");
        if (s.isEmpty()) {
            throw new IllegalArgumentException("slug is required and must be non-empty");
        }
        if (s.contains("//") || s.startsWith("/") || s.endsWith("/")) {
            throw new IllegalArgumentException("invalid slug \"" + raw + "\": '/' separators are malformed");
        }
        for (int i = 0; i < s.length(); i++) {
            char r = s.charAt(i);
            boolean ok = (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || r == '-' || r == '/'
                    || (r >= 0x4E00 && r <= 0x9FFF);
            if (!ok) {
                throw new IllegalArgumentException("invalid slug \"" + raw + "\": character \"" + r
                        + "\" is not allowed (use lowercase letters, digits, '-', '/', or CJK)");
            }
        }
        return s;
    }

    /** 对照 isSummaryNamespace。 */
    public static boolean isSummaryNamespace(String slug) {
        return slug != null && slug.startsWith(WIKI_PAGE_TYPE_SUMMARY + "/");
    }

    /** 对照 wiki_tools.go resolveSourceRefs：无鉴权路径，纯富化 "uuid|title"。 */
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

    // ==================== seam 视图 ====================

    /** 页视图（对照 types.WikiPage 被用字段；可变，对齐 Go 的指针修改）。 */
    public static final class PageView {
        private String id = "";
        private long tenantId;
        private String knowledgeBaseId = "";
        private String slug = "";
        private String title = "";
        private String pageType = "";
        private String status = "";
        private String content = "";
        private String summary = "";
        private List<String> aliases = new ArrayList<>();
        private String parentSlug = "";
        private String folderId = "";
        private int sortOrder;
        private List<String> sourceRefs = new ArrayList<>();
        private List<String> chunkRefs = new ArrayList<>();
        private List<String> inLinks = new ArrayList<>();
        private List<String> outLinks = new ArrayList<>();
        private String pageMetadata = "";

        public static PageView of(String kbId, String slug) {
            PageView p = new PageView();
            p.knowledgeBaseId = kbId;
            p.slug = slug;
            return p;
        }

        public String id() { return id; }
        public void setId(String v) { id = v; }
        public long tenantId() { return tenantId; }
        public void setTenantId(long v) { tenantId = v; }
        public String knowledgeBaseId() { return knowledgeBaseId; }
        public void setKnowledgeBaseId(String v) { knowledgeBaseId = v; }
        public String slug() { return slug; }
        public void setSlug(String v) { slug = v; }
        public String title() { return title; }
        public void setTitle(String v) { title = v; }
        public String pageType() { return pageType; }
        public void setPageType(String v) { pageType = v; }
        public String status() { return status; }
        public void setStatus(String v) { status = v; }
        public String content() { return content; }
        public void setContent(String v) { content = v; }
        public String summary() { return summary; }
        public void setSummary(String v) { summary = v; }
        public List<String> aliases() { return aliases; }
        public void setAliases(List<String> v) { aliases = v != null ? v : new ArrayList<>(); }
        public String parentSlug() { return parentSlug; }
        public void setParentSlug(String v) { parentSlug = v; }
        public String folderId() { return folderId; }
        public void setFolderId(String v) { folderId = v; }
        public int sortOrder() { return sortOrder; }
        public void setSortOrder(int v) { sortOrder = v; }
        public List<String> sourceRefs() { return sourceRefs; }
        public void setSourceRefs(List<String> v) { sourceRefs = v != null ? v : new ArrayList<>(); }
        public List<String> chunkRefs() { return chunkRefs; }
        public void setChunkRefs(List<String> v) { chunkRefs = v != null ? v : new ArrayList<>(); }
        public List<String> inLinks() { return inLinks; }
        public void setInLinks(List<String> v) { inLinks = v != null ? v : new ArrayList<>(); }
        public List<String> outLinks() { return outLinks; }
        public void setOutLinks(List<String> v) { outLinks = v != null ? v : new ArrayList<>(); }
        public String pageMetadata() { return pageMetadata; }
        public void setPageMetadata(String v) { pageMetadata = v; }

        /** 深拷贝（对照 rename 里对旧页字段的逐字段复制）。 */
        public PageView copy() {
            PageView p = new PageView();
            p.id = id;
            p.tenantId = tenantId;
            p.knowledgeBaseId = knowledgeBaseId;
            p.slug = slug;
            p.title = title;
            p.pageType = pageType;
            p.status = status;
            p.content = content;
            p.summary = summary;
            p.aliases = new ArrayList<>(aliases);
            p.parentSlug = parentSlug;
            p.folderId = folderId;
            p.sortOrder = sortOrder;
            p.sourceRefs = new ArrayList<>(sourceRefs);
            p.chunkRefs = new ArrayList<>(chunkRefs);
            p.inLinks = new ArrayList<>(inLinks);
            p.outLinks = new ArrayList<>(outLinks);
            p.pageMetadata = pageMetadata;
            return p;
        }
    }

    /** issue 视图（对照 types.WikiPageIssue；时间字段以 Go RFC3339 文本透传保证逐字）。 */
    public static final class IssueView {
        private String id = "";
        private long tenantId;
        private String knowledgeBaseId = "";
        private String slug = "";
        private String issueType = "";
        private String description = "";
        private List<String> suspectedKnowledgeIds = new ArrayList<>();
        private String status = "";
        private String reportedBy = "";
        private String createdAt = "";
        private String updatedAt = "";
        private boolean deletedAtValid;
        private String deletedAt = "";

        public String id() { return id; }
        public void setId(String v) { id = v; }
        public long tenantId() { return tenantId; }
        public void setTenantId(long v) { tenantId = v; }
        public String knowledgeBaseId() { return knowledgeBaseId; }
        public void setKnowledgeBaseId(String v) { knowledgeBaseId = v; }
        public String slug() { return slug; }
        public void setSlug(String v) { slug = v; }
        public String issueType() { return issueType; }
        public void setIssueType(String v) { issueType = v; }
        public String description() { return description; }
        public void setDescription(String v) { description = v; }
        public List<String> suspectedKnowledgeIds() { return suspectedKnowledgeIds; }
        public void setSuspectedKnowledgeIds(List<String> v) { suspectedKnowledgeIds = v != null ? v : new ArrayList<>(); }
        public String status() { return status; }
        public void setStatus(String v) { status = v; }
        public String reportedBy() { return reportedBy; }
        public void setReportedBy(String v) { reportedBy = v; }
        public String createdAt() { return createdAt; }
        public void setCreatedAt(String v) { createdAt = v; }
        public String updatedAt() { return updatedAt; }
        public void setUpdatedAt(String v) { updatedAt = v; }
        public boolean deletedAtValid() { return deletedAtValid; }
        public void setDeletedAtValid(boolean v) { deletedAtValid = v; }
        public String deletedAt() { return deletedAt; }
        public void setDeletedAt(String v) { deletedAt = v; }

        /**
         * 对照 json.MarshalIndent(issue, "", "  ") 的字节形态。
         * gorm.DeletedAt 无效时序列化为 null。
         */
        public String toGoJsonIndent() {
            // 对照 json.MarshalIndent(issue, "", "  ")：嵌套数组非空时逐元素换行缩进
            StringBuilder b = new StringBuilder();
            b.append("{\n");
            b.append("  \"id\": ").append(goJsonString(id)).append(",\n");
            b.append("  \"tenant_id\": ").append(tenantId).append(",\n");
            b.append("  \"knowledge_base_id\": ").append(goJsonString(knowledgeBaseId)).append(",\n");
            b.append("  \"slug\": ").append(goJsonString(slug)).append(",\n");
            b.append("  \"issue_type\": ").append(goJsonString(issueType)).append(",\n");
            b.append("  \"description\": ").append(goJsonString(description)).append(",\n");
            b.append("  \"suspected_knowledge_ids\": ");
            if (suspectedKnowledgeIds == null || suspectedKnowledgeIds.isEmpty()) {
                b.append("[]");
            } else {
                b.append("[\n");
                for (int i = 0; i < suspectedKnowledgeIds.size(); i++) {
                    if (i > 0) {
                        b.append(",\n");
                    }
                    b.append("    ").append(goJsonString(suspectedKnowledgeIds.get(i)));
                }
                b.append("\n  ]");
            }
            b.append(",\n");
            b.append("  \"status\": ").append(goJsonString(status)).append(",\n");
            b.append("  \"reported_by\": ").append(goJsonString(reportedBy)).append(",\n");
            b.append("  \"created_at\": ").append(goJsonString(createdAt)).append(",\n");
            b.append("  \"updated_at\": ").append(goJsonString(updatedAt)).append(",\n");
            b.append("  \"deleted_at\": ").append(deletedAtValid ? goJsonString(deletedAt) : "null").append("\n");
            b.append('}');
            return b.toString();
        }

        private static String goJsonString(String s) {
            StringBuilder sb = new StringBuilder();
            GoJsonCodec.writeString(s == null ? "" : s, sb);
            return sb.toString();
        }
    }

    /** 对照 RepairContentLinks 的 (repaired, changed) 返回。 */
    public record RepairResult(String repaired, boolean changed) {
    }

    /**
     * Wiki 页服务接缝（对照 interfaces.WikiPageService 被用子集）。
     * 失败一律抛 RuntimeException；getPageBySlug 返回 null = 页不存在（ErrWikiPageNotFound）。
     */
    public interface WikiPages {
        PageView getPageBySlug(String kbId, String slug);

        PageView createPage(PageView page, String editSource);

        void updatePage(PageView page, String editSource);

        /** 对照 UpdateAutoLinkedContent：机器维护链接更新（不递增版本）。 */
        void updateAutoLinkedContent(PageView page, String editSource);

        void deletePage(String kbId, String slug, String editSource);

        /** 对照 RepairContentLinks；返回 null 表示服务不可用（Go rerr != nil 时静默跳过）。 */
        RepairResult repairContentLinks(String kbId, String slug, String content);

        void injectCrossLinks(String kbId, List<String> slugs);

        /** 对照 RebuildIndexPage：返回值恒被忽略。 */
        void rebuildIndexPage(String kbId);

        List<IssueView> listIssues(String kbId, String slug, String status);

        IssueView createIssue(IssueView issue);

        void updateIssueStatus(String issueId, String status);

        /**
         * 对照 SearchPages（wiki_search 用）。失败抛 RuntimeException
         * （工具侧拼 "Wiki search %q failed in KB %s: %v"）。
         */
        default List<PageView> searchPages(String kbId, String query, int limit) {
            throw new UnsupportedOperationException("searchPages");
        }

        /**
         * 对照 GetIndexView(ctx, kbID, nil, topK, "")（wiki_read_page 的 index 特判用）。
         * 返回 null = 无 overview（Go err != nil || overview == nil 时静默跳过）。
         */
        default IndexOverviewView getIndexView(String kbId, int topK) {
            return null;
        }
    }

    // ==================== wiki_tools.go 共享 helper ====================

    /** 对照 wikiIndexAgentTopK。 */
    public static final int WIKI_INDEX_AGENT_TOP_K = 20;
    /** 对照 wikiMaxLinkSummaries。 */
    public static final int WIKI_MAX_LINK_SUMMARIES = 20;
    /** 对照 wikiLinkSummaryMaxRunes。 */
    public static final int WIKI_LINK_SUMMARY_MAX_RUNES = 150;
    /** 对照 wikiMinPageBody。 */
    public static final int WIKI_MIN_PAGE_BODY = 400;
    /** 对照 wikiBudgetReserve。 */
    public static final int WIKI_BUDGET_RESERVE = 600;

    /** 对照 types.WikiPageTypeEntity。 */
    public static final String WIKI_PAGE_TYPE_ENTITY = "entity";
    /** 对照 types.WikiPageTypeConcept。 */
    public static final String WIKI_PAGE_TYPE_CONCEPT = "concept";
    /** 对照 types.WikiPageTypeIndex。 */
    public static final String WIKI_PAGE_TYPE_INDEX = "index";
    /** 对照 types.WikiPageTypeSynthesis。 */
    public static final String WIKI_PAGE_TYPE_SYNTHESIS = "synthesis";
    /** 对照 types.WikiPageTypeComparison。 */
    public static final String WIKI_PAGE_TYPE_COMPARISON = "comparison";

    /** 对照 isStructuralPage：index 页不受 knowledge_ids scope 过滤。 */
    public static boolean isStructuralPage(PageView page) {
        return page != null && WIKI_PAGE_TYPE_INDEX.equals(page.pageType());
    }

    /** 对照 extractSourceKnowledgeIDs：SourceRefs（"uuid" / "uuid|title"）→ 裸 knowledge ID。 */
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

    /** 对照 pageIntersectsKnowledgeIDs。 */
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
     * 对照 pagePassesWikiScope：返回是否通过 scope。tag 查询失败时
     * fetchTags 的 RuntimeException 自然外抛（调用方捕获后拼 errs 文案，
     * 对照 Go 的 (bool, error) 二返回）。
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

    /** 对照 parseStringOrArray：JSON 字符串或字符串数组 → 非空字符串列表。 */
    public static List<String> parseStringOrArray(JsonNode val) {
        List<String> result = new ArrayList<>();
        if (val == null || val.isNull()) {
            return result;
        }
        if (val.isTextual()) {
            String s = val.asText();
            if (!s.isEmpty()) {
                result.add(s);
            }
            return result;
        }
        if (val.isArray()) {
            for (JsonNode item : val) {
                if (item.isTextual()) {
                    String s = item.asText();
                    if (!s.isEmpty()) {
                        result.add(s);
                    }
                }
            }
        }
        return result;
    }

    /** 对照 truncateForSummary：首段（去 #/## 前缀），超长按 rune 截 + "..."。 */
    public static String truncateForSummary(String content, int maxLen) {
        String first = content == null ? "" : content;
        int para = first.indexOf("\n\n");
        if (para >= 0) {
            first = first.substring(0, para);
        }
        String summary = first.trim();
        if (summary.startsWith("# ")) {
            summary = summary.substring(2);
        } else if (summary.startsWith("## ")) {
            summary = summary.substring(3);
        }
        int runes = summary.codePointCount(0, summary.length());
        if (runes > maxLen) {
            return summary.substring(0, summary.offsetByCodePoints(0, maxLen)) + "...";
        }
        return summary;
    }

    /**
     * 对照 extractSnippet：(?i)+query 的首个匹配前后各 60 runes、匹配自身至多 100
     * runes，压缩空白后以 "... ... ..." 包裹。正则编译失败返回 ""（Go regexp ≠
     * java.util.regex：语法交集之外的 pattern 行为差异列入报告已知差异）。
     */
    public static String extractSnippet(String content, String query) {
        if (content == null || content.isEmpty() || query == null || query.isEmpty()) {
            return "";
        }
        final java.util.regex.Pattern pattern;
        try {
            pattern = java.util.regex.Pattern.compile("(?i)" + query);
        } catch (java.util.regex.PatternSyntaxException e) {
            return "";
        }
        java.util.regex.Matcher m = pattern.matcher(content);
        if (!m.find()) {
            return "";
        }
        String matchStr = m.group();
        String before = content.substring(0, m.start());
        String after = content.substring(m.end());

        String beforePart = lastRunes(before, 60);
        String afterPart = firstRunes(after, 60);
        String matchPart = firstRunes(matchStr, 100);

        String snippet = beforePart + matchPart + afterPart;
        snippet = snippet.replace("\n", " ");
        while (snippet.contains("  ")) {
            snippet = snippet.replace("  ", " ");
        }
        return "... " + snippet.trim() + " ...";
    }

    /** 前 n runes（对照 []rune(s)[:n]）。 */
    static String firstRunes(String s, int n) {
        if (s == null) {
            return "";
        }
        int count = s.codePointCount(0, s.length());
        if (count <= n) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, n));
    }

    /** 后 n runes（对照 []rune(s)[len-n:]）。 */
    static String lastRunes(String s, int n) {
        if (s == null) {
            return "";
        }
        int count = s.codePointCount(0, s.length());
        if (count <= n) {
            return s;
        }
        return s.substring(s.offsetByCodePoints(0, count - n));
    }

    /** 对照 renderIndexOverviewForAgent 的 WikiIndexResponse 被用子集。 */
    public record IndexOverviewView(String intro, List<IndexGroupView> groups) {
    }

    /** 对照 WikiIndexGroup 被用子集。 */
    public record IndexGroupView(String type, long total, List<IndexEntryView> items) {
    }

    /** 对照 WikiIndexEntry 被用子集。 */
    public record IndexEntryView(String slug, String title, String summary) {
    }

    /** 对照 renderIndexOverviewForAgent（逐字移植，含 "\n## " 裁剪与 top-K 标注）。 */
    public static String renderIndexOverviewForAgent(IndexOverviewView resp) {
        StringBuilder sb = new StringBuilder();
        String intro = resp.intro() == null ? "" : resp.intro().trim();
        int idx = intro.indexOf("\n## ");
        if (idx >= 0) {
            intro = intro.substring(0, idx).trim();
        }
        if (!intro.isEmpty()) {
            sb.append(intro).append('\n');
        }

        Map<String, String> typeLabels = new LinkedHashMap<>();
        typeLabels.put(WIKI_PAGE_TYPE_SUMMARY, "Summary");
        typeLabels.put(WIKI_PAGE_TYPE_ENTITY, "Entity");
        typeLabels.put(WIKI_PAGE_TYPE_CONCEPT, "Concept");
        typeLabels.put(WIKI_PAGE_TYPE_SYNTHESIS, "Synthesis");
        typeLabels.put(WIKI_PAGE_TYPE_COMPARISON, "Comparison");

        int nonEmpty = 0;
        for (IndexGroupView g : resp.groups()) {
            if (g.total() == 0) {
                continue;
            }
            String label = typeLabels.getOrDefault(g.type(), g.type());
            if (g.items().size() < g.total()) {
                sb.append("\n## ").append(label).append(" (").append(g.total())
                        .append(" total, showing top ").append(g.items().size()).append(")\n\n");
            } else {
                sb.append("\n## ").append(label).append(" (").append(g.total()).append(")\n\n");
            }
            for (IndexEntryView item : g.items()) {
                String display = item.title();
                if (display == null || display.isEmpty()) {
                    display = item.slug();
                }
                if (item.summary() != null && !item.summary().isEmpty()) {
                    sb.append("[[").append(item.slug()).append('|').append(display)
                            .append("]] — ").append(item.summary()).append('\n');
                } else {
                    sb.append("[[").append(item.slug()).append('|').append(display).append("]]\n");
                }
            }
            nonEmpty++;
        }

        if (nonEmpty == 0) {
            sb.append("\n*No wiki pages yet. Upload documents to get started.*\n");
        } else {
            sb.append("\n_To explore more pages under any category, use wiki_search with a query, "
                    + "or read a specific slug directly._\n");
        }
        return sb.toString();
    }

    /**
     * 对照 pendingWikiPage：邻居摘要/sources/body 已采集、渲染尺寸未定的页。
     */
    public static final class PendingWikiPage {
        private final PageView page;
        private final String kbId;
        private final List<String> outLinks;
        private final List<String> inLinks;
        private final List<String> sources;
        private final String body;

        public PendingWikiPage(PageView page, String kbId, List<String> outLinks,
                List<String> inLinks, List<String> sources, String body) {
            this.page = page;
            this.kbId = kbId;
            this.outLinks = outLinks;
            this.inLinks = inLinks;
            this.sources = sources;
            this.body = body;
        }

        public PageView page() { return page; }
        public String body() { return body; }

        /** 对照 pendingWikiPage.render 的 XML 模板（逐字节）。 */
        public String render(String body) {
            StringBuilder b = new StringBuilder();
            b.append("<wiki_page>\n");
            b.append("<metadata>\n");
            b.append("<knowledge_base_id>").append(kbId).append("</knowledge_base_id>\n");
            b.append("<link>[[").append(page.slug()).append('|').append(page.title()).append("]]</link>\n");
            b.append("<type>").append(page.pageType()).append("</type>\n");
            b.append("<aliases>").append(String.join(", ", page.aliases())).append("</aliases>\n");
            b.append("</metadata>\n");
            b.append("<relationships>\n");
            b.append("<links_to>").append(String.join(", ", outLinks)).append("</links_to>\n");
            b.append("<linked_from>").append(String.join(", ", inLinks)).append("</linked_from>\n");
            b.append("</relationships>\n");
            b.append("<sources>\n");
            b.append(String.join("\n", sources)).append('\n');
            b.append("</sources>\n");
            b.append("<summary>\n");
            b.append(page.summary()).append('\n');
            b.append("</summary>\n");
            b.append("<content>\n");
            b.append(body).append('\n');
            b.append("</content>\n");
            b.append("</wiki_page>");
            return b.toString();
        }
    }

    /**
     * 对照 renderWikiPagesWithinBudget：返回 (拼接输出, 被截断 slug, 被省略 slug)。
     * rune 计账与 Go 的 utf8.RuneCountInString 一致（code point 数）。
     */
    public static RenderedWikiPages renderWikiPagesWithinBudget(List<PendingWikiPage> pages, int budget) {
        if (pages.isEmpty()) {
            return new RenderedWikiPages("", List.of(), List.of());
        }
        final String separator = "\n\n";
        int separatorCost = separator.codePointCount(0, separator.length());

        int n = pages.size();
        String[] rendered = new String[n];
        int[] bodySizes = new int[n];
        int[] overheads = new int[n];
        int total = separatorCost * (n - 1);
        for (int i = 0; i < n; i++) {
            PendingWikiPage p = pages.get(i);
            rendered[i] = p.render(p.body());
            int size = rendered[i].codePointCount(0, rendered[i].length());
            bodySizes[i] = p.body() == null ? 0 : p.body().codePointCount(0, p.body().length());
            overheads[i] = size - bodySizes[i];
            total += size;
        }

        int usable = budget - WIKI_BUDGET_RESERVE;
        if (usable <= 0 || total <= usable) {
            return new RenderedWikiPages(String.join(separator, List.of(rendered)), List.of(), List.of());
        }

        int[] overheadsFinal = overheads;
        java.util.function.IntUnaryOperator fixedCost = keep -> {
            int cost = separatorCost * (keep - 1);
            for (int i = 0; i < keep; i++) {
                cost += overheadsFinal[i];
            }
            return cost;
        };

        int keep = n;
        while (keep > 1 && fixedCost.applyAsInt(keep) + keep * WIKI_MIN_PAGE_BODY > usable) {
            keep--;
        }
        List<String> omitted = new ArrayList<>();
        for (int i = keep; i < n; i++) {
            omitted.add(pages.get(i).page().slug());
        }

        int[] caps = OutputBudgets.splitBudgetFairly(usable - fixedCost.applyAsInt(keep), Arrays.copyOf(bodySizes, keep));
        List<String> outputs = new ArrayList<>(keep);
        List<String> truncated = new ArrayList<>();
        for (int i = 0; i < keep; i++) {
            if (caps[i] >= bodySizes[i]) {
                outputs.add(rendered[i]);
                continue;
            }
            String body = "(body omitted: output budget exhausted)";
            if (caps[i] > 0) {
                body = ToolOutput.truncateToolOutput(pages.get(i).body(), caps[i]);
            }
            outputs.add(pages.get(i).render(body));
            truncated.add(pages.get(i).page().slug());
        }
        return new RenderedWikiPages(String.join(separator, outputs), truncated, omitted);
    }

    /** 对照 renderWikiPagesWithinBudget 的三返回值。 */
    public record RenderedWikiPages(String output, List<String> truncatedSlugs, List<String> omittedSlugs) {
    }
}
