package com.ragagent.datasource.connector.rss;

import com.ragagent.common.web.JsonMappers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.domain.DataSourceConfig;

/**
 * RSS 连接器的私有配置（对照 Go {@code internal/datasource/connector/rss/types.go}
 * 的 {@code Config} + {@code parseConfig} + {@code feedURLsFromSettings}
 * + {@code feedURLList} + {@code parseHeaders}）。
 *
 * <h2>两处存放位置，以及"谁覆盖谁"</h2>
 * <p>Go 的注释说得很清楚：{@code feed_urls} 是<b>非密钥</b>配置，住在
 * {@code DataSourceConfig.Settings}（UI 上改它不需要换凭据）；{@code auth_headers}
 * 可能带密钥，住在 {@code Credentials}（落库前会被 AES 加密）。
 * 为了兼容老数据行，{@code Credentials} 里可能也还有 {@code feed_urls}
 * ——但 <b>settings 里的值优先覆盖它</b>（且只在非空时覆盖）。</p>
 *
 * <h2>为什么反序列化要用"不许标量强转"的 mapper</h2>
 * <p>Go 的 {@code json.Unmarshal} 把一个 JSON 数字塞进 {@code FeedURLs string} 字段时
 * <b>会报错</b>（{@code cannot unmarshal number into Go struct field}）。
 * Jackson 默认会把数字<em>强转</em>成字符串，于是 {@code {"feed_urls": 12}} 在 Java 侧
 * 会被静默接受成 {@code "12"}、在 Go 侧报 {@code parse rss credentials: ...}。
 * 关掉标量强转后两边的行为一致（错误文案仍然是两边各自的 JSON 库原文，见约定 §9 差异 #2 那一族）。</p>
 */
final class RssConfig {

    private static final ObjectMapper MAPPER = buildMapper();

    private static ObjectMapper buildMapper() {
        ObjectMapper mapper = JsonMappers.lenient()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                // Go 的 struct tag 是 json:"feed_urls" / json:"auth_headers"（蛇形），
                // Java 字段是驼峰 —— 用命名策略让两边对上（别改成给字段加 @JsonProperty，
                // 那会让字段与 getter 分裂成两个属性）。
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        // 对照 Go：数字塞进 string 字段要报错，不能静默强转成 "12"。
        mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        return mapper;
    }

    /** {@code feed_urls}：换行或逗号分隔的 feed 地址列表。 */
    private String feedUrls = "";

    /**
     * {@code auth_headers}：换行分隔的 {@code "Name: Value"} 自定义请求头，
     * <b>只作用于 feed 抓取</b>，绝不发给第三方文章页。
     */
    private String authHeaders;

    RssConfig() {
    }

    RssConfig(String feedUrls, String authHeaders) {
        this.feedUrls = feedUrls == null ? "" : feedUrls;
        this.authHeaders = authHeaders;
    }

    String getFeedUrls() {
        return feedUrls;
    }

    public void setFeedUrls(String v) {
        feedUrls = v == null ? "" : v;
    }

    String getAuthHeaders() {
        return authHeaders;
    }

    public void setAuthHeaders(String v) {
        authHeaders = v;
    }

    // ── Go parseConfig ─────────────────────────────────────────────────────

    /**
     * 对照 Go {@code parseConfig}：从 {@link DataSourceConfig} 解出 RSS 私有配置。
     *
     * <p>四条分支逐条照抄：</p>
     * <ol>
     *   <li>{@code config == nil} → {@link ConnectorException.InvalidConfig}{@code ("config is nil")}
     *       （文本 = {@code "invalid configuration: config is nil"}）；</li>
     *   <li>Credentials 解不出 {@link RssConfig} → 普通 {@code ConnectorException}
     *       （Go 是 {@code "parse rss credentials: %w"}，<b>不是</b>哨兵包装，
     *       所以调用方认不出类型——照抄）；</li>
     *   <li>Settings 里的 {@code feed_urls} 非空 → 覆盖 Credentials 里的；</li>
     *   <li>最终列表为空 → {@link ConnectorException.InvalidCredentials}{@code ("feed_urls is required")}。</li>
     * </ol>
     */
    static RssConfig parse(DataSourceConfig config) {
        if (config == null) {
            throw new ConnectorException.InvalidConfig("config is nil");
        }
        RssConfig cfg;
        try {
            JsonNode credentials = MAPPER.valueToTree(
                    config.getCredentials() == null ? Map.of() : config.getCredentials());
            cfg = MAPPER.treeToValue(credentials, RssConfig.class);
        } catch (Exception e) {
            throw new ConnectorException("parse rss credentials: " + e.getMessage(), e);
        }
        if (cfg == null) {
            cfg = new RssConfig();
        }
        String fromSettings = feedUrlsFromSettings(config.getSettings());
        if (!fromSettings.isEmpty()) {
            cfg.feedUrls = fromSettings;
        }
        if (cfg.feedUrlList().isEmpty()) {
            throw new ConnectorException.InvalidCredentials("feed_urls is required");
        }
        return cfg;
    }

    /**
     * 对照 Go {@code feedURLsFromSettings}：从 settings 里抠出 {@code feed_urls}。
     *
     * <p>非字符串（数字 / 布尔 / 对象）一律当成"没配"，<b>不报错</b>——
     * 这是 Go 的类型断言失败分支，与 {@link #parse} 里 Credentials 的严格反序列化不同。</p>
     */
    static String feedUrlsFromSettings(Map<String, Object> settings) {
        if (settings == null || settings.isEmpty()) {
            return "";
        }
        Object raw = settings.get("feed_urls");
        if (!(raw instanceof String s)) {
            return "";
        }
        return RssUtil.goTrim(s);
    }

    /**
     * 对照 Go {@code Config.feedURLList}：按换行 / 回车 / 逗号切分，逐项 TrimSpace，
     * 去重（<b>保留首次出现顺序</b>），丢掉空项。
     *
     * <p>Go 用的是 {@code strings.FieldsFunc}（连续分隔符只算一次、首尾的分隔符直接忽略），
     * 所以 {@code "\n\n"} 得到的是空切片而不是两个空串。照抄。</p>
     */
    List<String> feedUrlList() {
        List<String> out = new ArrayList<>();
        if (feedUrls == null || feedUrls.isEmpty()) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        int i = 0;
        int n = feedUrls.length();
        while (i < n) {
            char c = feedUrls.charAt(i);
            if (c == '\n' || c == '\r' || c == ',') {
                i++;
                continue;
            }
            int start = i;
            while (i < n) {
                char d = feedUrls.charAt(i);
                if (d == '\n' || d == '\r' || d == ',') {
                    break;
                }
                i++;
            }
            String u = RssUtil.goTrim(feedUrls.substring(start, i));
            if (u.isEmpty()) {
                continue;
            }
            if (seen.add(u)) {
                out.add(u);
            }
        }
        return out;
    }

    /**
     * 对照 Go {@code Config.parseHeaders}：把换行分隔的 {@code "Name: Value"} 块解析成 map。
     *
     * <p>逐条照抄 Go 的跳过条件：空行跳过；<b>找不到冒号、或冒号在第 0 位</b>跳过
     * （{@code idx <= 0}）；name TrimSpace 后为空跳过（其实已被 {@code idx <= 0} 覆盖）。
     * 同名后者覆盖前者。最终 map 为空时返回 {@code null}（对照 Go 的 nil map）。</p>
     */
    Map<String, String> parseHeaders() {
        if (authHeaders == null || RssUtil.goTrim(authHeaders).isEmpty()) {
            return null;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (String rawLine : authHeaders.split("\n", -1)) {
            String line = RssUtil.goTrim(rawLine);
            if (line.isEmpty()) {
                continue;
            }
            int idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String name = RssUtil.goTrim(line.substring(0, idx));
            String value = RssUtil.goTrim(line.substring(idx + 1));
            if (name.isEmpty()) {
                continue;
            }
            headers.put(name, value);
        }
        if (headers.isEmpty()) {
            return null;
        }
        return headers;
    }
}
