package com.ragagent.datasource.connector.ima;

import com.ragagent.common.web.JsonMappers;
import java.util.Locale;
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
 * IMA 专属配置。
 *
 * <p>两个凭据都是不透明字符串，由
 * {@link DataSourceConfig#toJSON()} 在落库前整体加密。</p>
 *
 * <h2>派生访问器不带 {@code get} 前缀</h2>
 * <p>{@code baseURL()} 刻意<b>不带</b> {@code get} 前缀，
 * Jackson 就不会把它当属性写进 JSON——否则 credentials map 里会凭空多出一个
 * {@code baseURL} 键、落进 {@code data_sources.config} 的密文里。</p>
 *
 * <h2>{@code base_url} 为空时不出现在序列化结果里</h2>
 * <p>{@code NON_EMPTY}：未配置时该键不出现（判空串）。</p>
 *
 * <h2>内部 API 形状，不是契约</h2>
 * <p>本类型只作为 credentials 的解析目标，从不作响应体、也不独立落 jsonb
 * （它的内容属于 {@code data_sources.config} 那个已经加密的 blob）。</p>
 */
public class ImaConfig {

    /**
     * 忽略未知属性。
     *
     * <p>凭据 map 里的键只多不少，解析必须容忍未知属性。</p>
     */
    private static final ObjectMapper MAPPER = JsonMappers.lenient()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** {@code ima-openapi-clientid} 头的取值来源。 */
    @JsonProperty("client_id")
    private String clientId = "";

    /** {@code ima-openapi-apikey} 头的取值来源。 */
    @JsonProperty("api_key")
    private String apiKey = "";

    /** 本地化 / 测试部署的 IMA 地址；空 → {@link ImaFormats#DEFAULT_BASE_URL}。 */
    @JsonProperty("base_url")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String baseUrl = "";

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String v) {
        clientId = v == null ? "" : v;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String v) {
        apiKey = v == null ? "" : v;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String v) {
        baseUrl = v == null ? "" : v;
    }

    /**
     * 归一化后的基地址
     * （空 → 默认值、缺 scheme 补 {@code https://}、去尾斜杠）。
     *
     * <p>{@code @JsonIgnore} 是必须的：漏掉就会被 Jackson 当成属性，
     * 凭空多出一个 {@code baseURL} 键。</p>
     */
    @JsonIgnore
    public String baseURL() {
        String url = baseUrl == null ? "" : baseUrl.trim();
        if (url.isEmpty()) {
            return ImaFormats.DEFAULT_BASE_URL;
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
     * 解析并校验 IMA 专属配置。
     *
     * <p>校验顺序（失败即抛，后续不执行）：
     * config 为 null → {@link ConnectorException.InvalidConfig}；
     * credentials 反序列化失败 → {@code "parse ima credentials: ..."}；
     * {@code client_id} 空白 → {@link ConnectorException.InvalidCredentials}；
     * {@code api_key} 空白 → 同上；最后把基地址过一遍 SSRF 策略。</p>
     *
     * <p><b>注意最后一步会真的去解析 DNS</b>（除非白名单命中）——测试里必须
     * 把 {@code base_url} 指向被放行的 stub server，不能留空让它回落到
     * {@code https://ima.qq.com}。</p>
     */
    public static ImaConfig parse(DataSourceConfig config) {
        if (config == null) {
            throw new ConnectorException.InvalidConfig("config is nil");
        }
        ImaConfig cfg;
        try {
            Map<String, Object> credentials = config.getCredentials();
            // credentials 为 null 时解析成全默认配置。
            cfg = credentials == null ? new ImaConfig() : MAPPER.convertValue(credentials, ImaConfig.class);
            if (cfg == null) {
                cfg = new ImaConfig();
            }
        } catch (RuntimeException e) {
            throw new ConnectorException("parse ima credentials: " + e.getMessage(), e);
        }
        if (ImaFormats.isGoBlank(cfg.clientId)) {
            throw new ConnectorException.InvalidCredentials("client_id is required");
        }
        if (ImaFormats.isGoBlank(cfg.apiKey)) {
            throw new ConnectorException.InvalidCredentials("api_key is required");
        }
        // 基地址过 SSRF 策略，失败时原文抛出（不包装）。
        ConnectorHttp.validateConnectorBaseUrl(cfg.baseURL());
        return cfg;
    }

    /** 供日志用的地址归一。 */
    @Override
    public String toString() {
        return "ImaConfig{baseUrl=" + baseURL().toLowerCase(Locale.ROOT) + '}';
    }
}
