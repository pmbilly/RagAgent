package com.ragagent.agent.tools;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;
import com.ragagent.agent.tools.SearchAuth.KnowledgeScopeReader;
import com.ragagent.agent.tools.SearchAuth.ScopeAuthException;
import com.ragagent.agent.tools.SearchTarget.SearchTargets;
import com.ragagent.agent.tools.WikiSupport.PageView;
import com.ragagent.agent.tools.WikiSupport.ResolvedPage;
import com.ragagent.agent.tools.WikiSupport.WikiPages;
import com.ragagent.agent.tools.WikiSupport.WikiRouteResolver;

/**
 * wiki_replace_text 工具（对照 Go {@code wiki_replace_text.go}，逐字移植）。
 * 全文精确替换；old_text 出现次数为 0 时给出复制指引错误。
 */
public class WikiReplaceTextTool extends BaseTool {

    private static final String SCHEMA_JSON = """
            {
            	"type": "object",
            	"properties": {
            		"slug": {
            			"type": "string",
            			"description": "The slug of the Wiki page"
            		},
            		"old_text": {
            			"type": "string",
            			"description": "The exact text to find and replace"
            		},
            		"new_text": {
            			"type": "string",
            			"description": "The new text to insert"
            		},
            		"source_refs": {
            			"type": "array",
            			"items": {"type": "string"},
            			"description": "An optional list of short dN source document IDs that justify this change. If provided, these will COMPLETELY REPLACE the existing source_refs of the page."
            		}
            	},
            	"required": ["slug", "old_text", "new_text"]
            }""";

    private static final String DESCRIPTION =
            "Replace all occurrences of specific exact text in a Wiki page. Ideal for consistent minor corrections.";

    private final WikiPages wikiPageService;
    private final KnowledgeScopeReader knowledgeService;
    private final List<String> kbIds;
    private final WikiRouteResolver routes;
    private SearchTargets searchTargets;
    private boolean scopeEnforced;

    public WikiReplaceTextTool(WikiPages wikiPageService, List<String> kbIds,
                               KnowledgeScopeReader knowledgeService, WikiRouteResolver routes) {
        super(ToolDefinitions.TOOL_WIKI_REPLACE_TEXT, DESCRIPTION, SCHEMA_JSON);
        this.wikiPageService = wikiPageService;
        this.knowledgeService = knowledgeService;
        this.kbIds = kbIds;
        this.routes = routes != null ? routes : new WikiRouteResolver();
    }

    /** 对照 WithSearchTargets（链式）。 */
    public WikiReplaceTextTool withSearchTargets(SearchTargets searchTargets) {
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
        String oldText = args.path("old_text").asText("");
        if (oldText.isEmpty()) {
            return failure("old_text is required");
        }
        String slug;
        try {
            slug = WikiSupport.normalizeAndValidateWikiSlug(args.path("slug").asText(""));
        } catch (IllegalArgumentException e) {
            return failure(e.getMessage());
        }

        PageView existingPage;
        try {
            ResolvedPage resolved = WikiSupport.resolveUniqueWikiPage(wikiPageService, slug, kbIds, routes);
            existingPage = resolved.page();
        } catch (RuntimeException e) {
            return failure(String.format("Failed to fetch page %s: %s", slug, e.getMessage()));
        }

        // 对照 strings.Count：非重叠出现次数
        int replacementCount = countOccurrences(existingPage.content(), oldText);
        if (replacementCount == 0) {
            return failure("old_text not found in the current page content. Ensure you copy it exactly as it appears.");
        }

        String newText = args.path("new_text").asText("");
        existingPage.setContent(existingPage.content().replace(oldText, newText));

        if (args.has("source_refs")) {
            List<String> sourceRefs = WikiFlagIssueTool.stringList(args.get("source_refs"));
            if (sourceRefs == null) {
                sourceRefs = List.of();
            }
            if (scopeEnforced) {
                List<String> resolvedRefs;
                try {
                    resolvedRefs = SearchAuth.resolveAuthorizedSourceRefs(searchTargets, sourceRefs, knowledgeService);
                } catch (RuntimeException e) {
                    return failure("Invalid source_refs: " + e.getMessage());
                }
                existingPage.setSourceRefs(resolvedRefs);
            } else {
                existingPage.setSourceRefs(WikiSupport.resolveSourceRefs(sourceRefs, knowledgeService));
            }
        }

        try {
            wikiPageService.updatePage(existingPage, WikiSupport.WIKI_EDIT_SOURCE_AGENT);
        } catch (RuntimeException e) {
            return failure("Failed to update page: " + e.getMessage());
        }

        String oldPreview = FaqSnippet.truncateRunes(oldText, 80);
        String newPreview = FaqSnippet.truncateRunes(newText, 80);

        String output = String.format(
                "Successfully replaced %d occurrence(s) on page [[%s]].\n- Old: %s\n- New: %s",
                replacementCount, slug, oldPreview, newPreview);

        ToolResult r = new ToolResult();
        r.setSuccess(true);
        r.setOutput(output);
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("display_type", "wiki_replace_text");
        data.put("slug", slug);
        data.put("title", existingPage.title());
        data.put("old_text", oldPreview);
        data.put("new_text", newPreview);
        data.put("replacement_count", replacementCount);
        r.setData(data);
        return r;
    }

    /** 对照 strings.Count(s, sub)：非重叠计数；空 sub 返回 0（Go 返回 rune 数+1，但 old_text 已拒空）。 */
    static int countOccurrences(String s, String sub) {
        if (s == null || sub == null || sub.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = s.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    private static ToolResult failure(String message) {
        ToolResult result = new ToolResult();
        result.setSuccess(false);
        result.setError(message);
        return result;
    }
}
