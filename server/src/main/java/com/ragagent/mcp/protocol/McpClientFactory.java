package com.ragagent.mcp.protocol;

import com.ragagent.mcp.domain.McpAuthConfig;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.domain.McpTransportType;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * MCP 客户端工厂。
 *
 * <p><b>六分支 = 3 种传输 × {OAuth, 非 OAuth}</b>（stdio 与未知类型是拒绝分支）：</p>
 * <ol>
 *   <li>{@code sse} × 非 OAuth → {@link SseTransport}</li>
 *   <li>{@code sse} × OAuth → {@link McpOAuthSupport#createTransport}</li>
 *   <li>{@code http-streamable} × 非 OAuth → {@link StreamableHttpTransport}</li>
 *   <li>{@code http-streamable} × OAuth → {@link McpOAuthSupport#createTransport}</li>
 *   <li>{@code stdio} → 硬拒绝（命令注入风险）</li>
 *   <li>未知/空类型 → {@code ErrUnsupportedTransport}</li>
 * </ol>
 *
 * <p><b>构造前必做</b>：{@link McpServiceUrls#validateServiceOutboundUrls}——陈旧行/导入行
 * 可能绕过当前 SSRF 策略，构造点必须再查一次。</p>
 */
public final class McpClientFactory {

    /** stdio 拒绝文案（契约固定，manager 里也复用同一条）。 */
    static final String STDIO_DISABLED_MESSAGE =
            "stdio transport is disabled for security reasons; "
                    + "please use SSE or HTTP Streamable transport instead";

    private McpClientFactory() {
    }

    /**
     * 构造客户端。
     *
     * @throws McpException 配置缺失 / SSRF 校验失败 / stdio / 未知传输 / OAuth 未装配
     */
    public static McpClient createClient(McpClientConfig config) {
        if (config == null || config.service() == null) {
            throw new McpException("MCP client config and service are required");
        }
        McpService service = config.service();
        McpServiceUrls.validateServiceOutboundUrls(service);

        Duration timeout = DefaultMcpClient.resolveTimeout(service);
        Map<String, String> headers = McpAuthHeaders.buildHeaders(service.getHeaders(), service.getAuthConfig());

        boolean useOAuth = isOAuth(service);
        if (useOAuth) {
            // OAuth 装配检查发生在传输分支之前
            McpOAuthSupport support = config.oauthSupport();
            if (support == null || !support.isAvailable()) {
                throw new McpException("OAuth repository is required for OAuth MCP services");
            }
            support.resolvePrincipal(config, service);
        }

        McpTransportType transportType = McpTransportType.fromValue(service.getTransportType());
        McpTransport transport;
        if (transportType == null) {
            throw new McpException(McpErrorCode.UNSUPPORTED_TRANSPORT);
        }
        switch (transportType) {
            case SSE -> {
                String url = requireUrl(service, "SSE");
                transport = useOAuth
                        ? config.oauthSupport().createTransport(config, service, url, true, headers)
                        : new SseTransport(URI.create(url), headers, timeout);
            }
            case HTTP_STREAMABLE -> {
                String url = requireUrl(service, "HTTP Streamable");
                transport = useOAuth
                        ? config.oauthSupport().createTransport(config, service, url, false, headers)
                        : new StreamableHttpTransport(URI.create(url), headers, timeout);
            }
            case STDIO -> throw new McpException(STDIO_DISABLED_MESSAGE);
            default -> throw new McpException(McpErrorCode.UNSUPPORTED_TRANSPORT);
        }

        McpOAuthRuntime runtime = useOAuth
                ? config.oauthSupport().createRuntime(config, service, service.getUrl())
                : null;
        DefaultMcpClient client = new DefaultMcpClient(service, transport, runtime);
        transport.setConnectionLostHandler(err -> {
            // 连接丢失即断开，让下次 GetOrCreateClient 重建。
            client.disconnect();
            org.slf4j.LoggerFactory.getLogger(McpClientFactory.class)
                    .warn("MCP server connection has been lost, URL:{}, error:{}",
                            McpLog.sanitize(service.getUrl()), String.valueOf(err));
        });
        return client;
    }

    /** 传输分支内校验 URL 必填（错误文案是契约）。 */
    private static String requireUrl(McpService service, String transportLabel) {
        String url = service.getUrl();
        if (url == null || url.isEmpty()) {
            throw new McpException("URL is required for " + transportLabel + " transport");
        }
        return url;
    }

    /** OAuth 策略判定（authConfig 缺失视为非 OAuth）。 */
    static boolean isOAuth(McpService service) {
        McpAuthConfig authConfig = service.getAuthConfig();
        return authConfig != null && authConfig.isOAuth();
    }
}
