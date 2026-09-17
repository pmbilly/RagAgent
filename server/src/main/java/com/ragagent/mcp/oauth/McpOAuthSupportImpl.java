package com.ragagent.mcp.oauth;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.common.context.TenantContext;
import com.ragagent.mcp.domain.McpAdvancedConfig;
import com.ragagent.mcp.domain.McpAuthConfig;
import com.ragagent.mcp.domain.McpPrincipal;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.protocol.McpClientConfig;
import com.ragagent.mcp.protocol.McpException;
import com.ragagent.mcp.protocol.McpOAuthRuntime;
import com.ragagent.mcp.protocol.McpOAuthSupport;
import com.ragagent.mcp.protocol.McpProtocol;
import com.ragagent.mcp.protocol.McpTransport;

/**
 * {@link McpOAuthSupport} 的生产实现——<b>这就是"接上协议层注入点"的那一段</b>
 * （对照 Go {@code buildOAuthConfig} + {@code NewOAuth*Client} + {@code newOAuthRuntime}，
 * internal/mcp/client.go:184-249、258-283）。
 *
 * <p>三个动作与 Go 的对应：</p>
 * <ol>
 *   <li>{@link #resolvePrincipal}：归一化 principal，principal 无效时回落到
 *       {@code (web_user, userId)}，再无效就报
 *       {@code "principal context is required to connect to an OAuth MCP service"}
 *       （文案逐字对照 Go）；</li>
 *   <li>{@link #createTransport}：构造带 Bearer 注入的 {@link OAuthTransport}，
 *       token 用 <b>{@link ManagedTokenStore}</b>——不自行刷新；</li>
 *   <li>{@link #createRuntime}：构造 {@link OAuthRuntime} 做协调过的刷新
 *       （跨实例租约）。</li>
 * </ol>
 *
 * <p>两处都<b>不</b>调 {@code McpServiceUrls.validateServiceOutboundUrls}——那已经在
 * {@code McpClientFactory.createClient} 的开头做过一次了（Go 同：client.go 开头 + newHandler）。</p>
 */
public class McpOAuthSupportImpl implements McpOAuthSupport {

    private final OAuthRepository repo;

    public McpOAuthSupportImpl(OAuthRepository repo) {
        this.repo = repo;
    }

    @Override
    public boolean isAvailable() {
        return repo != null;
    }

    /**
     * 对照 Go {@code buildOAuthConfig} 的 principal 解析段（client.go:266-273）。
     *
     * @throws McpException principal 缺失（文案与 Go 逐字一致）
     */
    @Override
    public TenantContext.Principal resolvePrincipal(McpClientConfig config, McpService service) {
        TenantContext.Principal principal = McpPrincipal.normalize(config.principal());
        if (!McpPrincipal.valid(principal)
                && config.userId() != null && !config.userId().isEmpty()) {
            principal = McpPrincipal.normalize(
                    new TenantContext.Principal(McpPrincipal.WEB_USER, config.userId()));
        }
        if (!McpPrincipal.valid(principal)) {
            throw new McpException(
                    "principal context is required to connect to an OAuth MCP service");
        }
        return principal;
    }

    /**
     * 对照 Go {@code client.NewOAuthSSEClient} / {@code NewOAuthStreamableHttpClient}：
     * 构造带 OAuth 的传输。headers 已由协议层注入好（CustomHeaders + 静态鉴权头，
     * OAuth 策略不产生静态头），这里只在其上补 {@code Authorization}。
     */
    @Override
    public McpTransport createTransport(McpClientConfig config, McpService service, String url,
                                        boolean sse, Map<String, String> headers) {
        TenantContext.Principal principal = resolvePrincipal(config, service);
        OAuthHandler handler = new OAuthHandler(buildConfig(config, service, principal, true, url));
        handler.setBaseUrl(url);
        return new OAuthTransport(sse, URI.create(url),
                headers == null ? Map.of() : new LinkedHashMap<>(headers),
                resolveTimeout(service), handler);
    }

    /**
     * 对照 Go {@code newOAuthRuntime}（client.go:240-249）：
     * 与传输共享<b>同一份</b> OAuth 配置（因此也共享同一个 ManagedTokenStore 语义）。
     */
    @Override
    public McpOAuthRuntime createRuntime(McpClientConfig config, McpService service, String url) {
        TenantContext.Principal principal = resolvePrincipal(config, service);
        OAuthConfig cfg = buildConfig(config, service, principal, true, url);
        return new OAuthRuntime(repo, tenantIdOf(config), principal, service.getId(), url, cfg);
    }

    /**
     * 对照 Go {@code buildOAuthConfig} 的配置装配段（client.go:275-283）。
     *
     * @param managed true = 运行期（{@link ManagedTokenStore}，隐藏过期、不自行刷新）；
     *                false = 授权流程用（{@link DbTokenStore}）
     */
    private OAuthConfig buildConfig(McpClientConfig config, McpService service,
                                    TenantContext.Principal principal, boolean managed, String url) {
        long tenantId = tenantIdOf(config);
        McpAuthConfig authConfig = service.getAuthConfig();

        OAuthConfig cfg = new OAuthConfig()
                .scopes(scopesOf(authConfig))
                .tokenStore(managed
                        ? new ManagedTokenStore(repo, tenantId, principal, service.getId())
                        : new DbTokenStore(repo, tenantId, principal, service.getId()))
                .pkceEnabled(true)
                .authServerMetadataUrl(metadataUrlOf(authConfig))
                .httpTimeout(resolveTimeout(service));

        var registered = repo.getClient(tenantId, service.getId());
        if (registered != null) {
            cfg.clientId(registered.getClientId());
            cfg.clientSecret(registered.getClientSecret());
            cfg.redirectUri(registered.getRedirectUri());
        }
        return cfg;
    }

    /** 对照 Go {@code *config.Service.URL} 的入参（本类里由调用方直接给 url）。 */
    static long tenantIdOf(McpClientConfig config) {
        return config.tenantId() == null ? 0L : config.tenantId();
    }

    /**
     * 对照 Go {@code resolveTimeout}（client.go）：{@code AdvancedConfig.timeout > 0}
     * 才覆盖，默认 30s。
     */
    static Duration resolveTimeout(McpService service) {
        McpAdvancedConfig advanced = service == null ? null : service.getAdvancedConfig();
        if (advanced != null && advanced.getTimeout() > 0) {
            return Duration.ofSeconds(advanced.getTimeout());
        }
        return McpProtocol.DEFAULT_TIMEOUT;
    }

    private static List<String> scopesOf(McpAuthConfig ac) {
        return ac == null || ac.getScopes() == null ? List.of() : ac.getScopes();
    }

    private static String metadataUrlOf(McpAuthConfig ac) {
        return ac == null || ac.getAuthServerMetadataUrl() == null ? "" : ac.getAuthServerMetadataUrl();
    }
}
