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
 * 语雀专属配置。
 *
 * <h2>派生访问器不带 {@code get} 前缀</h2>
 * <p>{@code baseURL()} 刻意不带 {@code get} 前缀并显式
 * {@code @JsonIgnore}——否则 Jackson 会凭空多吐一个 {@code baseURL} 键
 * （这是本项目复发率最高的一类错误）。</p>
 *
 * <h2>企业/私有部署</h2>
 * <p>{@code base_url} 空 → {@code https://www.yuque.com}；缺 scheme 补
 * {@code https://}；去尾斜杠。</p>
 *
 * <h2>内部 API 形状，不是契约</h2>
 * <p>只作为 credentials 的解析目标，从不作响应体。</p>
 */
public class YuqueConfig {

    /** 默认部署地址。 */
    public static final String DEFAULT_BASE_URL = "https://www.yuque.com";

    /**
     * 忽略未知属性（credentials 里的键只多不少，{@code base_url} 这类字段可选）。
     */
    private static final ObjectMapper MAPPER = JsonMappers.lenient()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 语雀设置页里的个人令牌，随 {@code X-Auth-Token} 头发送。 */
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
     * 归一化后的基地址：空 → 默认、缺 scheme 补
     * {@code https://}、去尾斜杠。
     *
     * <p>{@code @JsonIgnore} 必须有——否则会被 Jackson 当成属性多写一个键。</p>
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
     * 解析并校验语雀配置。
     *
     * <p>顺序：nil config → {@link ConnectorException.InvalidConfig}；
     * 反序列化失败 → {@code "parse yuque credentials: ..."}；{@code api_token}
     * 空白 → {@link ConnectorException.InvalidCredentials}；最后过 SSRF 策略。</p>
     *
     * <p><b>最后一步会真的解析 DNS</b>（除非命中白名单）。测试必须把
     * {@code base_url} 指向被放行的 stub server，不能留空回落到
     * {@code https://www.yuque.com}。</p>
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

    /** 空白判定（含 U+00A0 / U+3000，比 {@code isBlank} 更严）。 */
    private static boolean isGoBlank(String s) {
        if (s == null) {
            return true;
        }
        return s.codePoints().allMatch(cp -> Character.isWhitespace(cp)
                || cp == 0x00A0 || cp == 0x3000);
    }
}
