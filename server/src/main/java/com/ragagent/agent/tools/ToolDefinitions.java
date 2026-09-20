package com.ragagent.agent.tools;

import java.util.List;

/**
 * 工具名常量与 UI 元数据（对照 Go {@code definitions.go}，逐条移植）。
 * 名字字符串是跨端契约（前端 tool-capabilities、agent 配置 allowlist、历史回放都引用）。
 */
public final class ToolDefinitions {

    /** OpenAI API 对工具/函数名的最大长度。对照 maxFunctionNameLength。 */
    public static final int MAX_FUNCTION_NAME_LENGTH = 64;

    // ---- 工具名常量（definitions.go L8-74）----

    /** 能力域 MCP 发现与调用；不是租户可选的内建工具。 */
    public static final String TOOL_DISCOVER_MCP_TOOLS = "discover_mcp_tools";
    public static final String TOOL_CALL_MCP_TOOL = "call_mcp_tool";
    public static final String TOOL_THINKING = "thinking";
    public static final String TOOL_TODO_WRITE = "todo_write";
    public static final String TOOL_GREP_CHUNKS = "grep_chunks";
    public static final String TOOL_KNOWLEDGE_SEARCH = "knowledge_search";
    public static final String TOOL_LIST_KNOWLEDGE_CHUNKS = "list_knowledge_chunks";
    public static final String TOOL_QUERY_KNOWLEDGE_GRAPH = "query_knowledge_graph";
    public static final String TOOL_GET_DOCUMENT_INFO = "get_document_info";
    public static final String TOOL_SEARCH_CONVERSATIONS = "search_conversations";
    public static final String TOOL_SEARCH_MEMORY = "search_memory";
    public static final String TOOL_DATABASE_QUERY = "database_query";
    public static final String TOOL_DATA_ANALYSIS = "data_analysis";
    public static final String TOOL_DATA_SCHEMA = "data_schema";
    public static final String TOOL_WEB_SEARCH = "web_search";
    public static final String TOOL_WEB_FETCH = "web_fetch";
    /** 统一读取：workspace 走 sandbox file 能力；skill 资源走 SkillsEnabled 且不需要 sandbox。 */
    public static final String TOOL_READ_FILE = "read_file";
    /** Sandbox 文件系统工具（仅支持会话文件的后端：Cube/E2B/Docker）。 */
    public static final String TOOL_LIST_SANDBOX_FILES = "list_sandbox_files";
    public static final String TOOL_WRITE_SANDBOX_FILE = "write_sandbox_file";
    public static final String TOOL_EDIT_SANDBOX_FILE = "edit_sandbox_file";
    /** 内建 skill 安装器专用的 skill 树写入（/opt/weknora/tenant/skills）。 */
    public static final String TOOL_WRITE_SKILL_FILE = "write_skill_file";
    public static final String TOOL_EDIT_SKILL_FILE = "edit_skill_file";
    /** 会话 sandbox 内的临时 shell（后端声明会话 shell 能力时才注册；命令从不在 WeKnora 主机跑）。 */
    public static final String TOOL_SHELL_EXEC = "shell_exec";
    // Wiki 相关（作用域里有 wiki KB 时才可用）
    public static final String TOOL_WIKI_READ_PAGE = "wiki_read_page";
    public static final String TOOL_WIKI_WRITE_PAGE = "wiki_write_page";
    public static final String TOOL_WIKI_REPLACE_TEXT = "wiki_replace_text";
    public static final String TOOL_WIKI_RENAME_PAGE = "wiki_rename_page";
    public static final String TOOL_WIKI_DELETE_PAGE = "wiki_delete_page";
    public static final String TOOL_WIKI_SEARCH = "wiki_search";
    public static final String TOOL_WIKI_READ_SOURCE_DOC = "wiki_read_source_doc";
    public static final String TOOL_WIKI_FLAG_ISSUE = "wiki_flag_issue";
    public static final String TOOL_WIKI_READ_ISSUE = "wiki_read_issue";
    public static final String TOOL_WIKI_UPDATE_ISSUE = "wiki_update_issue";

    // ---- 已退役工具（只为解码历史与忽略过期 allowlist 条目而保留；无实现无注册无模型可见）----
    public static final String LEGACY_TOOL_EXECUTE_SKILL_SCRIPT = "execute_skill_script";
    public static final String LEGACY_TOOL_READ_SKILL = "read_skill";
    public static final String LEGACY_TOOL_READ_SANDBOX_FILE = "read_sandbox_file";

    private ToolDefinitions() {
    }

    /** 设置类 API 用的工具元数据（对照 AvailableTool struct：name/label/description 三键蛇形输出）。 */
    public record AvailableTool(String name, String label, String description) {
    }

    /**
     * 暴露给 UI 的工具清单（对照 AvailableToolDefinitions，顺序 = Go 数组声明序，
     * JSON 键序 name/label/description。Go 实录 22 条逐字节一致）。
     */
    public static List<AvailableTool> availableToolDefinitions() {
        return List.of(
                new AvailableTool(TOOL_THINKING, "思考", "动态和反思性的问题解决思考工具"),
                new AvailableTool(TOOL_TODO_WRITE, "制定计划", "创建结构化的研究计划"),
                new AvailableTool(TOOL_GREP_CHUNKS, "关键词搜索", "快速定位包含特定关键词的文档和分块"),
                new AvailableTool(TOOL_KNOWLEDGE_SEARCH, "语义搜索", "理解问题并查找语义相关内容"),
                new AvailableTool(TOOL_LIST_KNOWLEDGE_CHUNKS, "查看文档分块", "获取文档完整分块内容"),
                new AvailableTool(TOOL_QUERY_KNOWLEDGE_GRAPH, "查询知识图谱", "从知识图谱中查询关系"),
                new AvailableTool(TOOL_GET_DOCUMENT_INFO, "获取文档信息", "查看文档元数据"),
                new AvailableTool(TOOL_SEARCH_CONVERSATIONS, "回顾历史对话", "在用户自己的历史会话中查找之前聊过的内容"),
                new AvailableTool(TOOL_DATABASE_QUERY, "查询数据库", "查询数据库中的信息"),
                new AvailableTool(TOOL_DATA_ANALYSIS, "数据分析", "理解数据文件并进行数据分析"),
                new AvailableTool(TOOL_DATA_SCHEMA, "查看数据元信息", "获取表格文件的元信息"),
                new AvailableTool(TOOL_WIKI_READ_PAGE, "读取Wiki页面", "读取指定的Wiki页面内容"),
                new AvailableTool(TOOL_WIKI_SEARCH, "搜索Wiki", "在Wiki中搜索页面"),
                new AvailableTool(TOOL_WIKI_READ_SOURCE_DOC, "精读源文档", "使用知识点深入阅读特定原始文档"),
                new AvailableTool(TOOL_WIKI_FLAG_ISSUE, "标记Wiki问题", "标记页面中存在的事实错误或合并冲突问题"),
                new AvailableTool(TOOL_WIKI_WRITE_PAGE, "创建/覆盖Wiki", "创建新页面或完全覆盖已有页面"),
                new AvailableTool(TOOL_WIKI_REPLACE_TEXT, "局部替换Wiki", "替换Wiki页面中的特定文本"),
                new AvailableTool(TOOL_WIKI_RENAME_PAGE, "重命名Wiki", "重命名Wiki页面并自动更新关联链接"),
                new AvailableTool(TOOL_WIKI_DELETE_PAGE, "删除Wiki", "删除Wiki页面并自动清理关联死链"),
                new AvailableTool(TOOL_WIKI_READ_ISSUE, "查看Wiki问题", "查看特定的Wiki页面问题详情"),
                new AvailableTool(TOOL_WIKI_UPDATE_ISSUE, "更新Wiki问题状态", "更新特定的Wiki页面问题状态"));
    }

    /**
     * 默认放行工具（对照 DefaultAllowedTools，顺序照抄）。
     * search_memory / web_search / sandbox 文件族刻意缺席——由能力开关决定而非 allowlist。
     */
    public static List<String> defaultAllowedTools() {
        return List.of(
                TOOL_KNOWLEDGE_SEARCH,
                TOOL_GREP_CHUNKS,
                TOOL_LIST_KNOWLEDGE_CHUNKS,
                TOOL_GET_DOCUMENT_INFO,
                TOOL_SEARCH_CONVERSATIONS);
    }

    /**
     * 被移除工具的替代指引（对照 RetiredToolReplacement）；从未是 WeKnora 工具的名字返回 ""。
     * Go 实录文案逐字（含反引号路径）。
     */
    public static String retiredToolReplacement(String name) {
        return switch (name) {
            case LEGACY_TOOL_EXECUTE_SKILL_SCRIPT ->
                    "execute_skill_script is no longer available; use shell_exec(skill_name=..., command=...) to run skill scripts";
            case LEGACY_TOOL_READ_SKILL ->
                    "read_skill is no longer available; use read_file(path=\"skill://<name>/<file_path or SKILL.md>\")";
            case LEGACY_TOOL_READ_SANDBOX_FILE ->
                    "read_sandbox_file is no longer available; use read_file(path=...)";
            default -> "";
        };
    }
}
