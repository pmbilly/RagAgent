package com.ragagent.llm.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Anthropic content 断点标记（对照 Go chat.anthropicCacheControl，
 * internal/models/chat/anthropic.go:28-31）。
 *
 * <p>{@code type} 恒输出（Go 无 omitempty），{@code ttl} 为空则省略
 * （Go 的 {@code json:"ttl,omitempty"}）。</p>
 */
@JsonPropertyOrder({"type", "ttl"})
public class AnthropicCacheControl {

    @JsonProperty("type")
    private String type = "";
    @JsonProperty("ttl")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String ttl;

    public AnthropicCacheControl() {
    }

    public AnthropicCacheControl(String type, String ttl) {
        this.type = type == null ? "" : type;
        this.ttl = ttl;
    }

    /** 由 {@link PromptCache#cacheControlFor} 的标记构造（Anthropic 路径固定 longTtl="1h"）。 */
    public static AnthropicCacheControl from(PromptCache.CacheControlMarker marker) {
        if (marker == null) {
            return null;
        }
        return new AnthropicCacheControl(marker.type(), marker.ttl());
    }

    public String getType() { return type; }
    public void setType(String v) { type = v == null ? "" : v; }
    public String getTtl() { return ttl; }
    public void setTtl(String v) { ttl = v; }
}
