package com.ragagent.agent;

/**
 * 工具名常量。
 *
 * <p>此处只收名字（compaction 的 fileops 与 grounding prompt 用）；
 * 注册表/工具定义本体在 {@code com.ragagent.agent.tools}。</p>
 */
public final class AgentToolNames {

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

    public static final String TOOL_READ_FILE = "read_file";
    public static final String TOOL_SHELL_EXEC = "shell_exec";

    public static final String TOOL_WRITE_SANDBOX_FILE = "write_sandbox_file";
    public static final String TOOL_EDIT_SANDBOX_FILE = "edit_sandbox_file";

    public static final String TOOL_WIKI_READ_PAGE = "wiki_read_page";
    public static final String TOOL_WIKI_SEARCH = "wiki_search";
    public static final String TOOL_WIKI_READ_SOURCE_DOC = "wiki_read_source_doc";

    public static final String LEGACY_TOOL_READ_SANDBOX_FILE = "read_sandbox_file";

    private AgentToolNames() {
    }
}
