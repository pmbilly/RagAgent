package com.ragagent.mcp.protocol;

/**
 * OAuth token 生命周期钩子（对照 Go internal/mcp/client.go:316-335 的 {@code oauthCall}
 * 所依赖的 {@code oauthRuntime}）。
 *
 * <p><b>这是留给后续 OAuth agent 的注入点</b>：Java 侧已完成 {@code oauthCall} 的
 * <b>调用纪律</b>（先保鲜 → 执行 → 仅对授权失败强制刷新一次并重试一次），
 * 具体实现（token 存储、刷新租约、PKCE、动态客户端注册）由 OAuth 模块提供。</p>
 *
 * <p>Go 侧的两处判定在 Java 里分别对应：</p>
 * <ul>
 *   <li>{@code oauthRuntime.ensureFresh(ctx, false, nil)} → {@link #ensureFresh}</li>
 *   <li>{@code isOAuthAuthorizationFailure(err)} → {@link #isAuthorizationFailure}</li>
 *   <li>{@code client.GetOAuthHandler(err)} → 不再单独暴露：强制刷新时把<b>触发失败的原始异常</b>
 *       一并传回实现方，由它自己从异常里取 handler（Go 也是这么做的）。</li>
 * </ul>
 */
public interface McpOAuthRuntime {

    /**
     * 保证当前 principal 的 access token 可用（必要时刷新）。
     *
     * @param ctx          调用上下文
     * @param forceRefresh true = 上一次调用因授权失败，强制刷新一次后再重试
     * @param trigger      触发强制刷新的原始异常（首次保鲜时为 null）
     */
    void ensureFresh(McpContext ctx, boolean forceRefresh, Throwable trigger);

    /** 对照 Go {@code isOAuthAuthorizationFailure}：判断异常是否为"该刷新 token 了"的授权失败。 */
    boolean isAuthorizationFailure(Throwable e);
}
