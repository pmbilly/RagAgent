package com.ragagent.mcp.protocol;

import com.ragagent.common.context.TenantContext;
import com.ragagent.mcp.domain.McpService;

import java.util.Map;

/**
 * OAuth 装配注入点。
 *
 * <p><b>留给 OAuth 模块</b>：实现本接口即接上 OAuth（发现、动态客户端注册、PKCE、
 * 按 principal 的 token 存储与刷新）。MCP 协议层只依赖这几个动作：</p>
 * <ol>
 *   <li>{@link #isAvailable()}——是否装配了 OAuth 仓储（未装配时连 OAuth 服务会报错）；</li>
 *   <li>{@link #resolvePrincipal}——解析/校验发起连接的 principal
 *       （归一化 + 有效性判定 + 兼容回退）；</li>
 *   <li>{@link #createTransport}——构造带 OAuth 的传输（SSE / Streamable 各一支）；</li>
 *   <li>{@link #createRuntime}——token 生命周期。</li>
 * </ol>
 */
public interface McpOAuthSupport {

    /** 是否已装配 OAuth 能力。未装配时连 OAuth 服务会报错。 */
    default boolean isAvailable() {
        return true;
    }

    /**
     * 解析本次连接应使用的 principal（归一化 + 有效性判定 + 兼容回退）。
     *
     * @return 归一化后的非空 principal
     * @throws McpException 无法解析出合法 principal 时（固定文案：
     *         {@code "principal context is required to connect to an OAuth MCP service"}）
     */
    TenantContext.Principal resolvePrincipal(McpClientConfig config, McpService service);

    /**
     * 构造 OAuth 传输。
     *
     * @param sse true=HTTP+SSE，false=HTTP Streamable
     * @param url 服务 URL
     * @param headers 已按策略注入好的出站头（CustomHeaders + 鉴权头）
     */
    McpTransport createTransport(McpClientConfig config, McpService service, String url,
                                 boolean sse, Map<String, String> headers);

    /** 构造 token 生命周期钩子。 */
    McpOAuthRuntime createRuntime(McpClientConfig config, McpService service, String url);
}
