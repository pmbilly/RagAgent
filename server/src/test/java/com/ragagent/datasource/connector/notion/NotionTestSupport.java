package com.ragagent.datasource.connector.notion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.ConnectorHttp;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.DataSourceConstants;

/**
 * Notion 测试的公共夹具。
 *
 * <h2>SSRF 白名单是进程级静态状态（必须还原）</h2>
 * <p>对照 Go 测试的 {@code t.Setenv("SSRF_WHITELIST", "127.0.0.1,::1,localhost")}
 * + {@code ResetSSRFWhitelistForTest()}。Java 进程内改不了 env，
 * 按 {@code FakeYuque} / {@code FeishuTestSupport} 的既有惯例：
 * {@code @BeforeAll} 调 {@link #allowLoopback()}，{@code @AfterAll} 调
 * {@link #restoreSsrf()}。</p>
 *
 * <h2>限流与退避必须被替换掉，否则测试变成慢测</h2>
 * <p>Go 的默认限流是 3 req/s，Go 自己那套连接器测试因此实测要跑 1.7 秒；
 * 重试退避更是 1s/2s/4s。Java 侧在测试里一律注入
 * {@link NotionClient.RateLimiter#unlimited()} + 零退避 + 记账 Sleeper，
 * 于是没有一条用例真的在等墙钟（约定 §7.5 第 7 条）。</p>
 */
final class NotionTestSupport {

    /** 放行本机回环的原始白名单（对照 Go 测试的 {@code SSRF_WHITELIST}）。 */
    static final String LOOPBACK_WHITELIST = "127.0.0.1,::1,localhost";

    private NotionTestSupport() {
    }

    static void allowLoopback() {
        SsrfGuard guard = new SsrfGuard();
        guard.reloadWhitelist(LOOPBACK_WHITELIST);
        ConnectorHttp.setSsrfGuard(guard);
    }

    static void restoreSsrf() {
        ConnectorHttp.setSsrfGuard(new SsrfGuard());
        ConnectorHttp.ssrfGuard().reloadWhitelist(envWhitelistRaw());
    }

    private static String envWhitelistRaw() {
        String primary = System.getenv("SSRF_WHITELIST");
        String extra = System.getenv("SSRF_WHITELIST_EXTRA");
        primary = primary == null ? "" : primary.trim();
        extra = extra == null ? "" : extra.trim();
        if (primary.isEmpty()) {
            return extra;
        }
        if (extra.isEmpty()) {
            return primary;
        }
        return primary + "," + extra;
    }

    // ── 配置 ──────────────────────────────────────────────────────────────

    /** 对照 Go {@code makeNotionConfig}。 */
    static DataSourceConfig config(String baseUrl, List<String> resourceIds) {
        return config("tok", baseUrl, resourceIds);
    }

    static DataSourceConfig config(String apiKey, String baseUrl, List<String> resourceIds) {
        DataSourceConfig c = new DataSourceConfig();
        c.setType(DataSourceConstants.CONNECTOR_TYPE_NOTION);
        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("api_key", apiKey);
        c.setCredentials(credentials);
        c.setResourceIds(resourceIds == null ? new ArrayList<>() : new ArrayList<>(resourceIds));
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("base_url", baseUrl);
        c.setSettings(settings);
        return c;
    }

    // ── 连接器 / 客户端 ───────────────────────────────────────────────────

    /** 记录休眠时长的 Sleeper（断言退避次数与毫秒数用）。 */
    static final class RecordingSleeper implements NotionClient.Sleeper {
        final List<Long> slept = new ArrayList<>();

        @Override
        public void sleep(long millis) {
            slept.add(millis);
        }
    }

    /** 零退避 + 无限流 + 不真睡的 Sleeper。 */
    static NotionConnector fastConnector() {
        return new NotionConnector((token, baseUrl) -> NotionClient.forTesting(
                token, baseUrl, NotionClient.RateLimiter.unlimited(),
                NotionClient.Backoff.none(), millis -> { }));
    }

    static NotionConnector fastConnector(RecordingSleeper sleeper) {
        return new NotionConnector((token, baseUrl) -> NotionClient.forTesting(
                token, baseUrl, NotionClient.RateLimiter.unlimited(),
                NotionClient.Backoff.none(), sleeper));
    }

    static NotionClient fastClient(String baseUrl) {
        return fastClient("tok", baseUrl, null);
    }

    static NotionClient fastClient(String token, String baseUrl, RecordingSleeper sleeper) {
        return fastClient(token, baseUrl, sleeper, NotionClient.Backoff.none());
    }

    /**
     * 退避可指定的版本：想断言"退避序列是 1s/2s/4s"时传
     * {@link NotionClient.Backoff#exponentialSeconds()}——休眠仍被记账、
     * 不真等，所以用例零耗时。
     */
    static NotionClient fastClient(String token, String baseUrl, RecordingSleeper sleeper,
                                   NotionClient.Backoff backoff) {
        NotionClient.Sleeper s = sleeper == null ? millis -> { } : sleeper;
        return NotionClient.forTesting(token, baseUrl,
                NotionClient.RateLimiter.unlimited(), backoff, s);
    }

    // ── 小工具 ────────────────────────────────────────────────────────────

    static JsonNode json(String raw) {
        try {
            return NotionJson.MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new ConnectorException("bad test json: " + e.getMessage(), e);
        }
    }

    static String contentOf(com.ragagent.datasource.domain.FetchedItem item) {
        return item.getContent() == null
                ? ""
                : new String(item.getContent(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
