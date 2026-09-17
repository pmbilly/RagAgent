package com.ragagent.mcp.protocol;

import com.ragagent.common.context.TenantContext;
import com.ragagent.mcp.domain.McpService;

import java.util.Map;

/**
 * OAuth 装配注入点（对照 Go internal/mcp/client.go 的 {@code buildOAuthConfig}
 * + {@code client.NewOAuthSSEClient}/{@code NewOAuthStreamableHttpClient}
 * + {@code newOAuthRuntime}）。
 *
 * <p><b>留给后续 OAuth agent</b>：实现本接口即接上 OAuth（发现、动态客户端注册、PKCE、
 * 按 principal 的 token 存储与刷新）。MCP 协议层只依赖这三个动作：</p>
 * <ol>
 *   <li>{@link #isAvailable()}——是否装配了 OAuth 仓储（对照 Go 的
 *       {@code if config.OAuthRepo == nil} 报错）；</li>
 *   <li>{@link #resolvePrincipal}——解析/校验发起连接的 principal（对照 Go
 *       {@code buildOAuthConfig} 里 principal 归一化与 {@code Valid()} 判定）；</li>
 *   <li>{@link #createTransport}——构造带 OAuth 的传输（Go：SSE / Streamable 各一个构造函数
 *       → 这是 {@code NewMCPClient} 的"6 分支"里的 OAuth 两支）；</li>
 *   <li>{@link #createRuntime}——token 生命周期。</li>
 * </ol>
 */
public interface McpOAuthSupport {

    /** 是否已装配 OAuth 能力（对照 Go {@code config.OAuthRepo != nil}）。未装配时连 OAuth 服务会报错。 */
    default boolean isAvailable() {
        return true;
    }

    /**
     * 解析本次连接应使用的 principal（对照 Go {@code buildOAuthConfig} 里
     * {@code principal.Normalize()} + {@code Valid()} + {@code UserID} 兼容回退）。
     *
     * @return 归一化后的非空 principal
     * @throws McpException 无法解析出合法 principal 时（Go：
     *         {@code "principal context is required to connect to an OAuth MCP service"}）
     */
    TenantContext.Principal resolvePrincipal(McpClientConfig config, McpService service);

    /**
     * 构造 OAuth 传输（对照 Go {@code client.NewOAuthSSEClient} /
     * {@code client.NewOAuthStreamableHttpClient}）。
     *
     * @param sse true=HTTP+SSE，false=HTTP Streamable
     * @param url 服务 URL
     * @param headers 已按策略注入好的出站头（CustomHeaders + 鉴权头）
     */
    McpTransport createTransport(McpClientConfig config, McpService service, String url,
                                 boolean sse, Map<String, String> headers);

    /** 对照 Go {@code newOAuthRuntime}：token 生命周期钩子。 */
    McpOAuthRuntime createRuntime(McpClientConfig config, McpService service, String url);
}
