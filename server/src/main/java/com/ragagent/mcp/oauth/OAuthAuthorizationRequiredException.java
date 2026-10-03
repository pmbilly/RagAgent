package com.ragagent.mcp.oauth;

import com.ragagent.mcp.protocol.McpAuthorizationRequiredException;

/**
 * OAuth 传输层"需要授权"信号，并<b>携带引发它的 handler</b>。
 *
 * <p>与 protocol 包既有的 {@link McpAuthorizationRequiredException} 的关系刻意做成继承：
 * 异常链上按父类判定也能命中本类——
 * 这正是 {@code isOAuthAuthorizationFailure} 能一次覆盖两种 401 的原因。</p>
 *
 * <p>{@code resourceMetadataUrl} 恒为空串：本异常表示"OAuth 已装配但当前 principal 没 token"，
 * 不是"服务端广告了 metadata 而本服务没配 OAuth"。故
 * {@code McpAuthHeaders.asOAuthRequired} 不会把它误升级成"请改用 OAuth"。</p>
 *
 * <p>消息沿用父类的哨兵文案 {@code "authorization required"}，只进日志。</p>
 */
public class OAuthAuthorizationRequiredException extends McpAuthorizationRequiredException {

    private static final long serialVersionUID = 1L;

    private final transient OAuthHandler handler;

    public OAuthAuthorizationRequiredException(OAuthHandler handler) {
        super("");
        this.handler = handler;
    }

    /** 引发本次 401 的 handler；{@link OAuthRuntime} 以它做强制刷新。 */
    public OAuthHandler handler() {
        return handler;
    }
}
