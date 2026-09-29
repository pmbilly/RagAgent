package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;
import com.ragagent.agent.tools.SearchAuth.KnowledgeScopeReader;
import com.ragagent.agent.tools.SearchTarget.SearchTargets;

/**
 * wiki_flag_issue 工具（对照 Go {@code wiki_flag_issue.go}，逐字移植）。
 * 页面路由走 resolveUniqueWikiPage；suspected_knowledge_ids 在 scopeEnforced 时
 * 经 resolveAuthorizedSourceRefs 鉴权重建。
 */
public class WikiFlagIssueTool extends BaseTool {

    private static final String SCHEMA_JSON = """
            {
              "type": "object",
              "properties": {
                "slug": {
                  "type": "string",
                  "description": "The slug of the wiki page that has an issue (e.g. 'entity/hunyuan-damoxing')"
                },
                "issue_type": {
                  "type": "string",
                  "enum": ["mixed_entities", "contradictory_facts", "out_of_date", "other"],
                  "description": "The category of the issue"
                },
                "description": {
                  "type": "string",
                  "description": "A detailed explanation of what is wrong with the page and what should be fixed."
                },
                "suspected_knowledge_ids": {
                  "type": "array",
                  "items": { "type": "string" },
                  "description": "Optional list of short dN document IDs from the <sources> block that you suspect are causing the pollution or error."
                }
              },
              "required": ["slug", "issue_type", "description"]
            }""";

    private static final String DESCRIPTION = """
            Flag a wiki page that contains errors, mixed entities, or outdated information.
            Use this tool when you or the user identifies that a wiki page is factually incorrect or wrongly merged (e.g., a page contains information about two different products).
            This will log an issue for human review or automated maintenance.""";

    private final WikiPages wikiService;
    private final List<String> kbIds;
    private final WikiRouteResolver routes;
    private KnowledgeScopeReader knowledgeService;
    private SearchTargets searchTargets;
    private boolean scopeEnforced;

    public WikiFlagIssueTool(WikiPages wikiService, List<String> kbIds, WikiRouteResolver routes) {
        super(ToolDefinitions.TOOL_WIKI_FLAG_ISSUE, DESCRIPTION, SCHEMA_JSON);
        this.wikiService = wikiService;
        this.kbIds = kbIds;
        this.routes = routes != null ? routes : new WikiRouteResolver();
    }

    /** 对照 WithKnowledgeScope：启用 Agent 授权边界（链式）。 */
    public WikiFlagIssueTool withKnowledgeScope(KnowledgeScopeReader knowledgeService, SearchTargets searchTargets) {
        this.knowledgeService = knowledgeService;
        this.searchTargets = searchTargets;
        this.scopeEnforced = true;
        return this;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        JsonNode args = request.args();
        String slug = args.path("slug").asText("").trim();
        String normalizedSlug;
        try {
            normalizedSlug = WikiSlugs.normalizeAndValidateWikiSlug(slug);
        } catch (IllegalArgumentException e) {
            return failure(e.getMessage());
        }
        slug = normalizedSlug;

        if (kbIds == null || kbIds.isEmpty()) {
            return failure("No knowledge bases available for issue tracking");
        }

        ResolvedPage resolved;
        try {
            resolved = WikiRouteResolver.resolveUniqueWikiPage(wikiService, slug, kbIds, routes);
        } catch (RuntimeException e) {
            return failure(e.getMessage());
        }
        String kbId = resolved.kbId();

        List<String> suspectedKnowledgeIds = stringList(args.get("suspected_knowledge_ids"));
        if (scopeEnforced && suspectedKnowledgeIds != null && !suspectedKnowledgeIds.isEmpty()) {
            List<String> resolvedRefs;
            try {
                resolvedRefs = SearchAuth.resolveAuthorizedSourceRefs(searchTargets, suspectedKnowledgeIds, knowledgeService);
            } catch (RuntimeException e) {
                return failure("Invalid suspected_knowledge_ids: " + e.getMessage());
            }
            suspectedKnowledgeIds = new ArrayList<>();
            for (String ref : resolvedRefs) {
                suspectedKnowledgeIds.add(ref.split("\\|", 2)[0]);
            }
        }

        IssueView issue = new IssueView();
        issue.setTenantId(resolved.page().tenantId());
        issue.setKnowledgeBaseId(kbId);
        issue.setSlug(slug);
        issue.setIssueType(args.path("issue_type").asText(""));
        issue.setDescription(args.path("description").asText(""));
        issue.setSuspectedKnowledgeIds(suspectedKnowledgeIds);
        issue.setReportedBy("wiki-researcher-agent");
        issue.setStatus("pending");

        try {
            wikiService.createIssue(issue);
        } catch (RuntimeException e) {
            return failure("Failed to create issue: " + e.getMessage());
        }

        ToolResult r = new ToolResult();
        r.setSuccess(true);
        r.setOutput("Successfully flagged issue for " + slug
                + ". A maintenance ticket has been created for review.");
        return r;
    }

    static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            out.add(item.asText());
        }
        return out;
    }

    private static ToolResult failure(String message) {
        ToolResult result = new ToolResult();
        result.setSuccess(false);
        result.setError(message);
        return result;
    }
}
