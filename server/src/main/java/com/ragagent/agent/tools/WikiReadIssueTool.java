package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;

/**
 * wiki_read_issue 工具。
 * 读单个 issue（两空格缩进 JSON 输出）或按 slug 列 pending issues。
 */
public class WikiReadIssueTool extends BaseTool {

    private static final String SCHEMA_JSON = """
            {
              "type": "object",
              "properties": {
                "issue_id": {
                  "type": "string",
                  "description": "Optional: The short iN ID of a specific issue from an earlier wiki_read_issue result."
                },
                "slug": {
                  "type": "string",
                  "description": "Optional: The slug of the wiki page to list pending issues for."
                }
              },
              "description": "Provide either issue_id or slug to read issue(s)."
            }""";

    private static final String DESCRIPTION =
            "Read the details of a specific wiki page issue or list pending issues for a wiki page.";

    private final WikiPages wikiService;
    private final List<String> kbIds;

    public WikiReadIssueTool(WikiPages wikiService, List<String> kbIds) {
        super(ToolDefinitions.TOOL_WIKI_READ_ISSUE, DESCRIPTION, SCHEMA_JSON);
        this.wikiService = wikiService;
        this.kbIds = kbIds;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        JsonNode args = request.args();
        String issueId = args.path("issue_id").asText("").trim();
        String slug = args.path("slug").asText("").trim();

        if (issueId.isEmpty() && slug.isEmpty()) {
            return failure("Either issue_id or slug is required");
        }
        if (kbIds == null || kbIds.isEmpty()) {
            return failure("No knowledge bases available");
        }

        if (!issueId.isEmpty()) {
            IssueView issue;
            try {
                issue = WikiRouteResolver.resolveWikiIssue(wikiService, issueId, kbIds);
            } catch (RuntimeException e) {
                return failure(e.getMessage());
            }
            ToolResult r = new ToolResult();
            r.setSuccess(true);
            r.setOutput(issue.toGoJsonIndent());
            return r;
        }

        List<IssueView> issues = new ArrayList<>();
        for (String kbId : SearchAuth.dedupNonEmptyStrings(kbIds)) {
            List<IssueView> kbIssues;
            try {
                kbIssues = wikiService.listIssues(kbId, slug, "pending");
            } catch (RuntimeException e) {
                return failure("Failed to list issues: " + e.getMessage());
            }
            for (IssueView issue : kbIssues) {
                if (issue != null && issue.knowledgeBaseId() != null && !issue.knowledgeBaseId().isEmpty()
                        && !issue.knowledgeBaseId().equals(kbId)) {
                    return failure("Issue result returned knowledge base " + issue.knowledgeBaseId()
                            + " while resolving allowed scope " + kbId);
                }
            }
            issues.addAll(kbIssues);
        }

        if (issues.isEmpty()) {
            ToolResult r = new ToolResult();
            r.setSuccess(true);
            r.setOutput("No pending issues found for slug: " + slug);
            return r;
        }

        // 数组形态，元素整体缩进两格
        StringBuilder out = new StringBuilder();
        out.append("[\n");
        for (int i = 0; i < issues.size(); i++) {
            if (i > 0) {
                out.append(",\n");
            }
            String item = issues.get(i) == null ? "null" : issues.get(i).toGoJsonIndent();
            out.append(indent(item));
        }
        out.append("\n]");
        ToolResult r = new ToolResult();
        r.setSuccess(true);
        r.setOutput(out.toString());
        return r;
    }

    /** 把单行对象整体缩进两格（数组元素的输出形态）。 */
    private static String indent(String json) {
        StringBuilder sb = new StringBuilder();
        for (String line : json.split("\n", -1)) {
            if (!line.isEmpty()) {
                sb.append("  ").append(line).append('\n');
            }
        }
        if (sb.length() > 0) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    private static ToolResult failure(String message) {
        ToolResult result = new ToolResult();
        result.setSuccess(false);
        result.setError(message);
        return result;
    }
}
