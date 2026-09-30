package com.ragagent.datasource.connector.yuque;

import com.ragagent.common.web.JsonMappers;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.ConnectorHttp;
import com.ragagent.datasource.domain.DataSourceConfig;

/**
 * 语雀专属配置（对照 Go {@code yuque.Config} + {@code parseYuqueConfig}，
 * yuque/types.go L32-84）。
 *
 * <h2>{@code GetBaseURL()} 在 Go 里是方法</h2>
 * <p>Java 侧刻意不带 {@code get} 前缀（叫 {@code baseURL()}）并显式
 * {@code @JsonIgnore}——否则 Jackson 会凭空多吐一个 {@code baseURL} 键
 * （约定 §7.5 第 2 条，本项目复发率最高的那类错误）。</p>
 *
 * <h2>企业/私有部署</h2>
 * <p>{@code base_url} 空 → {@code https://www.yuque.com}；缺 scheme 补
 * {@code https://}；去尾斜杠。</p>
 *
 * <h2>内部 API 形状，不是契约</h2>
 * <p>只作为 credentials 的解析目标，从不作响应体。</p>
 */
public class YuqueConfig {

    /** 对照 Go {@code yuque.DefaultBaseURL}。 */
    public static final String DEFAULT_BASE_URL = "https://www.yuque.com";

    /**
     * 与 Go 的 {@code json.Unmarshal} 对齐：忽略未知属性。
     * Go 用 marshal/unmarshal 往返解析而不是逐字段类型断言，正是因为
     * {@code base_url} 这类字段可选。
     */
    private static final ObjectMapper MAPPER = JsonMappers.lenient()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 对照 Go {@code api_token}：语雀设置页里的个人令牌，随 {@code X-Auth-Token} 头发送。 */
    @JsonProperty("api_token")
    private String apiToken = "";

    /** 部署基地址；空 → {@link #DEFAULT_BASE_URL}。omitempty → 空时整键消失。 */
    @JsonProperty("base_url")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String baseUrl = "";

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String v) {
        apiToken = v == null ? "" : v;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String v) {
        baseUrl = v == null ? "" : v;
    }

    /**
     * 对照 Go {@code (*Config).GetBaseURL}：空 → 默认、缺 scheme 补
     * {@code https://}、去尾斜杠。
     *
     * <p>{@code @JsonIgnore} 必须有——Go 里它是方法，不参与 JSON。</p>
     */
    @JsonIgnore
    public String baseURL() {
        String url = baseUrl == null ? "" : baseUrl.trim();
        if (url.isEmpty()) {
            return DEFAULT_BASE_URL;
        }
        if (!url.contains("://")) {
            url = "https://" + url;
        }
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }

    /**
     * 对照 Go {@code parseYuqueConfig}：解析并校验语雀配置。
     *
     * <p>顺序逐条照抄：nil config → {@link ConnectorException.InvalidConfig}；
     * 反序列化失败 → {@code "parse yuque credentials: ..."}；{@code api_token}
     * 空白 → {@link ConnectorException.InvalidCredentials}；最后过 SSRF 策略。</p>
     *
     * <p><b>最后一步会真的解析 DNS</b>（除非命中白名单）。测试必须把
     * {@code base_url} 指向被放行的 stub server，不能留空回落到
     * {@code https://www.yuque.com}（约定 §7.5 第 7 条）。</p>
     */
    public static YuqueConfig parse(DataSourceConfig config) {
        if (config == null) {
            throw new ConnectorException.InvalidConfig("config is nil");
        }
        YuqueConfig cfg;
        try {
            Map<String, Object> credentials = config.getCredentials();
            cfg = credentials == null
                    ? new YuqueConfig()
                    : MAPPER.convertValue(credentials, YuqueConfig.class);
            if (cfg == null) {
                cfg = new YuqueConfig();
            }
        } catch (RuntimeException e) {
            throw new ConnectorException("parse yuque credentials: " + e.getMessage(), e);
        }
        if (isGoBlank(cfg.apiToken)) {
            throw new ConnectorException.InvalidCredentials("api_token is required");
        }
        ConnectorHttp.validateConnectorBaseUrl(cfg.baseURL());
        return cfg;
    }

    /** 对照 Go 的 {@code strings.TrimSpace(x) == ""}（含 U+00A0 / U+3000）。 */
    private static boolean isGoBlank(String s) {
        if (s == null) {
            return true;
        }
        return s.codePoints().allMatch(cp -> Character.isWhitespace(cp)
                || cp == 0x00A0 || cp == 0x3000);
    }
}
