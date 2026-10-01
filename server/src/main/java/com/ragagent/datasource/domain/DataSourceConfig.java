package com.ragagent.datasource.domain;

import com.ragagent.common.web.JsonMappers;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.crypto.CryptoService;

/**
 * 未加密的数据源配置（对照 Go {@code types.DataSourceConfig}，
 * internal/types/datasource.go L211-280）。
 *
 * <p>它既是 {@code data_sources.config} 这个 jsonb 列的**值形状**，也是
 * {@code DataSourceConfigDTO} 的来源。凭据管理走独立的 {@code /credentials} 子资源
 * ——密钥值**从不**出现在 API 响应里（handler 经 {@code dto.NewDataSourceResponse}
 * 按构造剥离 Credentials map）。</p>
 *
 * <h2>Go 实录（{@code DataSourceJsonTest} 逐字节钉住）</h2>
 * <pre>
 *   DataSourceConfig{}            → {"type":"","credentials":null,"resource_ids":null,"settings":null}
 *   DataSourceConfig{Type:"rss"}  → {"type":"rss","credentials":null,"resource_ids":null,"settings":null}
 *   带全部字段                     → {"type":"feishu","credentials":{"app_id":"x","b":true,"n":1},
 *                                    "resource_ids":["r1","r2"],"settings":{"folder_token":"ft"}}
 * </pre>
 * <p><b>四个键全部恒输出</b>（没有一个带 omitempty），{@code multimodal_enabled} 一个键都不出。</p>
 *
 * <h2>GORM 隐式行为清单（约定 §3）</h2>
 * <ol>
 *   <li><b>钩子</b>：无。</li>
 *   <li><b>关联预加载</b>：无。</li>
 *   <li><b>软删除</b>：本类型不落表，无。</li>
 *   <li><b>默认排序</b>：无。</li>
 *   <li><b>唯一索引/外键</b>：无。</li>
 *   <li><b>自动时间戳</b>：无。</li>
 * </ol>
 *
 * <h2>三条必须照抄的语义</h2>
 * <ol>
 *   <li><b>{@code MultimodalEnabled} 是 {@code json:"-"}，且从不落库</b>：它不是"响应里没有
 *       但库里还在"的那种——JSON 与 jsonb 是**同一个 {@code json.Marshal}**，
 *       所以它两边都不出现。它是"每次同步前由 service 按目标知识库的 VLM 设置临时填上"的
 *       **运行期**字段。Java 侧 {@code @JsonIgnore}，不参与任何持久化。</li>
 *   <li><b>{@code Has*()} 方法在 Go 里是方法、不是字段</b>，故 Java 侧刻意**不加**
 *       {@code get}/{@code is} 前缀（叫 {@code hasCredentials()}），Jackson 便不会把它们
 *       当属性写进 JSON——这正是约定 §7.5 第 2 条要防的那类泄漏。</li>
 *   <li><b>{@code ToJSON} 的加密是浅拷贝</b>：只加密 Credentials 里**值为非空字符串**的项，
 *       其余（数字 / 布尔 / 嵌套对象）原样穿过；且绝不改动调用方的内存 map
 *       （否则后续的读会看见密文）。</li>
 * </ol>
 */
public class DataSourceConfig {

    /** 与 Go 的 {@code json.Marshal} 对齐的写出器（容忍未知属性，对照 json.Unmarshal）。 */
    private static final ObjectMapper MAPPER = JsonMappers.lenient()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private String type = "";

    /**
     * OAuth/API 凭据（按连接器不同）。落库前逐项 AES-256-GCM 加密
     * （见 {@link #toJSON()}）；API 响应里整张 map 被剥离。
     *
     * <p>挂 {@link DataSourceMapSerializer} 而不是 {@code GoMapSerializer}：
     * 连接器会把数字塞进这张表，而 map 值的 {@code Double} 格式也要按 Go 的编码器走。</p>
     */
    @JsonSerialize(using = DataSourceMapSerializer.class)
    private Map<String, Object> credentials;

    /** 要同步的资源 ID（文件夹 ID、空间 ID 等）。 */
    private List<String> resourceIds;

    /** 连接器私有配置。 */
    @JsonSerialize(using = DataSourceMapSerializer.class)
    private Map<String, Object> settings;

    /**
     * 镜像目标知识库当前的 VLM/多模态设置，供本次同步使用。
     * service 在每次抓取前填它，**从不持久化**——设置归知识库所有。
     * 连接器用它决定"抽内嵌图片做 OCR"值不值得：往没有 VLM 的知识库灌图片会被拒，
     * 所以为 false 时直接跳过图片抽取。
     */
    @JsonIgnore
    private boolean multimodalEnabled;

    public String getType() { return type; }
    public void setType(String v) { type = v == null ? "" : v; }

    public Map<String, Object> getCredentials() { return credentials; }
    public void setCredentials(Map<String, Object> v) { credentials = v; }

    public List<String> getResourceIds() { return resourceIds; }
    public void setResourceIds(List<String> v) { resourceIds = v; }

    public Map<String, Object> getSettings() { return settings; }
    public void setSettings(Map<String, Object> v) { settings = v; }

    /**
     * ⚠️ 与 {@link #setMultimodalEnabled(boolean)} **成对**加 {@code @JsonIgnore}：
     * 字段名 {@code multimodalEnabled} 与 getter {@code isMultimodalEnabled()} 的隐式属性名
     * 一致（都叫 {@code multimodalEnabled}），本会合并成一个属性；但 Go 的 tag 是
     * {@code json:"-"}，两侧都必须不输出——显式标掉最稳。
     */
    @JsonIgnore
    public boolean isMultimodalEnabled() { return multimodalEnabled; }

    @JsonIgnore
    public void setMultimodalEnabled(boolean v) { multimodalEnabled = v; }

    // ── 业务方法（对照 Go 的三个方法） ─────────────────────────────────────

    /**
     * 对照 Go {@code HasCredentials}：凭据 map 里到底有没有值。
     * Update 路径与凭据子资源用它决定要不要跑一次真实的连接器校验。
     */
    @JsonIgnore
    public boolean hasCredentials() {
        return credentials != null && !credentials.isEmpty();
    }

    /**
     * 对照 Go {@code HasConfiguredCredentials}：**用户可见的密钥**是否已配置。
     *
     * <p>RSS 的 feed URL 是非密钥配置（属于 settings）；对该连接器而言，
     * 只有 {@code auth_headers} 算凭据。所以 {@code auth_headers} 缺失、或只有空白，
     * 都算"没配"。</p>
     */
    @JsonIgnore
    public boolean hasConfiguredCredentials(String connectorType) {
        if (credentials == null || credentials.isEmpty()) {
            return false;
        }
        if (DataSourceConstants.CONNECTOR_TYPE_RSS.equals(connectorType)) {
            if (!credentials.containsKey("auth_headers")) {
                return false;
            }
            Object raw = credentials.get("auth_headers");
            return raw instanceof String s && !s.trim().isEmpty();
        }
        return true;
    }

    /**
     * 对照 Go {@code StripNonSecretCredentials}：落库前把**误存进** credentials
     * 的非密钥项删掉。
     *
     * <p>RSS 的 {@code feed_urls} 历史上曾住在 credentials 里、现在归 settings；
     * 清完若 map 空了就置为 {@code null}（Go 的 {@code d.Credentials = nil}）——
     * 落库那一步会把它写成 SQL NULL，与"从未配过凭据"不可区分。</p>
     *
     * <p>Go 的接收者是 {@code *DataSourceConfig}，首行判 {@code d == nil || d.Credentials == nil}
     * 直接返回；Java 侧照抄这个 no-op（对 null 调用不抛）。</p>
     */
    public void stripNonSecretCredentials(String connectorType) {
        if (credentials == null) {
            return;
        }
        if (DataSourceConstants.CONNECTOR_TYPE_RSS.equals(connectorType)) {
            credentials.remove("feed_urls");
            if (credentials.isEmpty()) {
                credentials = null;
            }
        }
    }

    /**
     * 对照 Go {@code ToJSON}：把本对象转成写进 {@code DataSource.Config} 的 JSON。
     *
     * <p>配了 {@code SYSTEM_AES_KEY} 时，Credentials 里**每个非空字符串值**先
     * AES-256-GCM 加密再序列化；非字符串值（数字 / 布尔 / 嵌套对象）原样穿过。
     * 这是凭据抵达数据库的**唯一写路径**（Go 的 GORM {@code JSON} 类型本身只是字节透传），
     * 所以在这里加密就足以让 {@code DataSource.Config} 落盘时整块是密文。</p>
     *
     * <p>加密作用在 Credentials 的**浅拷贝**上，避免改到调用方的内存 map
     * ——否则后续的读会看见密文。</p>
     *
     * <p><b>与 Go 的签名差异</b>：Go 读包级 env（{@code utils.GetAESKey()}），
     * 所以 {@code ToJSON()} 无参。Java 侧用 {@code new CryptoService()}
     * （无状态、只在调用时读 env）保持同样的无参签名——与
     * {@code ModelParametersTypeHandler} 的默认构造器是同一处置。</p>
     *
     * @return 可赋给 {@code DataSource.config} 的 JSON；接收者为 null 时回 null
     */
    public JsonNode toJSON() {
        CryptoService crypto = new CryptoService();
        byte[] key = crypto.getAESKey();

        // 浅拷贝：绝不改调用方的 map（Go 的 out := *d + 逐项填）
        DataSourceConfig out = new DataSourceConfig();
        out.type = this.type;
        out.resourceIds = this.resourceIds;
        out.settings = this.settings;
        out.multimodalEnabled = this.multimodalEnabled;

        if (key != null && credentials != null && !credentials.isEmpty()) {
            Map<String, Object> encCreds = new LinkedHashMap<>(Math.max(16, credentials.size()));
            for (Map.Entry<String, Object> entry : credentials.entrySet()) {
                Object v = entry.getValue();
                if (v instanceof String s && !s.isEmpty()) {
                    // 已是 enc:v1: 前缀时 encryptAESGCM 原样返回（幂等）
                    encCreds.put(entry.getKey(), crypto.encryptAESGCM(s, key));
                } else {
                    encCreds.put(entry.getKey(), v);
                }
            }
            out.credentials = encCreds;
        } else {
            out.credentials = credentials;
        }

        return MAPPER.valueToTree(out);
    }

    /**
     * 对照 Go 的 {@code json.Unmarshal(bytes, &config)}。
     *
     * <p>两态与 Go 一致：SQL NULL（{@code node == null}）→ Go 的
     * {@code len(d.Config) == 0} 短路回 {@code null}；字面量 {@code null}
     * （{@code NullNode}）→ {@code json.Unmarshal("null", &config)} **成功**且留下零值。</p>
     */
    public static DataSourceConfig fromJson(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isNull()) {
            return new DataSourceConfig();
        }
        return MAPPER.convertValue(node, DataSourceConfig.class);
    }
}
