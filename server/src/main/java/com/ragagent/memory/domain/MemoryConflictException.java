package com.ragagent.memory.domain;

/**
 * 对照 Go {@code types.ErrMemoryConflict}（internal/types/memory_extraction.go L11）：
 * <pre>"memory changed; reload before applying this proposal"</pre>
 *
 * <p>由 {@link com.ragagent.memory.mapper.MemoryRepository#saveItem} 与
 * {@code confirmPendingItem} 在"要替换的目标已经不在可替换状态"时抛出。
 * Go 侧调用方用 {@code errors.Is(err, types.ErrMemoryConflict)} 判定，
 * Java 侧用 {@code catch (MemoryConflictException e)}——**消息逐字对齐**，
 * 因为它会经 {@code %w} 包装后进日志、也可能进响应。</p>
 */
public class MemoryConflictException extends RuntimeException {

    public static final String MESSAGE = "memory changed; reload before applying this proposal";

    public MemoryConflictException() {
        super(MESSAGE);
    }
}
