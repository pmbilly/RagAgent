package com.ragagent.memory.domain;

/**
 * 记忆冲突异常，固定消息：
 * <pre>"memory changed; reload before applying this proposal"</pre>
 *
 * <p>由 {@link com.ragagent.memory.mapper.MemoryRepository#saveItem} 与
 * {@code confirmPendingItem} 在"要替换的目标已经不在可替换状态"时抛出。
 * 调用方以 {@code catch (MemoryConflictException e)} 判定——消息逐字固定，
 * 因为它会进日志、也可能进响应。</p>
 */
public class MemoryConflictException extends RuntimeException {

    public static final String MESSAGE = "memory changed; reload before applying this proposal";

    public MemoryConflictException() {
        super(MESSAGE);
    }
}
