package com.ragagent.tracing.langfuse;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 对照 Go langfuse.TokenUsage（events.go L9-18）：Langfuse 规范化用量 schema，
 * 作为 {@code langfuse.observation.usage_details} 属性的 JSON 值。
 *
 * <p>Go 的 json tag 带 omitempty → 零值字段省略；Java 侧逐字段
 * {@code @JsonInclude(NON_DEFAULT)}（unit 走 NON_EMPTY）。字段序 = Go 声明序。</p>
 */
public final class TokenUsage {

    @JsonProperty("input")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public int input;

    @JsonProperty("output")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public int output;

    @JsonProperty("total")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public int total;

    @JsonProperty("cache_read_input_tokens")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public int cacheRead;

    @JsonProperty("cache_creation_input_tokens")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public int cacheWrite;

    @JsonProperty("cache_miss_input_tokens")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public int cacheMiss;

    @JsonProperty("unit")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public String unit = "";

    public TokenUsage() {
    }

    /** 三值便利构造（input/output/total；Go 调用点最常见的形态）。 */
    public static TokenUsage of(int input, int output, int total) {
        TokenUsage u = new TokenUsage();
        u.input = input;
        u.output = output;
        u.total = total;
        return u;
    }
}
