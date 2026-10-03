package com.ragagent.mcp.protocol;

/**
 * OAuth token 生命周期钩子。
 *
 * <p><b>这是留给 OAuth 模块的注入点</b>：协议层已完成调用纪律
 * （先保鲜 → 执行 → 仅对授权失败强制刷新一次并重试一次），
 * 具体实现（token 存储、刷新租约、PKCE、动态客户端注册）由 OAuth 模块提供。</p>
 *
 * <p>两个方法分别覆盖"保鲜判定"与"授权失败判定"；强制刷新时把<b>触发失败的原始异常</b>
 * 一并传回实现方，由它自己从异常里取 handler。</p>
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

    /** 判断异常是否为"该刷新 token 了"的授权失败。 */
    boolean isAuthorizationFailure(Throwable e);
}
