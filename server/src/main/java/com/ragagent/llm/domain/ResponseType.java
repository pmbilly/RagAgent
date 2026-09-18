package com.ragagent.llm.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 流式响应类型（对照 Go types.ResponseType，internal/types/chat.go:211-283，共 22 个）。
 *
 * 阶段 4.0（chat 包）只产出 ANSWER / THINKING / TOOL_CALL / ERROR；
 * 其余由后续的 session/SSE（阶段 5）与 agent 引擎（阶段 7）产出——
 * 常量在此集中定义，避免各模块各写一份字符串。
 *
 * 注意：这是**线上契约**（SSE event 的 response_type 字段），值必须与 Go 逐字一致。
 */
public enum ResponseType {

    /** 答案正文 */
    ANSWER("answer"),
    /** 检索引用 */
    REFERENCES("references"),
    /** 思考过程（agent thought） */
    THINKING("thinking"),
    /** 工具调用 */
    TOOL_CALL("tool_call"),
    /** 工具结果 */
    TOOL_RESULT("tool_result"),
    /** 安装器进度（不结束该工具卡片） */
    INSTALL_OUTPUT("install_output"),
    /** 命令输出（更新待定工具卡片但不结束它） */
    COMMAND_OUTPUT("command_output"),
    /** 错误 */
    ERROR("error"),
    /** agent 反思 */
    REFLECTION("reflection"),
    /** 会话标题 */
    SESSION_TITLE("session_title"),
    /** 查询已收到并开始处理 */
    AGENT_QUERY("agent_query"),
    /** agent 完成 */
    COMPLETE("complete"),
    /** 答案流出后正在把 skill/sandbox 产物复制到持久存储（UI 显示工具栏占位） */
    ARTIFACTS_PENDING("artifacts_pending"),
    /** MCP 工具被标记为危险，需用户批准后才能继续执行 */
    TOOL_APPROVAL_REQUIRED("tool_approval_required"),
    /** 用户已批准/拒绝（或超时）；供 UI 回放 */
    TOOL_APPROVAL_RESOLVED("tool_approval_resolved"),
    /** 调用了需 OAuth 的 MCP 服务但用户未授权，agent 暂停等待 */
    MCP_OAUTH_REQUIRED("mcp_oauth_required"),
    /** 授权完成/超时/取消；供 UI 回放 */
    MCP_OAUTH_RESOLVED("mcp_oauth_resolved"),
    /** 本次回答注入的长期记忆，供 UI 展示与删除 */
    MEMORY_RECALLED("memory_recalled"),
    /** 运行中客户端 POST 的单轮控制信号（走 StreamManager 独立子列表，不上用户可见流） */
    STEER("steer"),
    /** 被引导的消息已并入运行中的轮次 */
    USER_MESSAGE_INJECTED("user_message_injected"),
    /** 旧对话被摘要压缩以适配上下文窗口 */
    CONTEXT_COMPACTED("context_compacted"),
    /** skill 安装交给安装器 agent 的指令（仅安装流水线发出，且最先发出） */
    INSTALL_PROMPT("install_prompt"),
    /**
     * 停止生成（对照 Go handler 里 {@code types.ResponseType(event.EventStop)} 的
     * 字符串强转——"stop" 不在 Go 的 ResponseType 常量表里，但确实以该值落进
     * StreamManager 的存储契约，见 SessionController.stopSession）。
     */
    STOP("stop");

    private final String value;

    ResponseType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ResponseType fromValue(String v) {
        if (v == null || v.isEmpty()) {
            return null;
        }
        for (ResponseType t : values()) {
            if (t.value.equals(v)) {
                return t;
            }
        }
        return null;
    }
}
