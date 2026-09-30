package com.ragagent.wiki.service;

/**
 * LLM 调用的记账元数据：由 {@code generateWithTemplate} 设置，
 * 供日志与 langfuse 追踪读取。
 *
 * <h2>为什么是 ThreadLocal 而不是参数</h2>
 * <p>为了不动 LLM 客户端的方法签名就把 purpose / 前缀指纹带进 LLM 层，
 * 用 {@link ThreadLocal} 承载——本类只在
 * "设置 → 同线程调用 LLM → 清除"这个窗口内使用，不跨线程传递
 * （跨虚拟线程传递必须显式传值，禁止共享 ThreadLocal）。</p>
 *
 * <p><b>注意执行线程</b>：{@code generateWithTemplate} 里的 LLM 调用跑在
 * {@link SingleFlight} 提交出去的虚拟线程上，因此元数据的设置与清除都在
 * <b>那个</b>线程内完成（在 {@code execute} 闭包里），而不是在调用方线程。</p>
 */
public final class WikiLlmCallMetadata {

    private WikiLlmCallMetadata() {}

    /** (purpose, prefixFingerprint) 二元组 */
    public record Metadata(String purpose, String prefixFingerprint) {}

    private static final ThreadLocal<Metadata> CURRENT = new ThreadLocal<>();

    /** 设置当前线程的记账元数据 */
    public static void set(String purpose, String prefixFingerprint) {
        CURRENT.set(new Metadata(purpose, prefixFingerprint));
    }

    /**
     * 读取当前线程的记账元数据；未设置时返回
     * {@code ("", "")}。
     */
    public static Metadata current() {
        Metadata m = CURRENT.get();
        return m == null ? new Metadata("", "") : m;
    }

    /** 清除。必须显式清，否则污染线程池。 */
    public static void clear() {
        CURRENT.remove();
    }
}
