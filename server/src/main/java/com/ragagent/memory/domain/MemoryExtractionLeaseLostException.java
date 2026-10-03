package com.ragagent.memory.domain;

/**
 * 抽取租约丢失异常，固定消息：
 * <pre>"memory extraction lease lost"</pre>
 *
 * <p>抽取 worker 的租约在它干活期间被别人抢走（或过期）时抛出。
 * 调用方以 {@code catch (MemoryExtractionLeaseLostException e)} 判定；消息逐字固定。</p>
 */
public class MemoryExtractionLeaseLostException extends RuntimeException {

    public static final String MESSAGE = "memory extraction lease lost";

    public MemoryExtractionLeaseLostException() {
        super(MESSAGE);
    }
}
