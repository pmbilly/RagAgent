package com.ragagent.agent.tools;

/**
 * 可选的资源释放接口（对照 Go {@code types.Cleanable}，internal/types/agent.go:346-348）。
 * 实现了本接口的工具会在 registry cleanup（agent 会话收尾）时被逐个调用。
 */
public interface Cleanable {

    /** 释放工具持有的资源（对照 Cleanup(ctx)；ctx 在 Java 侧无对应物，no-op）。 */
    void cleanup();
}
