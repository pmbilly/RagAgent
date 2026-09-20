package com.ragagent.agent;

/**
 * 工具名常量（对照 Go internal/agent/tools/definitions.go 的名称子集，L10-70、L143）。
 *
 * <p>波 4.2 只需要名字（compaction 的 fileops 与 grounding prompt 用）；
 * 注册表/定义本体随波 4.5 tools 批落 {@code com.ragagent.agent.tools}，
 * 届时这些常量迁过去，此处留转发引用即可。</p>
 */
public final class AgentToolNames {

    // definitions.go L10-25
    public static final String TOOL_DISCOVER_MCP_TOOLS = "discover_mcp_tools";
    public static final String TOOL_CALL_MCP_TOOL = "call_mcp_tool";
    public static final String TOOL_GREP_CHUNKS = "grep_chunks";
    public static final String TOOL_KNOWLEDGE_SEARCH = "knowledge_search";
    public static final String TOOL_LIST_KNOWLEDGE_CHUNKS = "list_knowledge_chunks";
    public static final String TOOL_QUERY_KNOWLEDGE_GRAPH = "query_knowledge_graph";
    public static final String TOOL_GET_DOCUMENT_INFO = "get_document_info";
    public static final String TOOL_DATABASE_QUERY = "database_query";
    public static final String TOOL_DATA_ANALYSIS = "data_analysis";
    public static final String TOOL_DATA_SCHEMA = "data_schema";
    public static final String TOOL_WEB_SEARCH = "web_search";
    public static final String TOOL_WEB_FETCH = "web_fetch";

    // definitions.go L28 / L62
    public static final String TOOL_READ_FILE = "read_file";
    public static final String TOOL_SHELL_EXEC = "shell_exec";

    // definitions.go L42-43
    public static final String TOOL_WRITE_SANDBOX_FILE = "write_sandbox_file";
    public static final String TOOL_EDIT_SANDBOX_FILE = "edit_sandbox_file";

    // definitions.go L64-70
    public static final String TOOL_WIKI_READ_PAGE = "wiki_read_page";
    public static final String TOOL_WIKI_SEARCH = "wiki_search";
    public static final String TOOL_WIKI_READ_SOURCE_DOC = "wiki_read_source_doc";

    // definitions.go L143
    public static final String LEGACY_TOOL_READ_SANDBOX_FILE = "read_sandbox_file";

    private AgentToolNames() {
    }
}
