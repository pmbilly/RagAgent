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

/**
 * wiki_search 工具（对照 Go {@code wiki_tools.go} 的 wikiSearchTool，逐字移植）。
 * PostgreSQL POSIX 正则由服务端执行；工具侧只按 scope 过滤与排版。
 */
public class WikiSearchTool extends BaseTool {

    private static final String SCHEMA_JSON = """
            {
              "type": "object",
              "properties": {
                "queries": {
                  "type": "array",
                  "items": { "type": "string" },
                  "description": "List of regex search queries to run"
                },
                "limit": {
                  "type": "integer",
                  "description": "Max results to return per query (default 10)"
                },
                "knowledge_base_id": {
                  "type": "string",
                  "description": "Optional: restrict search to a single short bN knowledge base ID in scope."
                }
              },
              "required": ["queries"]
            }""";

    private static final String DESCRIPTION =
            "Search wiki pages using PostgreSQL POSIX regular expressions (~* operator, case-insensitive).\n"
                    + "STRONGLY PREFER using regex to search for multiple concepts at once rather than simple plain text queries.\n"
                    + "Returns matching pages with titles, slugs, and summaries (each tagged with its short bN knowledge_base_id).\n"
                    + "Examples:\n"
                    + "- Alternation (RECOMMENDED): \"stardust|skyvault\" (matches either word)\n"
                    + "- Multiple terms (RECOMMENDED): \"psionic.*engine\" (matches both words in order)\n"
                    + "- Prefix matching: \"^entity/.*\" (finds all entities)\n"
                    + "- Plain text: \"engine\" (matches anywhere in title/content/slug/summary)\n"
                    + "IMPORTANT — JSON escaping: every backslash in a regex MUST be written as \\\\ inside the JSON tool arguments (e.g. to search for literal \"C++\" write \"C\\\\+\\\\+\", NOT \"C\\+\\+\"; for \"\\d+\" write \"\\\\d+\"). Plain \"\\+\" / \"\\d\" etc. are invalid JSON escapes and will fail to parse.\n"
                    + "Use this to find relevant wiki pages when you don't know the exact slug.";

    private final WikiPages wikiService;
    private final KnowledgeTagsFetcher tagsFetcher;
    private final List<WikiScope> scopes;
    private final WikiRouteResolver routes;
    /** 会话级已见 slug（对照 seenSlugs map[string]bool）。 */
    private final Set<String> seenSlugs = ConcurrentHashMap.newKeySet();

    public WikiSearchTool(WikiPages wikiService, KnowledgeTagsFetcher tagsFetcher,
            List<WikiScope> scopes, WikiRouteResolver routes) {
        super(ToolDefinitions.TOOL_WIKI_SEARCH, DESCRIPTION, SCHEMA_JSON);
        this.wikiService = wikiService;
        this.tagsFetcher = tagsFetcher;
        this.scopes = scopes != null ? scopes : List.of();
        this.routes = routes != null ? routes : new WikiRouteResolver();
    }

    private static String seenLinkKey(String kbId, String slug) {
        return kbId + "\0" + slug;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        JsonNode args = request.args();

        List<String> queriesToRun = new ArrayList<>();
        queriesToRun.addAll(WikiTexts.parseStringOrArray(args.get("queries")));
        queriesToRun.addAll(WikiTexts.parseStringOrArray(args.get("query")));

        if (queriesToRun.isEmpty()) {
            return failure("Missing 'queries' parameter");
        }

        int limit = args.path("limit").asInt(0);
        if (limit <= 0) {
            limit = 10;
        }

        // knowledge_base_id 参数限制 scope
        String restrictKb = args.path("knowledge_base_id").asText("");
        List<WikiScope> effectiveScopes = scopes;
        if (!restrictKb.isEmpty()) {
            List<WikiScope> filtered = new ArrayList<>();
            for (WikiScope sc : scopes) {
                if (restrictKb.equals(sc.knowledgeBaseId())) {
                    filtered.add(sc);
                    break;
                }
            }
            if (filtered.isEmpty()) {
                return failure("knowledge_base_id is not within the current wiki scope");
            }
            effectiveScopes = filtered;
        }

        List<String> allOutputs = new ArrayList<>();
        List<String> searchErrors = new ArrayList<>();
        int successfulSearchCalls = 0;
        Map<String, List<String>> foundKBs = new LinkedHashMap<>();

        for (String query : queriesToRun) {
            List<PageView> allHitPages = new ArrayList<>();
            List<String> allHitKbs = new ArrayList<>();
            for (WikiScope sc : effectiveScopes) {
                String kbId = sc.knowledgeBaseId();
                if (kbId == null || kbId.isEmpty()) {
                    continue;
                }
                List<PageView> pages;
                try {
                    pages = wikiService.searchPages(kbId, query, limit);
                } catch (RuntimeException e) {
                    searchErrors.add("Wiki search \"" + query + "\" failed in KB " + kbId + ": " + e.getMessage());
                    continue;
                }
                successfulSearchCalls++;
                if (pages == null) {
                    continue;
                }
                for (PageView p : pages) {
                    if (p == null) {
                        continue;
                    }
                    boolean passesScope;
                    try {
                        passesScope = WikiScope.pagePassesWikiScope(p, sc, tagsFetcher);
                    } catch (RuntimeException e) {
                        searchErrors.add("Failed to validate Wiki search result \"" + p.slug()
                                + "\" in KB " + kbId + ": " + e.getMessage());
                        continue;
                    }
                    if (!passesScope) {
                        continue;
                    }
                    if (p.knowledgeBaseId() != null && !p.knowledgeBaseId().isEmpty()
                            && !p.knowledgeBaseId().equals(kbId)) {
                        searchErrors.add("Wiki search result \"" + p.slug() + "\" returned KB "
                                + p.knowledgeBaseId() + " while resolving allowed KB " + kbId);
                        continue;
                    }
                    allHitPages.add(p);
                    allHitKbs.add(kbId);
                    routes.rememberPage(p, kbId);
                    foundKBs.computeIfAbsent(p.slug(), k -> new ArrayList<>()).add(kbId);
                    registerLinkedSlugs(foundKBs, p, kbId);
                }
            }

            if (allHitPages.isEmpty()) {
                allOutputs.add("<search_results count=\"0\" query=\"" + query + "\" />");
                continue;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("<search_results count=\"").append(allHitPages.size())
                    .append("\" query=\"").append(query).append("\">\n");
            for (int i = 0; i < allHitPages.size(); i++) {
                PageView p = allHitPages.get(i);
                String kbId = allHitKbs.get(i);
                String key = seenLinkKey(kbId, p.slug());
                boolean seen = seenSlugs.contains(key);
                seenSlugs.add(key);

                String snippet = WikiTexts.extractSnippet(p.content(), query);
                String snippetTag = snippet.isEmpty() ? ""
                        : "\n<match_snippet>" + snippet + "</match_snippet>";

                String aliasesTag = p.aliases() == null || p.aliases().isEmpty() ? ""
                        : "\n<aliases>" + String.join(", ", p.aliases()) + "</aliases>";

                String summary = p.summary();
                if (seen) {
                    summary = "(summary omitted, already seen in previous search)";
                }
                sb.append("<page>\n<knowledge_base_id>").append(kbId).append("</knowledge_base_id>\n");
                sb.append("<link>[[").append(p.slug()).append('|').append(p.title()).append("]]</link>\n");
                sb.append("<type>").append(p.pageType()).append("</type>").append(aliasesTag).append('\n');
                sb.append("<summary>").append(summary).append("</summary>").append(snippetTag).append('\n');
                sb.append("</page>\n");
            }
            sb.append("</search_results>");
            allOutputs.add(sb.toString());
        }

        if (successfulSearchCalls == 0 && !searchErrors.isEmpty()) {
            return failure(String.join("; ", searchErrors));
        }
        StringBuilder output = new StringBuilder(String.join("\n\n", allOutputs));
        if (!searchErrors.isEmpty()) {
            output.append("\n\n<errors>\n").append(String.join("\n", searchErrors)).append("\n</errors>");
        }
        ToolResult result = new ToolResult();
        result.setSuccess(true);
        result.setOutput(output.toString());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("found_kbs", foundKBs);
        result.setData(data);
        return result;
    }

    /** 对照 registerLinkedSlugs。 */
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

    private static ToolResult failure(String message) {
        ToolResult result = new ToolResult();
        result.setSuccess(false);
        result.setError(message);
        return result;
    }
}
