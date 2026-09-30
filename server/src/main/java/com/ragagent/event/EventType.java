package com.ragagent.event;

/**
 * 事件类型常量（对照 Go {@code event.EventType} 及其 39 个常量，internal/event/event.go:14-92）。
 *
 * <p>Go 里 {@code EventType} 是命名 string 类型；Java 侧用 String 常量集中定义
 * （与 {@code com.ragagent.common.llm.ResponseType} 的做法同源——流式子集的取值
 * 与 ResponseType 的 wire 值逐字相同：thought / tool_call / tool_result / command_output /
 * reflection / references / final_answer / error / tool_approval_required / ...
 * 接线层如需 ResponseType 枚举，按值映射即可）。</p>
 *
 * <p>事件类型同时是 {@link EventBus} 的订阅键（Go {@code map[EventType][]EventHandler}）
 * 与 {@link Event#getType()} 的取值。不要在别处散写字面量——这里是唯一权威。</p>
 */
public final class EventType {

    // === 查询处理（Query processing events） ===
    /** 用户查询到达 */
    public static final String EVENT_QUERY_RECEIVED = "query.received";
    /** 查询验证完成 */
    public static final String EVENT_QUERY_VALIDATED = "query.validated";
    /** 查询预处理 */
    public static final String EVENT_QUERY_PREPROCESS = "query.preprocess";
    /** 查询改写 */
    public static final String EVENT_QUERY_REWRITE = "query.rewrite";
    /** 查询改写完成 */
    public static final String EVENT_QUERY_REWRITTEN = "query.rewritten";

    // === 检索（Retrieval events） ===
    /** 检索开始 */
    public static final String EVENT_RETRIEVAL_START = "retrieval.start";
    /** 向量检索 */
    public static final String EVENT_RETRIEVAL_VECTOR = "retrieval.vector";
    /** 关键词检索 */
    public static final String EVENT_RETRIEVAL_KEYWORD = "retrieval.keyword";
    /** 实体检索 */
    public static final String EVENT_RETRIEVAL_ENTITY = "retrieval.entity";
    /** 检索完成 */
    public static final String EVENT_RETRIEVAL_COMPLETE = "retrieval.complete";

    // === 排序（Rerank events） ===
    /** 排序开始 */
    public static final String EVENT_RERANK_START = "rerank.start";
    /** 排序完成 */
    public static final String EVENT_RERANK_COMPLETE = "rerank.complete";

    // === 合并（Merge events） ===
    /** 合并开始 */
    public static final String EVENT_MERGE_START = "merge.start";
    /** 合并完成 */
    public static final String EVENT_MERGE_COMPLETE = "merge.complete";

    // === 聊天生成（Chat completion events） ===
    /** 聊天生成开始 */
    public static final String EVENT_CHAT_START = "chat.start";
    /** 聊天生成完成 */
    public static final String EVENT_CHAT_COMPLETE = "chat.complete";
    /** 聊天流式输出 */
    public static final String EVENT_CHAT_STREAM = "chat.stream";

    // === Agent（Agent events） ===
    /** Agent 查询开始 */
    public static final String EVENT_AGENT_QUERY = "agent.query";
    /** Agent 计划生成 */
    public static final String EVENT_AGENT_PLAN = "agent.plan";
    /** Agent 步骤执行 */
    public static final String EVENT_AGENT_STEP = "agent.step";
    /** Agent 工具调用 */
    public static final String EVENT_AGENT_TOOL = "agent.tool";
    /** Agent 完成 */
    public static final String EVENT_AGENT_COMPLETE = "agent.complete";

    // === Agent 流式事件（实时反馈，AgentStreamHandler 订阅的就是这一组） ===
    /** Agent 思考过程 */
    public static final String EVENT_AGENT_THOUGHT = "thought";
    /** 有界命令输出（累计尾量） */
    /** 工具调用通知 */
    public static final String EVENT_AGENT_TOOL_CALL = "tool_call";
    /** 工具结果 */
    public static final String EVENT_AGENT_TOOL_RESULT = "tool_result";
    /** Agent 反思 */
    public static final String EVENT_AGENT_REFLECTION = "reflection";
    /** 知识引用 */
    public static final String EVENT_AGENT_REFERENCES = "references";
    /** 最终答案 */
    public static final String EVENT_AGENT_FINAL_ANSWER = "final_answer";

    // === MCP 工具人工审批（issue #1173） ===
    public static final String EVENT_TOOL_APPROVAL_REQUIRED = "tool_approval_required";
    public static final String EVENT_TOOL_APPROVAL_RESOLVED = "tool_approval_resolved";

    // === MCP OAuth 会话内授权提示：调用带 OAuth 的 MCP 服务但用户尚未授权时发出，
    // agent 暂停等待用户授权（或超时 / 取消） ===
    public static final String EVENT_MCP_OAUTH_REQUIRED = "mcp_oauth_required";
    public static final String EVENT_MCP_OAUTH_RESOLVED = "mcp_oauth_resolved";

    // === 错误 ===
    /** 错误事件 */
    public static final String EVENT_ERROR = "error";

    /** 本轮召回的长期记忆；在答案流出前发一次，UI 可展示答案看到了哪些记忆 */
    public static final String EVENT_MEMORY_RECALLED = "memory_recalled";

    /** 旧对话被摘要压缩以适配上下文窗口时发出 */
    public static final String EVENT_CONTEXT_COMPACTED = "context_compacted";

    /** 用户在运行中追加的消息被并入本轮时发出（见 agent drainSteerMessages） */
    public static final String EVENT_USER_MESSAGE_INJECTED = "user_message_injected";

    /** 会话标题更新 */
    public static final String EVENT_SESSION_TITLE = "session_title";

    /** 停止对话生成 */
    public static final String EVENT_STOP = "stop";

    private EventType() {
    }
}
