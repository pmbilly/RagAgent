package com.ragagent.mcp.oauth;

import com.ragagent.mcp.protocol.McpContext;

/**
 * token 存储（对照 mcp-go {@code transport.TokenStore}，oauth.go:61-73）。
 *
 * <p>契约（Go 接口文档逐条）：
 * <ul>
 *   <li>尚未授权时抛 {@link OAuthNoTokenException}，<b>不要</b>与其它运维错误混为一谈；</li>
 *   <li>其它错误（库连接失败、IO）原样上抛；</li>
 *   <li>操作前检查取消状态，已取消则抛取消错误。</li>
 * </ul>
 */
public interface OAuthTokenStore {

    /** 读当前 token；未授权抛 {@link OAuthNoTokenException}。 */
    OAuthToken getToken(McpContext ctx);

    /** 保存新签发或刷新后的 token。 */
    void saveToken(McpContext ctx, OAuthToken token);
}
