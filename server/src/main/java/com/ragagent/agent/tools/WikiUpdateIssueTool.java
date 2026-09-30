package com.ragagent.agent.tools;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.llm.ToolResult;

/**
 * wiki_update_issue 工具。
 * 先证明 issue 属于允许的 KB（resolveWikiIssue），再改状态。
 */
public class WikiUpdateIssueTool extends BaseTool {

    private static final String SCHEMA_JSON = """
            {
              "type": "object",
              "properties": {
                "issue_id": {
                  "type": "string",
                  "description": "The short iN issue ID from wiki_read_issue."
                },
                "status": {
                  "type": "string",
                  "enum": ["resolved", "ignored", "pending"],
                  "description": "The new status for the issue."
                }
              },
              "required": ["issue_id", "status"]
            }""";

    private static final String DESCRIPTION =
            "Update the status of a specific wiki page issue (e.g., set it to 'resolved' or 'ignored').";

    private final WikiPages wikiService;
    private final List<String> kbIds;

    public WikiUpdateIssueTool(WikiPages wikiService, List<String> kbIds) {
        super(ToolDefinitions.TOOL_WIKI_UPDATE_ISSUE, DESCRIPTION, SCHEMA_JSON);
        this.wikiService = wikiService;
        this.kbIds = kbIds;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        JsonNode args = request.args();
        String issueId = args.path("issue_id").asText("");
        String status = args.path("status").asText("");

        if (issueId.isEmpty()) {
            return failure("issue_id is required");
        }
        if (status.isEmpty()) {
            return failure("status is required");
        }
        if (kbIds == null || kbIds.isEmpty()) {
            return failure("No knowledge bases available");
        }
        try {
            WikiRouteResolver.resolveWikiIssue(wikiService, issueId, kbIds);
        } catch (RuntimeException e) {
            return failure(e.getMessage());
        }

        // Update only after the issue has been proven to belong to an allowed KB.
        try {
            wikiService.updateIssueStatus(issueId, status);
        } catch (RuntimeException e) {
            return failure("Failed to update issue status: " + e.getMessage());
        }

        ToolResult r = new ToolResult();
        r.setSuccess(true);
        r.setOutput("Successfully updated issue " + issueId + " to status '" + status + "'");
        return r;
    }

    private static ToolResult failure(String message) {
        ToolResult result = new ToolResult();
        result.setSuccess(false);
        result.setError(message);
        return result;
    }
}
