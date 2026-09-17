package com.ragagent.llm.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Token 用量（对照 Go types.TokenUsage，internal/types/chat.go:21-157）。
 *
 * JSON 契约（字段序 = Go struct 声明序，恒输出/省略规则照抄）：
 * prompt_tokens/completion_tokens/total_tokens/cache_reported 无 omitempty 恒输出；
 * cached_tokens/cache_read_tokens/cache_write_tokens/cache_miss_tokens/cache_status 带 omitempty
 * → Java 侧用 NON_DEFAULT（int 0 / 空串省略）。
 *
 * 行为契约（不只是数据——Accumulate 的合并语义是 agent 多轮统计的依据）：
 * - SetPromptCacheUsage：负数截 0；reported=false → "unreported"；read>0 → "hit"；否则 "miss"
 * - MarkPromptCacheUnsupported：先按未上报归零，再置 "unsupported"
 * - Accumulate：各计数独立累加（缓存计数是 prompt 的子集，绝不折进 prompt），
 *   reported 取 OR，status 由合并后的计数重算 → 任一次命中即整体为 hit
 * - PromptCacheHitRate：CacheReadTokens/PromptTokens*100，prompt<=0 时 0
 *
 * 持久化：Go 实现 driver.Valuer/sql.Scanner（jsonb 列），Java 侧对应 PgJsonTypeHandler。
 */
@JsonPropertyOrder({
        "prompt_tokens", "completion_tokens", "total_tokens",
        "cached_tokens", "cache_read_tokens", "cache_write_tokens", "cache_miss_tokens",
        "cache_reported", "cache_status"
})
public class TokenUsage {

    @JsonProperty("prompt_tokens")
    private int promptTokens;
    @JsonProperty("completion_tokens")
    private int completionTokens;
    @JsonProperty("total_tokens")
    private int totalTokens;
    /** 历史别名（= cache_read_tokens），为兼容既有 API 消费者保留在线上。 */
    @JsonProperty("cached_tokens")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int cachedTokens;
    @JsonProperty("cache_read_tokens")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int cacheReadTokens;
    @JsonProperty("cache_write_tokens")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int cacheWriteTokens;
    @JsonProperty("cache_miss_tokens")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int cacheMissTokens;
    @JsonProperty("cache_reported")
    private boolean cacheReported;
    @JsonProperty("cache_status")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private PromptCacheStatus cacheStatus;

    public TokenUsage() {
    }

    /**
     * 把厂商特有的缓存计数归一化进共享用量模型。
     * promptTokens 仍是厂商的输入 token 总数；read/write/miss 是描述性子集，**不得**加进去。
     */
    public void setPromptCacheUsage(int read, int write, int miss, boolean reported) {
        if (read < 0) {
            read = 0;
        }
        if (write < 0) {
            write = 0;
        }
        if (miss < 0) {
            miss = 0;
        }
        this.cachedTokens = read;
        this.cacheReadTokens = read;
        this.cacheWriteTokens = write;
        this.cacheMissTokens = miss;
        this.cacheReported = reported;
        if (!reported) {
            this.cacheStatus = PromptCacheStatus.UNREPORTED;
        } else if (read > 0) {
            this.cacheStatus = PromptCacheStatus.HIT;
        } else {
            this.cacheStatus = PromptCacheStatus.MISS;
        }
    }

    /** 标记该 provider/model 路径无法上报服务端 prompt 缓存用量。 */
    public void markPromptCacheUnsupported() {
        setPromptCacheUsage(0, 0, 0, false);
        this.cacheStatus = PromptCacheStatus.UNSUPPORTED;
    }

    /** 把另一次调用的用量并入本对象（子集语义见类注释）。 */
    public void accumulate(TokenUsage other) {
        if (other == null) {
            return;
        }
        this.promptTokens += other.promptTokens;
        this.completionTokens += other.completionTokens;
        this.totalTokens += other.totalTokens;
        this.cachedTokens += other.cachedTokens;
        this.cacheReadTokens += other.cacheReadTokens;
        this.cacheWriteTokens += other.cacheWriteTokens;
        this.cacheMissTokens += other.cacheMissTokens;
        this.cacheReported = this.cacheReported || other.cacheReported;
        if (!this.cacheReported) {
            this.cacheStatus = mergeUnreportedCacheStatus(this.cacheStatus, other.cacheStatus);
        } else if (this.cacheReadTokens > 0) {
            this.cacheStatus = PromptCacheStatus.HIT;
        } else {
            this.cacheStatus = PromptCacheStatus.MISS;
        }
    }

    /**
     * 合并"从未上报"状态。"unsupported" 只在两次都是 unsupported 时保留——
     * 首次累加采用传入的分类，其后任何非 unsupported 的调用都降级为 unreported。
     */
    private static PromptCacheStatus mergeUnreportedCacheStatus(PromptCacheStatus accumulated,
                                                                PromptCacheStatus incoming) {
        if (accumulated == null) {
            return incoming == null ? PromptCacheStatus.UNREPORTED : incoming;
        }
        if (accumulated == PromptCacheStatus.UNSUPPORTED && incoming == PromptCacheStatus.UNSUPPORTED) {
            return PromptCacheStatus.UNSUPPORTED;
        }
        return PromptCacheStatus.UNREPORTED;
    }

    /** 缓存读取 token 占 prompt 总数的百分比；prompt 为空时 0。 */
    public double promptCacheHitRate() {
        if (promptTokens <= 0) {
            return 0;
        }
        return (double) cacheReadTokens / (double) promptTokens * 100;
    }

    public int getPromptTokens() { return promptTokens; }
    public void setPromptTokens(int v) { promptTokens = v; }
    public int getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(int v) { completionTokens = v; }
    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int v) { totalTokens = v; }
    public int getCachedTokens() { return cachedTokens; }
    public int getCacheReadTokens() { return cacheReadTokens; }
    public int getCacheWriteTokens() { return cacheWriteTokens; }
    public int getCacheMissTokens() { return cacheMissTokens; }
    public boolean isCacheReported() { return cacheReported; }
    public PromptCacheStatus getCacheStatus() { return cacheStatus; }
    public void setCacheStatus(PromptCacheStatus v) { cacheStatus = v; }
}
