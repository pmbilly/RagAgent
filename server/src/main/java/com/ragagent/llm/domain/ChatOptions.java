package com.ragagent.llm.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 聊天选项（对照 Go chat.ChatOptions，internal/models/chat/chat.go:28-52）。
 *
 * 字段序 = Go 声明序。恒输出键（无 omitempty）包括 thinking——Go 是 *bool，
 * nil 会输出 "thinking":null，故 Java 用 Boolean + ALWAYS 包含。
 */
@JsonPropertyOrder({
        "temperature", "top_p", "seed", "max_tokens", "max_completion_tokens",
        "frequency_penalty", "presence_penalty", "thinking", "tools", "tool_choice",
        "parallel_tool_calls", "format"
})
public class ChatOptions {

    @JsonProperty("temperature")
    private double temperature;
    @JsonProperty("top_p")
    private double topP;
    @JsonProperty("seed")
    private int seed;
    /**
     * MaxTokens 与 MaxCompletionTokens 是同一个补全预算的别名，调用方可设任一个；
     * {@link #completionBudget()} 优先取 MaxCompletionTokens。
     * 出站的 Chat Completions JSON **恰好**携带 max_tokens / max_completion_tokens 之一，
     * 按 provider 选择（见 WireCompletionTokenField）。
     */
    @JsonProperty("max_tokens")
    private int maxTokens;
    @JsonProperty("max_completion_tokens")
    private int maxCompletionTokens;
    @JsonProperty("frequency_penalty")
    private double frequencyPenalty;
    @JsonProperty("presence_penalty")
    private double presencePenalty;
    /** nil = 由模型默认决定 */
    @JsonProperty("thinking")
    private Boolean thinking;
    @JsonProperty("tools")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<ChatTool> tools;
    /** "auto" / "required" / "none" / 具体工具名 */
    @JsonProperty("tool_choice")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String toolChoice;
    /** nil = 由模型决定 */
    @JsonProperty("parallel_tool_calls")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Boolean parallelToolCalls;
    /** 非空时强制 JSON object，并把 schema 拼到最后一条 message 的 content 尾部 */
    @JsonProperty("format")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private JsonNode format;

    /** provider 路由键（OpenAI prompt_cache_key）。空则回退到调用上下文里的 session ID。 */
    @JsonIgnore
    private String promptCacheKey;
    /** provider prompt 缓存 TTL 控制；none 关闭缓存标记，空/short 为默认 5 分钟，long 请求 1h/24h。 */
    @JsonIgnore
    private CacheRetention cacheRetention;

    /** 补全预算：MaxCompletionTokens 优先，否则 MaxTokens（对照 Go CompletionBudget）。 */
    public int completionBudget() {
        return maxCompletionTokens > 0 ? maxCompletionTokens : maxTokens;
    }

    public double getTemperature() { return temperature; }
    public void setTemperature(double v) { temperature = v; }
    public double getTopP() { return topP; }
    public void setTopP(double v) { topP = v; }
    public int getSeed() { return seed; }
    public void setSeed(int v) { seed = v; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int v) { maxTokens = v; }
    public int getMaxCompletionTokens() { return maxCompletionTokens; }
    public void setMaxCompletionTokens(int v) { maxCompletionTokens = v; }
    public double getFrequencyPenalty() { return frequencyPenalty; }
    public void setFrequencyPenalty(double v) { frequencyPenalty = v; }
    public double getPresencePenalty() { return presencePenalty; }
    public void setPresencePenalty(double v) { presencePenalty = v; }
    public Boolean getThinking() { return thinking; }
    public void setThinking(Boolean v) { thinking = v; }
    public List<ChatTool> getTools() { return tools; }
    public void setTools(List<ChatTool> v) { tools = v; }
    public String getToolChoice() { return toolChoice; }
    public void setToolChoice(String v) { toolChoice = v; }
    public Boolean getParallelToolCalls() { return parallelToolCalls; }
    public void setParallelToolCalls(Boolean v) { parallelToolCalls = v; }
    public JsonNode getFormat() { return format; }
    public void setFormat(JsonNode v) { format = v; }
    public String getPromptCacheKey() { return promptCacheKey; }
    public void setPromptCacheKey(String v) { promptCacheKey = v; }
    public CacheRetention getCacheRetention() { return cacheRetention; }
    public void setCacheRetention(CacheRetention v) { cacheRetention = v; }
}
