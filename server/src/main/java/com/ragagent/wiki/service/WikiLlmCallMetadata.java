package com.ragagent.wiki.service;

/**
 * LLM 调用的记账元数据（对照 Go {@code types.WithLLMCallMetadata} /
 * {@code types.LLMCallMetadataFromContext}，被 {@code generateWithTemplate} 设置，
 * 供日志与 langfuse 追踪读取）。
 *
 * <h2>为什么是 ThreadLocal 而不是参数</h2>
 * <p>Go 把它挂在 {@code context.Context} 上，是为了不动 {@code chat.Chat} 的签名
 * 就把 purpose / 前缀指纹带进 LLM 层。Java 侧没有 ctx，最近的等价物是
 * {@link ThreadLocal}——而且在这里是<b>正确的</b>等价物：本类只在
 * "设置 → 同线程调用 LLM → 清除"这个窗口内使用，不跨线程传递
 * （约定文档 §5：跨虚拟线程传递必须显式传值，禁止共享 ThreadLocal）。</p>
 *
 * <p><b>注意执行线程</b>：{@code generateWithTemplate} 里的 LLM 调用跑在
 * {@link SingleFlight} 提交出去的虚拟线程上，因此元数据的设置与清除都在
 * <b>那个</b>线程内完成（在 {@code execute} 闭包里），而不是在调用方线程。
 * 这与 Go 里"ctx 被传入闭包、在闭包内设置"的时序一致。</p>
 */
public final class WikiLlmCallMetadata {

    private WikiLlmCallMetadata() {}

    /** 对照 Go 的 {@code (purpose, prefixFingerprint)} 二元组 */
    public record Metadata(String purpose, String prefixFingerprint) {}

    private static final ThreadLocal<Metadata> CURRENT = new ThreadLocal<>();

    /** 对照 Go {@code types.WithLLMCallMetadata} */
    public static void set(String purpose, String prefixFingerprint) {
        CURRENT.set(new Metadata(purpose, prefixFingerprint));
    }

    /**
     * 对照 Go {@code types.LLMCallMetadataFromContext}：未设置时返回
     * {@code ("", "")}（Go 的 ok=false 分支返回零值字符串）。
     */
    public static Metadata current() {
        Metadata m = CURRENT.get();
        return m == null ? new Metadata("", "") : m;
    }

    /** 清除。Go 侧随 ctx 生命周期自动消失；Java 必须显式清，否则污染线程池。 */
    public static void clear() {
        CURRENT.remove();
    }
}
