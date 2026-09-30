package com.ragagent.embed.filter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import com.ragagent.auth.service.TenantService;
import com.ragagent.common.context.TenantContext;
import com.ragagent.embed.EmbedError;
import com.ragagent.embed.EmbedRateLimiter;
import com.ragagent.embed.EmbedTokens;
import com.ragagent.embed.domain.EmbedChannelEntity;
import com.ragagent.embed.service.EmbedChannelService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 对照 Go internal/middleware/embed_auth.go 的 EmbedAuth（publish token 门）。
 *
 * <p>判定顺序与 Go 严格一致（golden 逐条钉过）：</p>
 * <ol>
 *   <li>路径 channel_id 缺失 → 400 "channel_id is required"；</li>
 *   <li>token 只认 {@code Authorization: Embed <token>}（query/Bearer 一律不算）→
 *       401 "embed publish token is required"；</li>
 *   <li>{@code ems_} 前缀 → 解析 Redis 里的 channelID，不匹配/失败 →
 *       401 "invalid embed channel or token"；否则按渠道查（禁用 → 403 "embed channel
 *       is disabled"）；publish token 路径同理（禁用先于 token 比对，golden 钉死）；</li>
 *   <li>Origin 白名单（Origin 头优先，回落 Referer 解析；空清单全拒）→
 *       403 "origin not allowed"；</li>
 *   <li>三段限流（per-IP 每分钟 / 渠道全局每分钟 / 渠道全局每日）→ 429；</li>
 *   <li>租户存在性 → 500 "workspace unavailable"。</li>
 * </ol>
 *
 * <p>通过后写入 TenantContext：tenant=渠道租户、principal=embed_channel:tenant:channel、
 * role=viewer、userId=embed-<channelID>（对照 applyAuthSession 的合成 user），
 * 并把渠道实体挂到 request attribute {@link #CHANNEL_ATTRIBUTE} 供控制器取用。</p>
 */
public class EmbedAuthFilter extends OncePerRequestFilter {

    /** 渠道实体在请求上的键（对照 types.EmbedChannelContextKey）。 */
    public static final String CHANNEL_ATTRIBUTE = "embed.channel";

    private final EmbedChannelService embedService;
    private final TenantService tenantService;
    private final EmbedRateLimiter limiter;

    public EmbedAuthFilter(EmbedChannelService embedService, TenantService tenantService,
                           EmbedRateLimiter limiter) {
        this.embedService = embedService;
        this.tenantService = tenantService;
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Go 只在 /api/v1/embed/:channel_id 组挂这个中间件
        return !request.getRequestURI().startsWith("/api/v1/embed/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String channelId = channelIdFromPath(request.getRequestURI());
        if (channelId.isEmpty()) {
            writePlain(response, 400, "channel_id is required");
            return;
        }

        String token = extractEmbedToken(request);
        if (token.isEmpty()) {
            writePlain(response, 401, "embed publish token is required");
            return;
        }

        EmbedChannelEntity ch;
        try {
            if (EmbedTokens.isSessionToken(token)) {
                String resolvedId = embedService.resolveSessionToken(token);
                if (resolvedId == null || !resolvedId.equals(channelId)) {
                    writePlain(response, 401, "invalid embed channel or token");
                    return;
                }
                ch = embedService.lookupEnabledChannel(channelId);
            } else {
                ch = embedService.lookupForEmbed(channelId, token);
            }
        } catch (EmbedError e) {
            if (e.kind == EmbedError.Kind.CHANNEL_DISABLED) {
                writePlain(response, 403, "embed channel is disabled");
            } else {
                writePlain(response, 401, "invalid embed channel or token");
            }
            return;
        }

        String origin = requestOrigin(request);
        if (!originAllowed(origin, EmbedChannelService.allowedOriginsList(ch))) {
            writePlain(response, 403, "origin not allowed");
            return;
        }

        // 对照三段限流；命中 → 429 "rate limit exceeded" / "daily request limit exceeded"
        String clientIp = clientIp(request);
        String perMinuteKey = channelId + ":" + clientIp;
        if (!limiter.allow(perMinuteKey, EmbedRateLimiter.MINUTE_MILLIS, ch.getRateLimitPerMinute())
                || !limiter.allow(channelId + ":__global", EmbedRateLimiter.MINUTE_MILLIS,
                        EmbedRateLimiter.globalPerMinute(ch.getRateLimitPerMinute()))) {
            writePlain(response, 429, "rate limit exceeded");
            return;
        }
        if (!limiter.allow(channelId, EmbedRateLimiter.DAY_MILLIS, ch.getRateLimitPerDay())) {
            writePlain(response, 429, "daily request limit exceeded");
            return;
        }

        try {
            com.ragagent.auth.domain.Tenant tenant =
                    tenantService.getTenantById(ch.getTenantId() == null ? 0 : ch.getTenantId());
            if (tenant == null) {
                writePlain(response, 500, "workspace unavailable");
                return;
            }
        } catch (RuntimeException e) {
            writePlain(response, 500, "workspace unavailable");
            return;
        }

        String syntheticUser = "embed-" + channelId;
        TenantContext.set(ch.getTenantId(),
                new TenantContext.Principal(TenantContext.PrincipalTypes.EMBED_CHANNEL,
                        ch.getTenantId() + ":" + channelId),
                com.ragagent.common.tenant.TenantRole.VIEWER.value(),
                false, syntheticUser, false);
        request.setAttribute(CHANNEL_ATTRIBUTE, ch);
        try {
            chain.doFilter(request, response);
        } finally {
            // 嵌入访客上下文只属于本请求（对照 Go 的 per-request ctx 值语义）。
            // StorageUrlContext 的 forced-handle 同样在此收口——它的 javadoc 明确
            // "生命周期由设置方负责"，漏清会把 handle 钉死泄漏到线程的下个请求。
            TenantContext.clear();
            com.ragagent.storage.support.StorageUrlContext.clear();
        }
    }

    /**
     * 对照 gin 的 {@code c.Param("channel_id")}：路径第二段。
     * （embed 公开路由的第一层路径段固定是渠道 id。）
     */
    static String channelIdFromPath(String uri) {
        String prefix = "/api/v1/embed/";
        if (!uri.startsWith(prefix)) {
            return "";
        }
        String rest = uri.substring(prefix.length());
        int slash = rest.indexOf('/');
        String id = slash >= 0 ? rest.substring(0, slash) : rest;
        return id.trim();
    }

    /** 对照 extractEmbedToken：只认 "Embed " 前缀。 */
    static String extractEmbedToken(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Embed ")) {
            return auth.substring("Embed ".length()).trim();
        }
        return "";
    }

    /** 对照 requestOrigin：Origin 头优先，回落 Referer 的 scheme://host。 */
    static String requestOrigin(HttpServletRequest request) {
        String o = trim(request.getHeader("Origin"));
        if (!o.isEmpty()) {
            return o;
        }
        String ref = trim(request.getHeader("Referer"));
        if (ref.isEmpty()) {
            return "";
        }
        java.net.URI u;
        try {
            u = java.net.URI.create(ref);
        } catch (IllegalArgumentException e) {
            return "";
        }
        if (u.getScheme() == null || u.getScheme().isEmpty() || u.getHost() == null) {
            return "";
        }
        int port = u.getPort();
        if (port < 0) {
            return u.getScheme() + "://" + u.getHost();
        }
        return u.getScheme() + "://" + u.getHost() + ":" + port;
    }

    /** 对照 originAllowed（空清单全拒；"*" 全放；"*." 后缀匹配；大小写不敏感精确匹配）。 */
    static boolean originAllowed(String origin, List<String> allowed) {
        if (allowed.isEmpty()) {
            return false;
        }
        if (origin.isEmpty()) {
            return false;
        }
        for (String pattern : allowed) {
            String p = pattern == null ? "" : pattern.trim();
            if (p.isEmpty()) {
                continue;
            }
            if ("*".equals(p) || p.equalsIgnoreCase(origin)) {
                return true;
            }
            if (p.startsWith("*.")) {
                String suffix = p.substring(1);
                if (origin.toLowerCase(java.util.Locale.ROOT)
                        .endsWith(suffix.toLowerCase(java.util.Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr() == null ? "" : request.getRemoteAddr();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static void writePlain(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
