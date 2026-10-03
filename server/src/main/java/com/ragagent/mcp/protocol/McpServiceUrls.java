package com.ragagent.mcp.protocol;

import com.ragagent.common.security.SsrfGuard;
import com.ragagent.mcp.domain.McpAuthConfig;
import com.ragagent.mcp.domain.McpService;

/**
 * MCP 出站 URL 的 SSRF 校验。
 *
 * <p><b>为什么两个 URL 都要查</b>：MCP 传输会连 {@code service.url}，而 OAuth 发现流程还会连
 * {@code AuthConfig.AuthServerMetadataURL}——后者同样是租户可填的自由文本，只查前者等于开了
 * 一个绕过口。</p>
 *
 * <p><b>为什么持久化边界和客户端构造前都要调</b>：陈旧的行、导入的行、
 * 或者策略收紧之前存下来的行，都可能绕过<b>当前</b>的 SSRF 策略。持久化时校验保证"写不进去"，
 * 构造客户端前再校验一次保证"读出来也跑不掉"。</p>
 */
public final class McpServiceUrls {

    private McpServiceUrls() {
    }

    /**
     * 注入 Spring 管理的 {@link SsrfGuard}（含运行时由系统设置推送的 DB 白名单，
     * 对照 {@code LlmTransport.setSsrfGuard}）。转交给 {@link McpHttp}——出站 HTTP 与
     * 服务配置校验必须用<b>同一个</b>策略实例，否则会出现"存得进去、连不出去"的错配。
     */
    public static void setSsrfGuard(SsrfGuard guard) {
        McpHttp.setSsrfGuard(guard);
    }

    /**
     * 校验 service.url 与 AuthConfig.authServerMetadataUrl 两个出站 URL。
     *
     * <p>底层保留 {@link SsrfGuard.SsrfException} 作为 cause，
     * 判定方式为 {@code getCause() instanceof SsrfException}。</p>
     *
     * @throws McpException 校验失败（错误文案是契约，固定不变）
     */
    public static void validateServiceOutboundUrls(McpService service) {
        if (service == null) {
            throw new McpException("MCP service is required");
        }
        String serviceUrl = trimToEmpty(service.getUrl());
        if (!serviceUrl.isEmpty()) {
            try {
                McpHttp.validateUrlForSsrf(serviceUrl);
            } catch (SsrfGuard.SsrfException e) {
                throw new McpException("MCP service URL failed SSRF validation: " + e.getMessage(), e);
            }
        }
        McpAuthConfig authConfig = service.getAuthConfig();
        if (authConfig != null) {
            String metadataUrl = trimToEmpty(authConfig.getAuthServerMetadataUrl());
            if (!metadataUrl.isEmpty()) {
                try {
                    McpHttp.validateUrlForSsrf(metadataUrl);
                } catch (SsrfGuard.SsrfException e) {
                    throw new McpException("MCP OAuth metadata URL failed SSRF validation: " + e.getMessage(), e);
                }
            }
        }
    }

    /** 去除首尾空白。 */
    static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
