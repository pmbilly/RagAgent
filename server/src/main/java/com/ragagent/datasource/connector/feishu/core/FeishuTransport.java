package com.ragagent.datasource.connector.feishu.core;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.TokenResponse;
import com.ragagent.datasource.Connector;
import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.ConnectorHttp;

/**
 * 飞书客户端的传输层：tenant access token 的取用与缓存、HTTP 交换与退避重试、
 * Retry-After 解析。认证状态（token 缓存与过期时刻）由本类独占持有。
 *
 * <p>持有 {@link FeishuClient} 回引以访问 baseUrl / 凭据 / HTTP 客户端；本类不得独立实例化。</p>
 */
final class FeishuTransport {

    private static final Logger log = LoggerFactory.getLogger(FeishuTransport.class);

    private final FeishuClient client;

    // Token 缓存（线程安全；原实现直接放在客户端上，随传输层一起搬）
    private final Object tokenLock = new Object();
    private String tokenCache = "";
    private OffsetDateTime tokenExpAt = OffsetDateTime.MIN;

    FeishuTransport(FeishuClient client) {
        this.client = client;
    }

    /**
     * 对照 Go {@code GetTenantAccessToken}：取（或返回缓存的）tenant access token。
     *
     * <p>飞书 token 有效期 2 小时；这里留 <b>5 分钟安全边际</b>再过期。
     * 与 Go 一样整段加锁——并发同步任务会同时打进来。</p>
     */
    public String getTenantAccessToken() {
        synchronized (tokenLock) {
            if (!tokenCache.isEmpty() && OffsetDateTime.now().isBefore(tokenExpAt)) {
                return tokenCache;
            }

            byte[] payload;
            try {
                payload = FeishuClient.MAPPER.writeValueAsBytes(Map.of("app_id", client.appId, "app_secret", client.appSecret));
            } catch (Exception e) {
                throw new ConnectorException("marshal token request: " + e.getMessage(), e);
            }

            String url = client.baseUrl + "/open-apis/auth/v3/tenant_access_token/internal";
            ConnectorHttp.Response resp = client.httpClient.exchange("POST", url,
                    Map.of("Content-Type", "application/json; charset=utf-8"), payload);

            TokenResponse result;
            try {
                result = FeishuClient.MAPPER.readValue(resp.bodyAsString(), TokenResponse.class);
            } catch (Exception e) {
                throw new ConnectorException("decode token response: " + e.getMessage(), e);
            }
            if (result == null) {
                throw new ConnectorException("decode token response: empty body");
            }
            if (result.code() != 0) {
                throw new ConnectorException(
                        "feishu auth error: code=" + result.code() + " msg=" + result.msg());
            }

            String token = result.tenantAccessToken() == null ? "" : result.tenantAccessToken();
            tokenCache = token;
            Duration ttl = Duration.ofSeconds(result.expire());
            if (ttl.compareTo(Duration.ofMinutes(5)) > 0) {
                ttl = ttl.minusMinutes(5);
            }
            tokenExpAt = OffsetDateTime.now().plus(ttl);

            int prefixLen = Math.min(8, token.length());
            int suffixLen = Math.min(4, token.length());
            log.info("[Feishu] got tenant_access_token: {}...{} expire={}s",
                    token.substring(0, prefixLen), token.substring(token.length() - suffixLen),
                    result.expire());

            return tokenCache;
        }
    }

    /** 对照 Go {@code Ping}：拿一次 token 即算验活。 */
    public void ping() {
        getTenantAccessToken();
    }

    /**
     * 对照 Go {@code DoRequest}：带鉴权的 API 调用 + JSON 解码 + 瞬时失败重试。
     *
     * @param method     {@code "GET"} / {@code "POST"} …
     * @param path       以 {@code /open-apis/...} 开头的路径（client.baseUrl 由客户端补上）
     * @param body       请求体对象；{@code null} 表示无体
     * @param resultType 解码目标；{@code null} 表示不关心响应体（对照 Go 的 {@code result == nil}）
     * @return 解码结果；{@code resultType == null} 时返回 {@code null}
     */
    public <T> T doRequest(String method, String path, Object body, Class<T> resultType) {
        String token = getTenantAccessToken();

        byte[] bodyBytes = null;
        if (body != null) {
            try {
                bodyBytes = FeishuClient.MAPPER.writeValueAsBytes(body);
            } catch (Exception e) {
                throw new ConnectorException("marshal request body: " + e.getMessage(), e);
            }
        }

        String url = client.baseUrl + path;
        RuntimeException lastErr = null;

        for (int attempt = 0; attempt <= FeishuClient.MAX_RETRIES; attempt++) {
            if (attempt == 0) {
                log.info("[Feishu] {} {}", method, path);
            } else {
                log.info("[Feishu] {} {} (retry {}/{})", method, path, attempt, FeishuClient.MAX_RETRIES);
            }

            ConnectorHttp.Response resp;
            try {
                resp = client.httpClient.exchange(method, url, Map.of(
                        "Content-Type", "application/json; charset=utf-8",
                        "Authorization", "Bearer " + token), bodyBytes);
            } catch (RuntimeException e) {
                // 对照 Go：c.httpClient.Do(req) 失败 → 退避重试
                lastErr = e instanceof ConnectorException ce
                        ? ce : new ConnectorException("execute request: " + e.getMessage(), e);
                if (attempt < FeishuClient.MAX_RETRIES) {
                    Connector.sleep(backoffAt(attempt).toMillis());
                    continue;
                }
                throw lastErr;
            }

            String respBody = resp.bodyAsString();
            log.info("[Feishu] {} {} → status={} bodyLen={} body={}",
                    method, path, resp.status(), resp.body() == null ? 0 : resp.body().length,
                    FeishuSupport.truncate(respBody, 1000));

            if (resp.status() == 429) {
                Duration wait = parseRetryAfter(resp.header("Retry-After"), backoffAt(attempt));
                lastErr = new ConnectorException(
                        "feishu rate limited: status=429 body=" + FeishuSupport.truncate(respBody, 500));
                if (attempt < FeishuClient.MAX_RETRIES) {
                    Connector.sleep(wait.toMillis());
                    continue;
                }
                throw lastErr;
            }

            if (resp.status() >= 500 && resp.status() < 600) {
                lastErr = new ConnectorException("feishu server error: status=" + resp.status()
                        + " body=" + FeishuSupport.truncate(respBody, 500));
                if (attempt < FeishuClient.MAX_5XX_RETRIES) {
                    Connector.sleep(FeishuClient.retry5xxDelay.toMillis());
                    continue;
                }
                throw lastErr;
            }

            if (resp.status() != 200) {
                // 对照 Go：这一支用**完整** body（不截断）
                throw new ConnectorException(
                        "feishu api error: status=" + resp.status() + " body=" + respBody);
            }

            if (resultType == null) {
                return null;
            }
            try {
                return FeishuClient.MAPPER.readValue(respBody, resultType);
            } catch (Exception e) {
                throw new ConnectorException("decode response: " + e.getMessage(), e);
            }
        }

        // 不可达：循环内每个分支要么 return 要么 throw（保留以对齐 Go 的收尾 return）
        throw lastErr != null ? lastErr : new ConnectorException("request failed");
    }

    /**
     * 对照 Go {@code parseRetryAfter}：把 {@code Retry-After}（秒）解释成等待时长，
     * {@code 0}/负数强制成 100ms 的短延迟，缺失或不可解析时回落。
     *
     * <p>Go 的测试直接调这个包级函数，所以 Java 侧也保持静态可调。</p>
     */
    public static Duration parseRetryAfter(String header, Duration fallback) {
        if (header == null || header.isEmpty()) {
            return fallback;
        }
        double secs;
        try {
            secs = Double.parseDouble(header.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
        if (secs <= 0) {
            return Duration.ofMillis(100);
        }
        return Duration.ofNanos((long) (secs * 1_000_000_000L));
    }

    static Duration backoffAt(int attempt) {
        List<Duration> backoff = FeishuClient.retryBackoff;
        int idx = Math.min(attempt, backoff.size() - 1);
        return backoff.get(idx);
    }
}
