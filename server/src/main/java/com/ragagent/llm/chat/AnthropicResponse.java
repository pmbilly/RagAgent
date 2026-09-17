package com.ragagent.llm.chat;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Anthropic 非流式响应（对照 Go chat.anthropicResponse，
 * internal/models/chat/anthropic.go:61-83）。
 *
 * <p><b>关键在于 {@code cache_creation_input_tokens} / {@code cache_read_input_tokens} 是
 * 可空指针</b>（Java 用包装类型）："没上报"（null）与"上报了 0"是两回事，
 * {@code CacheReported} 与总量算法都依赖这个区分。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnthropicResponse {

    @JsonProperty("id")
    private String id;
    @JsonProperty("type")
    private String type;
    @JsonProperty("role")
    private String role;
    @JsonProperty("content")
    private List<Block> content;
    @JsonProperty("stop_reason")
    private String stopReason;
    @JsonProperty("usage")
    private Usage usage;
    @JsonProperty("error")
    private Error error;

    /** 对照 Go anthropicResponse.content 的匿名结构体。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Block {
        @JsonProperty("type")
        private String type;
        @JsonProperty("text")
        private String text;
        @JsonProperty("id")
        private String id;
        @JsonProperty("name")
        private String name;
        @JsonProperty("input")
        private JsonNode input;

        public String getType() { return type; }
        public String getText() { return text; }
        public String getId() { return id; }
        public String getName() { return name; }
        public JsonNode getInput() { return input; }
    }

    /** 对照 Go anthropicResponse.usage。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {
        @JsonProperty("input_tokens")
        private int inputTokens;
        @JsonProperty("output_tokens")
        private int outputTokens;
        @JsonProperty("cache_creation_input_tokens")
        private Integer cacheCreationInputTokens;
        @JsonProperty("cache_read_input_tokens")
        private Integer cacheReadInputTokens;

        public int getInputTokens() { return inputTokens; }
        public int getOutputTokens() { return outputTokens; }
        /** 未上报时返回 null（不是 0）——调用方必须保留这个区别。 */
        public Integer getCacheCreationInputTokens() { return cacheCreationInputTokens; }
        public Integer getCacheReadInputTokens() { return cacheReadInputTokens; }
    }

    /** 对照 Go 的匿名 error 结构体。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Error {
        @JsonProperty("type")
        private String type;
        @JsonProperty("message")
        private String message;

        public String getType() { return type; }
        public String getMessage() { return message; }
    }

    public String getId() { return id; }
    public String getType() { return type; }
    public String getRole() { return role; }
    public List<Block> getContent() { return content; }
    public String getStopReason() { return stopReason; }
    public Usage getUsage() { return usage; }
    public Error getError() { return error; }
}
