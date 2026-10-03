package com.ragagent.storage.support;

/**
 * 把「本请求必须走 handle 模式」钉住的请求级标记。
 *
 * <p><b>为什么匿名入口需要它</b>：embed 访客只凭渠道的会话句柄鉴权，
 * 所以它必须继续走渠道作用域的 {@code /embed/…/files} 代理取图，
 * 而不是拿到一个可分享、免凭据的公网 URL。</p>
 *
 * <p><b>形态</b>：沿用与 {@link com.ragagent.common.context.TenantContext}、
 * {@code APIKeyScopeContext} 一致的 ThreadLocal 方案——每请求一个线程，
 * <b>跨线程必须显式传值</b>。</p>
 *
 * <p><b>生命周期由设置方负责</b>：在能进入本包的入口（未来是 embed 渠道过滤器）
 * {@link #force()}，并在请求结束的 finally 里 {@link #clear()}。
 * ThreadLocal 不会随作用域自动失效——漏清会污染同线程的下一个请求。</p>
 */
public final class StorageUrlContext {

    private static final ThreadLocal<Boolean> FORCED_HANDLE = new ThreadLocal<>();

    private StorageUrlContext() {
    }

    /** 把本请求钉在 {@link Mode#HANDLE}。 */
    public static void force() {
        FORCED_HANDLE.set(Boolean.TRUE);
    }

    /** 本请求是否被钉在 handle 模式。 */
    public static boolean isHandleModeForced() {
        return Boolean.TRUE.equals(FORCED_HANDLE.get());
    }

    public static void clear() {
        FORCED_HANDLE.remove();
    }
}
