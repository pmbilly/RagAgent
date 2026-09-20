package com.ragagent.agent.tools;

/**
 * 并发执行白名单（对照 Go {@code execution_policy.go} 的 CanRunConcurrently，逐条照抄）。
 *
 * <p>只有内建<b>读</b>工具允许并发。写操作、任意代码、未知/MCP 工具都是执行屏障——
 * 尤其是文件写必须在前一个 shell / skill 调用开始前完成。</p>
 */
public final class ExecutionPolicy {

    private ExecutionPolicy() {
    }

    public static boolean canRunConcurrently(String name) {
        return switch (name) {
            case ToolDefinitions.TOOL_KNOWLEDGE_SEARCH,
                    ToolDefinitions.TOOL_GREP_CHUNKS,
                    ToolDefinitions.TOOL_LIST_KNOWLEDGE_CHUNKS,
                    ToolDefinitions.TOOL_QUERY_KNOWLEDGE_GRAPH,
                    ToolDefinitions.TOOL_GET_DOCUMENT_INFO,
                    ToolDefinitions.TOOL_SEARCH_CONVERSATIONS,
                    ToolDefinitions.TOOL_SEARCH_MEMORY,
                    ToolDefinitions.TOOL_DATA_SCHEMA,
                    ToolDefinitions.TOOL_WEB_SEARCH,
                    ToolDefinitions.TOOL_WEB_FETCH,
                    ToolDefinitions.TOOL_READ_FILE,
                    ToolDefinitions.TOOL_LIST_SANDBOX_FILES,
                    ToolDefinitions.TOOL_WIKI_SEARCH,
                    ToolDefinitions.TOOL_WIKI_READ_PAGE,
                    ToolDefinitions.TOOL_WIKI_READ_SOURCE_DOC,
                    ToolDefinitions.TOOL_WIKI_READ_ISSUE -> true;
            default -> false;
        };
    }
}
