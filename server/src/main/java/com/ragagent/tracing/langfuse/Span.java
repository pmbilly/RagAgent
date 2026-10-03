package com.ragagent.tracing.langfuse;

import java.util.Map;

/**
 * 一个非 LLM 调用的逻辑工作单元的活跃观测（ID + name + metadata）。
 */
public interface Span {

    /** Span ID（no-op 实现返回空串）。 */
    String getId();

    /**
     * 记录一次观测的终态。finish 期的 metadata 与开启期的合并（不是覆写）。
     */
    void finish(Object output, Map<String, Object> metadata, String err);
}
