package com.ragagent.auth.apikey.domain;

/**
 * 当前请求的 {@link TenantAPIKeyScope} 持有者。
 *
 * <p>对照 Go 的 {@code types.WithTenantAPIKeyScope(ctx, scope)} /
 * {@code types.TenantAPIKeyScopeFromContext(ctx)}（internal/types/tenant_api_key.go
 * L277-290）——Go 把 scope 塞进 {@code context.Context}，Java 侧没有逐层透传的 ctx，
 * 于是用与 {@link com.ragagent.common.context.TenantContext} 一致的 ThreadLocal 方案：
 * **每个请求一个线程**（Servlet + 虚拟线程模型下成立），
 * 跨线程（异步任务）必须显式把 scope 值传过去，禁止共享本 ThreadLocal。</p>
 *
 * <p>存放的是**已归一化**的 scope——对照 Go
 * {@code WithTenantAPIKeyScope} 写入前先调 {@code Normalize()}。</p>
 *
 * <p>生命周期由 {@code APIKeyAuthChannel} 负责：认证成功时
 * {@link #set(TenantAPIKeyScope)}，请求结束（finally）{@link #clear()}。
 * JWT 会话没有 API Key scope → {@link #current()} 返回 {@code null}，
 * 这正是"JWT 直通、API Key 才受门禁"的判定依据。</p>
 */
public final class APIKeyScopeContext {

    private static final ThreadLocal<TenantAPIKeyScope> SCOPE = new ThreadLocal<>();

    private APIKeyScopeContext() {
    }

    /** 对照 {@code WithTenantAPIKeyScope}：写入前先归一化。 */
    public static void set(TenantAPIKeyScope scope) {
        SCOPE.set(scope == null ? null : scope.normalize());
    }

    /** 对照 {@code TenantAPIKeyScopeFromContext}：非 API Key 主体 → null。 */
    public static TenantAPIKeyScope current() {
        return SCOPE.get();
    }

    /** 是否 API Key 主体（对照 Go 的 {@code _, ok := ...FromContext(ctx)}）。 */
    public static boolean present() {
        return SCOPE.get() != null;
    }

    public static void clear() {
        SCOPE.remove();
    }
}
