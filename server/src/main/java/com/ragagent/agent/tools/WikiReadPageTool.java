package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;
import com.ragagent.agent.tools.SearchAuth.KnowledgeTagsFetcher;
import com.ragagent.agent.tools.WikiSupport.PageView;
import com.ragagent.agent.tools.WikiSupport.PendingWikiPage;
import com.ragagent.agent.tools.WikiSupport.RenderedWikiPages;
import com.ragagent.agent.tools.WikiSupport.WikiPages;
import com.ragagent.agent.tools.WikiSupport.WikiRouteResolver;
import com.ragagent.agent.tools.WikiSupport.WikiScope;
import com.ragagent.agent.tools.WikiSupport.IndexGroupView;
import com.ragagent.agent.tools.WikiSupport.IndexOverviewView;

/**
 * wiki_read_page 工具（对照 Go {@code wiki_tools.go} 的 wikiReadPageTool，逐字移植）。
 *
 * <p>会话级 {@code seenLinks} 去重（对照 Go 实例字段 + sync.Mutex；Java 用
 * ConcurrentHashMap 等价）。index 页特判走 {@link WikiPages#getIndexView} seam。</p>
 */
public class WikiReadPageTool extends BaseTool {

    private static final String SCHEMA_JSON = """
            {
              "type": "object",
              "properties": {
                "slugs": {
                  "type": "array",
                  "items": { "type": "string" },
                  "description": "List of wiki page slugs to read (e.g. ['entity/acme-corp', 'index'])"
                }
              },
              "required": ["slugs"]
            }""";

    private static final String DESCRIPTION =
            "Read one or more wiki pages by their slugs. Returns the full markdown content, metadata, and links.\n"
                    + "Use this to read specific wiki pages when you know their slug (e.g. \"entity/acme-corp\", \"concept/rag\").\n"
                    + "Knowledge-base routing is automatic. Known link/search provenance is preferred; otherwise every wiki knowledge base in scope is checked and ambiguous matches are returned.";

    private final WikiPages wikiService;
    private final KnowledgeTagsFetcher tagsFetcher;
    private final List<WikiScope> scopes;
    private final WikiRouteResolver routes;
    /** 会话级已见链接（对照 seenLinks map[string]bool）。 */
    private final Set<String> seenLinks = ConcurrentHashMap.newKeySet();

    public WikiReadPageTool(WikiPages wikiService, KnowledgeTagsFetcher tagsFetcher,
            List<WikiScope> scopes, WikiRouteResolver routes) {
        super(ToolDefinitions.TOOL_WIKI_READ_PAGE, DESCRIPTION, SCHEMA_JSON);
        this.wikiService = wikiService;
        this.tagsFetcher = tagsFetcher;
        this.scopes = scopes != null ? scopes : List.of();
        this.routes = routes != null ? routes : new WikiRouteResolver();
    }

    /** 对照 seenLinkKey：KB 作用域下的去重键。 */
    private static String seenLinkKey(String kbId, String slug) {
        return kbId + "\0" + slug;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        JsonNode args = request.args();

        List<String> slugsToFetch = new ArrayList<>();
        slugsToFetch.addAll(WikiSupport.parseStringOrArray(args.get("slugs")));
        slugsToFetch.addAll(WikiSupport.parseStringOrArray(args.get("slug")));
        slugsToFetch = SearchAuth.dedupNonEmptyStrings(slugsToFetch);

        if (slugsToFetch.isEmpty()) {
            return failure("Missing 'slugs' parameter");
        }

        List<PendingWikiPage> pending = new ArrayList<>();
        List<String> errs = new ArrayList<>();
        // slug → 找到它的 KB 列表（一 slug 可存在于多个 KB）
        Map<String, List<String>> foundKBs = new LinkedHashMap<>();

        for (String slug : slugsToFetch) {
            List<PageView> hitPages = new ArrayList<>();
            List<String> hitKbs = new ArrayList<>();
            Map<String, List<String>> filteredOut = new LinkedHashMap<>();
            Set<String> lookupFailed = ConcurrentHashMap.newKeySet();

            List<WikiScope> cachedScopes = routes.scopesForSlug(slug, scopes);
            // provenance 只是排序提示，永远检查所有合法 scope
            List<WikiScope> effectiveScopes = new ArrayList<>(cachedScopes);
            effectiveScopes.addAll(WikiSupport.scopesOutsideKbs(scopes, cachedScopes));

            for (WikiScope sc : effectiveScopes) {
                String kbId = sc.knowledgeBaseId();
                if (kbId == null || kbId.isEmpty()) {
                    continue;
                }
                PageView page;
                try {
                    page = wikiService.getPageBySlug(kbId, slug);
                } catch (RuntimeException e) {
                    lookupFailed.add(slug);
                    errs.add("Failed to read Wiki page '" + slug + "' in KB " + kbId + ": " + e.getMessage());
                    continue;
                }
                if (page == null) {
                    continue;
                }
                if (page.knowledgeBaseId() != null && !page.knowledgeBaseId().isEmpty()
                        && !page.knowledgeBaseId().equals(kbId)) {
                    lookupFailed.add(slug);
                    errs.add("Wiki page '" + slug + "' returned KB " + page.knowledgeBaseId()
                            + " while resolving allowed KB " + kbId);
                    continue;
                }
                String actualKBID = kbId;

                boolean passesScope;
                try {
                    passesScope = WikiSupport.pagePassesWikiScope(page, sc, tagsFetcher);
                } catch (RuntimeException e) {
                    errs.add("Failed to validate wiki scope for '" + slug + "' in KB "
                            + actualKBID + ": " + e.getMessage());
                    continue;
                }
                if (!passesScope) {
                    filteredOut.computeIfAbsent(slug, k -> new ArrayList<>()).add(actualKBID);
                    continue;
                }

                hitPages.add(page);
                hitKbs.add(actualKBID);
                routes.rememberPage(page, actualKBID);
                foundKBs.computeIfAbsent(slug, k -> new ArrayList<>()).add(actualKBID);
                registerLinkedSlugs(foundKBs, page, actualKBID);
                seenLinks.add(seenLinkKey(actualKBID, slug));
            }

            if (hitPages.isEmpty()) {
                List<String> kbs = filteredOut.get(slug);
                if (kbs != null && !kbs.isEmpty()) {
                    errs.add("Wiki page '" + slug + "' exists in " + goSliceString(kbs)
                            + " but none of its source documents are within the scope pinned by the user");
                } else if (!lookupFailed.contains(slug)) {
                    errs.add("Wiki page '" + slug + "' not found");
                }
                continue;
            }

            // 路由仍 ambiguous 时输出每个命中页
            for (int i = 0; i < hitPages.size(); i++) {
                pending.add(resolvePage(hitPages.get(i), hitKbs.get(i)));
            }
        }

        if (pending.isEmpty()) {
            return failure(String.join("; ", errs));
        }

        RenderedWikiPages rendered =
                WikiSupport.renderWikiPagesWithinBudget(pending, request.outputBudget());
        StringBuilder finalOutput = new StringBuilder(rendered.output());
        if (!rendered.omittedSlugs().isEmpty()) {
            finalOutput.append("\n\n<omitted_pages reason=\"output budget exceeded\">\n")
                    .append(String.join("\n", rendered.omittedSlugs()))
                    .append("\n</omitted_pages>")
                    .append("\n<hint>These pages were resolved but not rendered. "
                            + "Call wiki_read_page again with fewer slugs to read them.</hint>");
        }
        if (!errs.isEmpty()) {
            finalOutput.append("\n\n<errors>\n").append(String.join("\n", errs)).append("\n</errors>");
        }

        Map<String, List<String>> ambiguous = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : foundKBs.entrySet()) {
            if (entry.getValue().size() > 1) {
                ambiguous.put(entry.getKey(), entry.getValue());
            }
        }

        ToolResult result = new ToolResult();
        result.setSuccess(true);
        result.setOutput(finalOutput.toString());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("found_kbs", foundKBs);
        data.put("ambiguous_slugs", ambiguous);
        data.put("truncated_slugs", rendered.truncatedSlugs().isEmpty() ? null : rendered.truncatedSlugs());
        data.put("omitted_slugs", rendered.omittedSlugs().isEmpty() ? null : rendered.omittedSlugs());
        result.setData(data);
        return result;
    }

    /** 对照 formatLinks：邻居 slug 列表 → 带摘要的链接描述。 */
    private List<String> formatLinks(List<String> slugs, String kbId) {
        List<String> descs = new ArrayList<>();
        int inlined = 0;
        for (String s : slugs) {
            if (s == null || s.isEmpty()) {
                continue;
            }
            if (inlined >= WikiSupport.WIKI_MAX_LINK_SUMMARIES) {
                descs.add("[[" + s + "]]");
                continue;
            }
            String key = seenLinkKey(kbId, s);
            if (seenLinks.contains(key)) {
                descs.add("[[" + s + "]] (summary omitted, already seen)");
                continue;
            }
            PageView linkPage;
            try {
                linkPage = wikiService.getPageBySlug(kbId, s);
            } catch (RuntimeException e) {
                linkPage = null;
            }
            if (linkPage == null || linkPage.summary() == null || linkPage.summary().isEmpty()) {
                descs.add("[[" + s + "]]");
                continue;
            }
            String summary = linkPage.summary();
            if (summary.codePointCount(0, summary.length()) > WikiSupport.WIKI_LINK_SUMMARY_MAX_RUNES) {
                summary = WikiSupport.firstRunes(summary, WikiSupport.WIKI_LINK_SUMMARY_MAX_RUNES) + "...";
            }
            descs.add("[[" + s + "]] (" + summary + ")");
            inlined++;
            seenLinks.add(key);
        }
        if (descs.isEmpty()) {
            return List.of("(none)");
        }
        return descs;
    }

    /** 对照 resolvePage：采集渲染所需的邻居摘要/sources/body（index 页特判）。 */
    private PendingWikiPage resolvePage(PageView page, String kbId) {
        List<String> outLinks = formatLinks(page.outLinks(), kbId);
        List<String> inLinks = formatLinks(page.inLinks(), kbId);
        String body = page.content();
        List<String> sources = new ArrayList<>();

        for (String ref : page.sourceRefs()) {
            if (ref == null) {
                continue;
            }
            String kid = ref;
            String title = "";
            int pipe = ref.indexOf('|');
            if (pipe > 0) {
                kid = ref.substring(0, pipe);
                title = ref.substring(pipe + 1);
            }
            if (!title.isEmpty()) {
                sources.add("<source knowledge_id=\"" + kid + "\">" + title + "</source>");
            } else {
                sources.add("<source knowledge_id=\"" + kid + "\"/>");
            }
        }

        if (WikiSupport.WIKI_PAGE_TYPE_INDEX.equals(page.pageType())) {
            IndexOverviewView overview = wikiService.getIndexView(kbId, WikiSupport.WIKI_INDEX_AGENT_TOP_K);
            if (overview != null) {
                body = WikiSupport.renderIndexOverviewForAgent(overview);
                if (overview.groups() != null) {
                    for (IndexGroupView group : overview.groups()) {
                        if (group.items() == null) {
                            continue;
                        }
                        for (var item : group.items()) {
                            routes.remember(item.slug(), kbId);
                        }
                    }
                }
            }
        }

        return new PendingWikiPage(page, kbId, outLinks, inLinks, sources, body);
    }

    /** 对照 registerLinkedSlugs：把页的出链/入链 slug 记入 foundKBs（去重追加）。 */
    private static void registerLinkedSlugs(Map<String, List<String>> foundKBs, PageView page, String kbId) {
        if (page == null || kbId == null || kbId.isEmpty()) {
            return;
        }
        java.util.function.Consumer<String> add = slug -> {
            if (slug == null || slug.isEmpty()) {
                return;
            }
            List<String> owners = foundKBs.computeIfAbsent(slug, k -> new ArrayList<>());
            if (!owners.contains(kbId)) {
                owners.add(kbId);
            }
        };
        for (String s : page.outLinks()) {
            add.accept(s);
        }
        for (String s : page.inLinks()) {
            add.accept(s);
        }
    }

    /** Go 的 %v 打印 []string：" [a b c]"。 */
    private static String goSliceString(List<String> items) {
        return "[" + String.join(" ", items) + "]";
    }

    private static ToolResult failure(String message) {
        ToolResult result = new ToolResult();
        result.setSuccess(false);
        result.setError(message);
        return result;
    }
}
