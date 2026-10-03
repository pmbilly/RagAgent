package com.ragagent.llm.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 聊天消息。
 *
 * JSON 字段序 = 声明序；role/content 恒输出，其余为空时省略（NON_EMPTY）。
 */
@JsonPropertyOrder({
        "role", "content", "multi_content", "name", "tool_call_id",
        "tool_calls", "images", "reasoning_content"
})
public class ChatMessage {

    /** 引擎合成消息的标记。 */
    public static final String KIND_COMPACTION_SUMMARY = "compaction_summary";

    /** 角色：system / user / assistant / tool */
    @JsonProperty("role")
    private String role = "";
    @JsonProperty("content")
    private String content = "";
    /** 多内容消息（文本+图片混排） */
    @JsonProperty("multi_content")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<MessageContentPart> multiContent;
    /** 工具名（tool 角色用） */
    @JsonProperty("name")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String name;
    /** 工具调用 ID（tool 角色用） */
    @JsonProperty("tool_call_id")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String toolCallId;
    /** 工具调用列表（assistant 角色用） */
    @JsonProperty("tool_calls")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<ToolCall> toolCalls;
    /** 图片 URL（仅当前 user 消息；转 OpenAI 时降级为 MultiContent） */
    @JsonProperty("images")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> images;
    /**
     * assistant 推理类模型上一轮输出的思考内容。部分供应商（MiMo、DeepSeek V3.2/V4
     * thinking 模式）要求多轮对话中把 assistant 的 reasoning_content 原样回传，
     * 否则以 400 拒绝；不要求的供应商会忽略未知字段，无副作用。
     */
    @JsonProperty("reasoning_content")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String reasoningContent;
    /**
     * 引擎内部记账，**不上线**：某些端点会拒绝未知消息字段。
     * 用于让压缩逻辑把自己的摘要与真实用户轮次区分开（否则摘要会被再次喂进摘要轮，
     * 退化成"摘要的摘要"）。
     */
    @JsonIgnore
    private String kind;

    public ChatMessage() {
    }

    public ChatMessage(String role, String content) {
        this.role = role == null ? "" : role;
        this.content = content == null ? "" : content;
    }

    /** 便捷构造：system 消息 */
    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    /** 便捷构造：user 消息 */
    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    /** 便捷构造：tool 结果消息 */
    public static ChatMessage tool(String toolCallId, String name, String content) {
        ChatMessage m = new ChatMessage("tool", content);
        m.toolCallId = toolCallId;
        m.name = name;
        return m;
    }

    public String getRole() { return role; }
    public void setRole(String v) { role = v == null ? "" : v; }
    public String getContent() { return content; }
    public void setContent(String v) { content = v == null ? "" : v; }
    public List<MessageContentPart> getMultiContent() { return multiContent; }
    public void setMultiContent(List<MessageContentPart> v) { multiContent = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String v) { toolCallId = v; }
    public List<ToolCall> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCall> v) { toolCalls = v; }
    public List<String> getImages() { return images; }
    public void setImages(List<String> v) { images = v; }
    public String getReasoningContent() { return reasoningContent; }
    public void setReasoningContent(String v) { reasoningContent = v; }
    public String getKind() { return kind; }
    public void setKind(String v) { kind = v; }

    @JsonIgnore
    public boolean isCompactionSummary() {
        return KIND_COMPACTION_SUMMARY.equals(kind);
    }
}
