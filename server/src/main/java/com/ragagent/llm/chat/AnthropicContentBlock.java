package com.ragagent.llm.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Anthropic content block（对照 Go chat.anthropicContentBlock，
 * internal/models/chat/anthropic.go:33-42）。
 *
 * <p><b>字段序 = Go 声明序</b>，序列化时 {@code type} 恒输出，其余按各自 omitempty 语义：</p>
 * <ul>
 *   <li>{@code text} / {@code id} / {@code name} / {@code tool_use_id}：空串省略 → NON_EMPTY；</li>
 *   <li>{@code cache_control}：指针，nil 省略 → NON_NULL；</li>
 *   <li>{@code input}：Go 是 {@code json.RawMessage}，len==0 才省略。<b>空对象 {@code {}} 必须照发</b>
 *       （模型显式要一个无参工具调用），故这里用 NON_NULL 而非 NON_EMPTY；</li>
 *   <li>{@code content}：Go 是 {@code any}，只有 nil 省略，<b>空串照发</b>
 *       （anthropic_tools_test 断言 {@code "tool_use_id":"b","content":""}）→ NON_NULL。</li>
 * </ul>
 */
@JsonPropertyOrder({"type", "text", "cache_control", "id", "name", "input", "tool_use_id", "content"})
public class AnthropicContentBlock {

    public static final String TYPE_TEXT = "text";
    public static final String TYPE_TOOL_USE = "tool_use";
    public static final String TYPE_TOOL_RESULT = "tool_result";

    @JsonProperty("type")
    private String type = "";
    @JsonProperty("text")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String text;
    @JsonProperty("cache_control")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private AnthropicCacheControl cacheControl;
    @JsonProperty("id")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String id;
    @JsonProperty("name")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String name;
    @JsonProperty("input")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonNode input;
    @JsonProperty("tool_use_id")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String toolUseId;
    /** tool_result 的内容（Go 的 {@code any}，实际只用字符串） */
    @JsonProperty("content")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Object content;

    public AnthropicContentBlock() {
    }

    /** 对照 Go 的 {@code anthropicContentBlock{Type: "text", Text: ...}}。 */
    public static AnthropicContentBlock text(String text) {
        AnthropicContentBlock block = new AnthropicContentBlock();
        block.type = TYPE_TEXT;
        block.text = text;
        return block;
    }

    /** 对照 Go 的 {@code anthropicContentBlock{Type: "tool_use", ID, Name, Input}}。 */
    public static AnthropicContentBlock toolUse(String id, String name, JsonNode input) {
        AnthropicContentBlock block = new AnthropicContentBlock();
        block.type = TYPE_TOOL_USE;
        block.id = id;
        block.name = name;
        block.input = input;
        return block;
    }

    /** 对照 Go 的 {@code anthropicContentBlock{Type: "tool_result", ToolUseID, Content}}。 */
    public static AnthropicContentBlock toolResult(String toolUseId, Object content) {
        AnthropicContentBlock block = new AnthropicContentBlock();
        block.type = TYPE_TOOL_RESULT;
        block.toolUseId = toolUseId;
        block.content = content;
        return block;
    }

    public String getType() { return type; }
    public void setType(String v) { type = v == null ? "" : v; }
    public String getText() { return text; }
    public void setText(String v) { text = v; }
    public AnthropicCacheControl getCacheControl() { return cacheControl; }
    public void setCacheControl(AnthropicCacheControl v) { cacheControl = v; }
    public String getId() { return id; }
    public void setId(String v) { id = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public JsonNode getInput() { return input; }
    public void setInput(JsonNode v) { input = v; }
    public String getToolUseId() { return toolUseId; }
    public void setToolUseId(String v) { toolUseId = v; }
    public Object getContent() { return content; }
    public void setContent(Object v) { content = v; }

    /** 请求方向：把 {@link #content} 当字符串读（Go 里 tool_result 只塞 string）。 */
    public String contentText() {
        return content instanceof String s ? s : "";
    }
}
