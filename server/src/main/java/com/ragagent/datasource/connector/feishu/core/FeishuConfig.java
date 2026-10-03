package com.ragagent.datasource.connector.feishu.core;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 飞书连接器配置与导出格式常量。
 *
 * <h2>这是内部 API 形状，不是契约</h2>
 * <p>它<b>从不落 jsonb、从不进 HTTP 响应</b>——它只是把 {@code DataSourceConfig.credentials}
 * 那张 map（以及 {@code settings.timezone}）解析成一个有类型的对象，供连接器内部使用。
 * 所以本类<b>不需要</b>{@code @JsonIgnore}/蛇形键名/往返测试那套契约治理
 * （只有会落 jsonb 或作响应体的类型才需要）。</p>
 *
 * <p>键名沿用 credentials 里的既定键（{@code app_id}/{@code app_secret}/{@code base_url}/{@code timezone}），
 * 因为<b>入参</b>是外部系统/前端写进 credentials 的 JSON——解析不认这些键就读不出凭据。</p>
 *
 * <h2>落库行为清单</h2>
 * <ol>
 *   <li><b>钩子 / 关联预加载 / 软删除 / 默认排序 / 唯一索引 / 自动时间戳</b>：全无——
 *       本类型不落表，只在连接器内部流动。</li>
 * </ol>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FeishuConfig {

    /** 默认 Open Platform API origin。 */
    public static final String DEFAULT_BASE_URL = "https://open.feishu.cn";

    /** Lark（国际版）Open Platform API origin。 */
    public static final String LARK_BASE_URL = "https://open.larksuite.com";

    /**
     * GMT+8（飞书大陆默认时区），在没有配置 timezone 时使用。
     *
     * <p>用<b>固定偏移</b>而非 IANA 区域，避免依赖系统 tzdata——精简容器镜像可能没装。</p>
     */
    public static final int DEFAULT_TIMEZONE_OFFSET_SECONDS = 8 * 3600;

    // ── 导出格式常量（导出任务 API：POST /drive/v1/export_tasks） ──────────

    /** 导出为 .docx。 */
    public static final String EXPORT_TYPE_DOCX = "docx";

    /** 导出为 .xlsx（表格 / 多维表格）。 */
    public static final String EXPORT_TYPE_XLSX = "xlsx";

    /** 导出为 .pdf（兜底）。 */
    public static final String EXPORT_TYPE_PDF = "pdf";

    /** obj_type → 导出 file_extension。 */
    public static final Map<String, String> OBJ_TYPE_TO_EXPORT_FILE_EXTENSION = Map.of(
            "docx", EXPORT_TYPE_DOCX,
            "doc", EXPORT_TYPE_DOCX,
            "sheet", EXPORT_TYPE_XLSX,
            "bitable", EXPORT_TYPE_XLSX);

    /** obj_type → 导出 API 的 type 参数。 */
    public static final Map<String, String> OBJ_TYPE_TO_EXPORT_TYPE = Map.of(
            "docx", "docx",
            "doc", "doc",
            "sheet", "sheet",
            "bitable", "bitable");

    /** 导出 file_extension → 文件名后缀。 */
    public static final Map<String, String> EXPORT_FILE_EXT_TO_SUFFIX = Map.of(
            EXPORT_TYPE_DOCX, ".docx",
            EXPORT_TYPE_XLSX, ".xlsx",
            EXPORT_TYPE_PDF, ".pdf");

    @JsonProperty("app_id")
    private String appId = "";

    @JsonProperty("app_secret")
    private String appSecret = "";

    @JsonProperty("base_url")
    private String baseUrl = "";

    /**
     * 渲染多维表格日期单元格用的 IANA 时区名（如 {@code "Asia/Shanghai"}）。
     *
     * <p>飞书把日期存成 UTC 瞬时，但用户看到的日历日期是"该瞬时在表格时区下"的日期，
     * 用 UTC 渲染会把日期整体挪一天。空 → {@code GMT+8}（飞书大陆租户），
     * Lark 其他时区的租户要显式设置。</p>
     */
    @JsonProperty("timezone")
    private String timezone = "";

    public FeishuConfig() {
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String v) {
        appId = v == null ? "" : v;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String v) {
        appSecret = v == null ? "" : v;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String v) {
        baseUrl = v == null ? "" : v;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String v) {
        timezone = v == null ? "" : v;
    }

    /**
     * 没配就回飞书默认 origin。
     *
     * <p>注意这是<b>二级兜底</b>：一级是 {@link FeishuSupport#parseFeishuConfig}
     * 用 region 的 host 填上（那样 {@code base_url} 留空的老数据源能跟着 region 走）。</p>
     */
    public String resolveBaseUrl() {
        return baseUrl.isEmpty() ? DEFAULT_BASE_URL : baseUrl;
    }

    /**
     * 多维表格日期单元格的渲染时区。
     *
     * <p>空名 → 固定 GMT+8（不依赖 tzdata）；有名 → 从系统 tz 库加载，
     * 载不到就回落 GMT+8。未知名上 {@link ZoneId#of} 抛 {@link DateTimeException}。</p>
     *
     * <p>回落用<b>固定偏移</b>：名字无所谓，
     * 起作用的只有偏移量，所以用 {@link ZoneOffset#ofTotalSeconds} 即可。</p>
     */
    public static ZoneId resolveLocation(String name) {
        if (name != null && !name.isEmpty()) {
            try {
                return ZoneId.of(name);
            } catch (DateTimeException ignored) {
                // 回落 GMT+8
            }
        }
        return ZoneOffset.ofTotalSeconds(DEFAULT_TIMEZONE_OFFSET_SECONDS);
    }
}
