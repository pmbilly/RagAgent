package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;
import com.ragagent.agent.tools.SearchAuth.KnowledgeScopeReader;
import com.ragagent.agent.tools.SearchAuth.ScopeAuthException;
import com.ragagent.agent.tools.SearchTarget.SearchTargets;
import com.ragagent.agent.tools.WikiSupport.AppliedChange;
import com.ragagent.agent.tools.WikiSupport.PageView;
import com.ragagent.agent.tools.WikiSupport.RepairResult;
import com.ragagent.agent.tools.WikiSupport.ResolvedPage;
import com.ragagent.agent.tools.WikiSupport.WikiPages;
import com.ragagent.agent.tools.WikiSupport.WikiRouteResolver;

/**
 * wiki_write_page 工具（对照 Go {@code wiki_write_page.go}，逐字移植）。
 * 创建或整页覆盖；source_refs 在 scopeEnforced 时经 resolveAuthorizedSourceRefs
 * 鉴权；summary 命名空间禁止手工创建；写前 RepairContentLinks 自动修链（best-effort）。
 */
public class WikiWritePageTool extends BaseTool {

    private static final String SCHEMA_JSON = """
            {
            	"type": "object",
            	"properties": {
            		"slug": {
            			"type": "string",
            			"description": "The slug of the Wiki page (e.g. 'entity/hunyuan-damoxing')"
            		},
            		"title": {
            			"type": "string",
            			"description": "The title of the page"
            		},
            		"summary": {
            			"type": "string",
            			"description": "A one-sentence summary for the index listing"
            		},
            		"content": {
            			"type": "string",
            			"description": "The FULL, complete Markdown content of the page. Do NOT use placeholders."
            		},
            		"page_type": {
            			"type": "string",
            			"description": "The page type, e.g., 'summary', 'entity', 'concept', 'synthesis', 'comparison'"
            		},
            		"aliases": {
            			"type": "array",
            			"items": {"type": "string"},
            			"description": "A list of aliases for the page (optional). If provided, these will COMPLETELY REPLACE the existing aliases of the page."
            		},
            		"source_refs": {
            			"type": "array",
            			"items": {"type": "string"},
            			"description": "A list of short dN source document IDs that contributed to this page. If provided, these will COMPLETELY REPLACE the existing source_refs of the page."
            		}
            	},
            	"required": ["slug", "title", "summary", "content", "page_type"]
            }""";

    private static final String DESCRIPTION =
            "Create a new Wiki page or completely overwrite an existing one. Automatically handles outbound links.";

    private final WikiPages wikiPageService;
    private final KnowledgeScopeReader knowledgeService;
    private final List<String> kbIds;
    private final WikiRouteResolver routes;
    private SearchTargets searchTargets;
    private boolean scopeEnforced;

    public WikiWritePageTool(WikiPages wikiPageService, List<String> kbIds,
                             KnowledgeScopeReader knowledgeService, WikiRouteResolver routes) {
        super(ToolDefinitions.TOOL_WIKI_WRITE_PAGE, DESCRIPTION, SCHEMA_JSON);
        this.wikiPageService = wikiPageService;
        this.knowledgeService = knowledgeService;
        this.kbIds = kbIds;
        this.routes = routes != null ? routes : new WikiRouteResolver();
    }

    /** 对照 WithSearchTargets：启用 source_refs 的 Agent 授权边界（链式）。 */
    public WikiWritePageTool withSearchTargets(SearchTargets searchTargets) {
        this.searchTargets = searchTargets;
        this.scopeEnforced = true;
        return this;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        JsonNode args = request.args();
        if (kbIds == null || kbIds.isEmpty()) {
            return failure("No knowledge bases available for editing");
        }
        String title = args.path("title").asText("");
        String pageType = args.path("page_type").asText("");
        String content = args.path("content").asText("");
        String summary = args.path("summary").asText("");
        if (title.isEmpty() || pageType.isEmpty() || content.isEmpty() || summary.isEmpty()) {
            return failure("title, summary, content, and page_type are required for write action");
        }

        String slug;
        try {
            slug = WikiSupport.normalizeAndValidateWikiSlug(args.path("slug").asText(""));
        } catch (IllegalArgumentException e) {
            return failure(e.getMessage());
        }

        // 解析并授权 provenance（scopeEnforced 时）；否则纯富化
        List<String> resolvedRefs = null;
        JsonNode sourceRefsNode = args.get("source_refs");
        if (sourceRefsNode != null) {
            List<String> sourceRefs = WikiFlagIssueTool.stringList(sourceRefsNode);
            if (sourceRefs == null) {
                sourceRefs = new ArrayList<>(); // JSON null → Go 的 nil slice
            }
            if (scopeEnforced) {
                try {
                    resolvedRefs = SearchAuth.resolveAuthorizedSourceRefs(searchTargets, sourceRefs, knowledgeService);
                } catch (RuntimeException e) {
                    return failure("Invalid source_refs: " + e.getMessage());
                }
            } else {
                resolvedRefs = WikiSupport.resolveSourceRefs(sourceRefs, knowledgeService);
            }
        }

        // 跨所有合法 Wiki KB 解析已存在页；ambiguous 绝不静默写第一个 KB
        PageView existingPage = null;
        String kbId = null;
        try {
            ResolvedPage resolved = WikiSupport.resolveUniqueWikiPage(wikiPageService, slug, kbIds, routes);
            existingPage = resolved.page();
            kbId = resolved.kbId();
        } catch (IllegalArgumentException e) {
            if (!e.getMessage().startsWith(WikiSupport.ERR_PAGE_NOT_FOUND_IN_SCOPE)) {
                return failure("Failed to resolve wiki target: " + e.getMessage());
            }
            // 新页：source_refs 提供服务端 KB 提示
            List<String> sourceKbHints;
            try {
                sourceKbHints = WikiSupport.wikiKnowledgeBasesForSourceRefs(resolvedRefs, knowledgeService, kbIds);
            } catch (RuntimeException e2) {
                return failure("Failed to resolve source_refs routing: " + e2.getMessage());
            }
            try {
                kbId = WikiSupport.resolveWikiCreateKb(slug, kbIds, routes, sourceKbHints);
            } catch (RuntimeException e2) {
                return failure("Failed to resolve wiki target: " + e2.getMessage());
            }
        } catch (RuntimeException e) {
            return failure("Failed to resolve wiki target: " + e.getMessage());
        }

        // summary 页是系统拥有的：只允许更新已存在的，不允许手工创建
        if (existingPage == null && (WikiSupport.isSummaryNamespace(slug)
                || WikiSupport.WIKI_PAGE_TYPE_SUMMARY.equalsIgnoreCase(pageType))) {
            return failure("summary pages are generated automatically from source documents and cannot be created manually. "
                    + "Use page_type 'synthesis'/'comparison'/'entity'/'concept' for authored pages, "
                    + "or target an existing summary page to update it.");
        }

        // 写前自动修复死链（best-effort——永不阻塞写入）
        RepairResult repaired = wikiPageService.repairContentLinks(kbId, slug, content);
        if (repaired != null && repaired.changed()) {
            content = repaired.repaired();
        }

        String action;
        if (existingPage != null) {
            existingPage.setTitle(title);
            existingPage.setSummary(summary);
            existingPage.setContent(content);
            existingPage.setPageType(pageType);
            if (args.has("aliases")) {
                existingPage.setAliases(WikiFlagIssueTool.stringList(args.get("aliases")));
            }
            if (sourceRefsNode != null) {
                existingPage.setSourceRefs(resolvedRefs);
            }
            try {
                wikiPageService.updatePage(existingPage, WikiSupport.WIKI_EDIT_SOURCE_AGENT);
            } catch (RuntimeException e) {
                return failure("Failed to update page: " + e.getMessage());
            }
            action = "updated";
        } else {
            PageView newPage = PageView.of(kbId, slug);
            newPage.setTitle(title);
            newPage.setSummary(summary);
            newPage.setContent(content);
            newPage.setPageType(pageType);
            newPage.setSourceRefs(resolvedRefs);
            if (args.has("aliases")) {
                newPage.setAliases(WikiFlagIssueTool.stringList(args.get("aliases")));
            }
            try {
                wikiPageService.createPage(newPage, WikiSupport.WIKI_EDIT_SOURCE_AGENT);
            } catch (RuntimeException e) {
                return failure("Failed to create page: " + e.getMessage());
            }
            action = "created";
        }
        routes.remember(slug, kbId);

        wikiPageService.injectCrossLinks(kbId, List.of(slug));
        try {
            wikiPageService.rebuildIndexPage(kbId);
        } catch (RuntimeException ignored) {
            // 对照 Go：_ = RebuildIndexPage，恒忽略
        }

        StringBuilder output = new StringBuilder();
        output.append("Successfully ").append(action).append(" page [[").append(slug).append("]].\n");
        output.append("- Title: ").append(title).append('\n');
        output.append("- Type: ").append(pageType).append('\n');
        output.append("- Summary: ").append(summary).append('\n');
        output.append("- Content length: ").append(content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).append(" chars");
        if (args.has("aliases") && WikiFlagIssueTool.stringList(args.get("aliases")) != null
                && !WikiFlagIssueTool.stringList(args.get("aliases")).isEmpty()) {
            output.append("\n- Aliases: ").append(String.join(", ", WikiFlagIssueTool.stringList(args.get("aliases"))));
        }
        if (sourceRefsNode != null && !sourceRefsNode.isNull()) {
            output.append("\n- Source refs: ").append(resolvedRefs.size()).append(" document(s)");
        }

        ToolResult r = new ToolResult();
        r.setSuccess(true);
        r.setOutput(output.toString());
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("display_type", "wiki_write_page");
        data.put("action", action);
        data.put("slug", slug);
        data.put("title", title);
        data.put("page_type", pageType);
        data.put("summary", summary);
        r.setData(data);
        return r;
    }

    private static ToolResult failure(String message) {
        ToolResult result = new ToolResult();
        result.setSuccess(false);
        result.setError(message);
        return result;
    }
}
