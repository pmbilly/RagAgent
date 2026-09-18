package com.ragagent.stream;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * live-run 标记在 Redis 里的存储形态（对照 Go {@code liveRunPayload}，
 * internal/stream/redis_manager.go:364-367）。
 *
 * <p>字段序 = Go struct 声明序。两个键无 omitempty，恒输出。</p>
 */
@JsonPropertyOrder({"assistant_message_id", "request_id"})
public record LiveRunPayload(
        @JsonProperty("assistant_message_id") String assistantMessageId,
        @JsonProperty("request_id") String requestId) {
}
