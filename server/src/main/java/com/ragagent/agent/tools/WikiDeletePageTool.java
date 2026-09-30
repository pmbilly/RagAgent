package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.llm.ToolResult;

/**
 * wiki_delete_page 工具。
 * 删页前把入链 [[slug]] 替换为可读名、[[slug|text]] 拆为 text；失败回滚。
 */
public class WikiDeletePageTool extends BaseTool {

    private static final String SCHEMA_JSON = """
            {
            	"type": "object",
            	"properties": {
            		"slug": {
            			"type": "string",
            			"description": "The slug of the Wiki page to delete"
            		}
            	},
            	"required": ["slug"]
            }""";

    private static final String DESCRIPTION =
            "Delete a Wiki page. Automatically cleans up incoming links on other pages to prevent dead links.";

    private final WikiPages wikiPageService;
    private final List<String> kbIds;
    private final WikiRouteResolver routes;

    public WikiDeletePageTool(WikiPages wikiPageService, List<String> kbIds, WikiRouteResolver routes) {
        super(ToolDefinitions.TOOL_WIKI_DELETE_PAGE, DESCRIPTION, SCHEMA_JSON);
        this.wikiPageService = wikiPageService;
        this.kbIds = kbIds;
        this.routes = routes != null ? routes : new WikiRouteResolver();
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        JsonNode args = request.args();
        if (kbIds == null || kbIds.isEmpty()) {
            return failure("No knowledge bases available for editing");
        }
        if (args.path("slug").asText("").isEmpty()) {
            return failure("slug is required");
        }
        String slug;
        try {
            slug = WikiSlugs.normalizeAndValidateWikiSlug(args.path("slug").asText(""));
        } catch (IllegalArgumentException e) {
            return failure(e.getMessage());
        }

        // 取页以拿到入链
        PageView existingPage;
        String kbId;
        try {
            ResolvedPage resolved = WikiRouteResolver.resolveUniqueWikiPage(wikiPageService, slug, kbIds, routes);
            existingPage = resolved.page();
            kbId = resolved.kbId();
        } catch (RuntimeException e) {
            return failure("Failed to fetch page to delete: " + e.getMessage());
        }
        List<String> inLinks = new ArrayList<>(existingPage.inLinks());

        String[] parts = slug.split("/", -1);
        String readableName = parts[parts.length - 1].replace("-", " ");
        // 管道式双链 `[[slug|text]]` 的匹配模式
        Pattern pipeLink = Pattern.compile(
                "\\[\\[" + Pattern.quote(slug) + "\\|([^\\]]+)\\]\\]");
        String finalSlug = slug;
        WikiContentRewrite rewrite = content -> {
            String updated = content.replace("[[" + finalSlug + "]]", readableName);
            updated = pipeLink.matcher(updated).replaceAll("$1");
            return new RewriteResult(updated, !updated.equals(content));
        };

        List<String> updatedSlugs = new ArrayList<>();
        List<AppliedChange> changes;
        try {
            changes = WikiContentRewrite.applyIncomingWikiContentRewrite(
                    wikiPageService, kbId, inLinks, WikiContentRewrite.WIKI_EDIT_SOURCE_AGENT, rewrite, updatedSlugs);
        } catch (WikiRewriteException rewriteErr) {
            String rollbackErr = null;
            try {
                WikiContentRewrite.rollbackWikiContentChanges(
                        wikiPageService, rewriteErr.changes(), WikiContentRewrite.WIKI_EDIT_SOURCE_AGENT);
            } catch (RuntimeException rb) {
                rollbackErr = rb.getMessage();
            }
            return failure("Delete aborted while cleaning incoming links: "
                    + WikiContentRewrite.joinWikiMutationErrors(rewriteErr.getMessage(), rollbackErr));
        }

        try {
            wikiPageService.deletePage(kbId, slug, WikiContentRewrite.WIKI_EDIT_SOURCE_AGENT);
        } catch (RuntimeException e) {
            String rollbackErr = null;
            try {
                WikiContentRewrite.rollbackWikiContentChanges(wikiPageService, changes, WikiContentRewrite.WIKI_EDIT_SOURCE_AGENT);
            } catch (RuntimeException rb) {
                rollbackErr = rb.getMessage();
            }
            return failure("Delete aborted because the page could not be removed: "
                    + WikiContentRewrite.joinWikiMutationErrors(e.getMessage(), rollbackErr));
        }
        routes.forget(slug, kbId);
        int updatedCount = updatedSlugs.size();

        String outputMsg = String.format(
                "Successfully deleted page [[%s]] and cleaned up %d incoming links.", slug, updatedCount);
        if (updatedCount > 0) {
            outputMsg += String.format("\n- Affected pages: %s", String.join(", ", updatedSlugs));
        }

        ToolResult r = new ToolResult();
        r.setSuccess(true);
        r.setOutput(outputMsg);
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("display_type", "wiki_delete_page");
        data.put("slug", slug);
        data.put("title", existingPage.title());
        data.put("updated_count", updatedCount);
        data.put("affected_pages", updatedSlugs);
        r.setData(data);
        return r;
    }

    static ToolResult failure(String message) {
        ToolResult result = new ToolResult();
        result.setSuccess(false);
        result.setError(message);
        return result;
    }
}
