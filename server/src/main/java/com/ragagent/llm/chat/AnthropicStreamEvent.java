package com.ragagent.llm.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Anthropic 流式事件（对照 Go chat.anthropicStreamEvent，
 * internal/models/chat/anthropic.go:85-113）。
 *
 * <p>事件类型：{@code message_start} / {@code content_block_start} / {@code content_block_delta}
 * / {@code content_block_stop} / {@code message_delta} / {@code message_stop} / {@code error}。</p>
 *
 * <p>usage 会在 <b>两处</b>出现：{@code message_start.message.usage}（输入侧）与
 * {@code message_delta.usage}（输出侧），两处都要按 max() 合并——见
 * {@link AnthropicChat#mergeAnthropicUsage}。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnthropicStreamEvent {

    @JsonProperty("type")
    private String type;
    @JsonProperty("index")
    private int index;
    @JsonProperty("content_block")
    private AnthropicContentBlock contentBlock;
    @JsonProperty("message")
    private MessagePayload message;
    @JsonProperty("delta")
    private Delta delta;
    @JsonProperty("usage")
    private AnthropicResponse.Usage usage;
    @JsonProperty("error")
    private AnthropicResponse.Error error;

    /** 对照 Go 里 message_start 事件内联的 usage 结构。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MessagePayload {
        @JsonProperty("usage")
        private AnthropicResponse.Usage usage;

        public AnthropicResponse.Usage getUsage() { return usage; }
    }

    /** 对照 Go 里 delta 的内联结构（text_delta / input_json_delta / message_delta 共用）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Delta {
        @JsonProperty("type")
        private String type;
        @JsonProperty("text")
        private String text;
        @JsonProperty("stop_reason")
        private String stopReason;
        @JsonProperty("partial_json")
        private String partialJson;

        public String getType() { return type; }
        public String getText() { return text; }
        public String getStopReason() { return stopReason; }
        public String getPartialJson() { return partialJson; }
    }

    public static final String TYPE_MESSAGE_START = "message_start";
    public static final String TYPE_CONTENT_BLOCK_START = "content_block_start";
    public static final String TYPE_CONTENT_BLOCK_DELTA = "content_block_delta";
    public static final String TYPE_CONTENT_BLOCK_STOP = "content_block_stop";
    public static final String TYPE_MESSAGE_DELTA = "message_delta";
    public static final String TYPE_MESSAGE_STOP = "message_stop";
    public static final String DELTA_TEXT = "text_delta";
    public static final String DELTA_INPUT_JSON = "input_json_delta";

    public String getType() { return type; }
    public int getIndex() { return index; }
    public AnthropicContentBlock getContentBlock() { return contentBlock; }
    public MessagePayload getMessage() { return message; }
    public Delta getDelta() { return delta; }
    public AnthropicResponse.Usage getUsage() { return usage; }
    public AnthropicResponse.Error getError() { return error; }
}
