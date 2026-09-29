package com.ragagent.agent.tools;

/**
 * 可选的资源释放接口。
 * 实现了本接口的工具会在 registry cleanup（agent 会话收尾）时被逐个调用。
 */
public interface Cleanable {

    /** 释放工具持有的资源。 */
    void cleanup();
}
