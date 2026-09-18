package com.ragagent.memory.domain;

/**
 * 对照 Go {@code types.ErrMemoryExtractionLeaseLost}（internal/types/memory_extraction.go L12）：
 * <pre>"memory extraction lease lost"</pre>
 *
 * <p>抽取 worker 的租约在它干活期间被别人抢走（或过期）时抛出。
 * Go 侧调用方同样用 {@code errors.Is} 判定；消息逐字对齐。</p>
 */
public class MemoryExtractionLeaseLostException extends RuntimeException {

    public static final String MESSAGE = "memory extraction lease lost";

    public MemoryExtractionLeaseLostException() {
        super(MESSAGE);
    }
}
