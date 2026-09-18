package com.ragagent.datasource.domain;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

/**
 * 一条面向用户的失败样本（对照 Go {@code types.SyncItemError}，
 * internal/types/datasource.go L448-493）。
 *
 * <p>Go 用一段**自定义 {@code UnmarshalJSON}** 让老同步日志仍可读：历史上每个 error
 * 就是一个裸 JSON 字符串，所以裸字符串解成 {@code Message}。Java 侧用
 * {@link SyncItemErrorDeserializer} 复刻同一分支。</p>
 *
 * <h2>Go 实录（{@code DataSourceJsonTest} 逐字节钉住）</h2>
 * <pre>
 *   SyncItemError{}                   → {}
 *   SyncItemError{全字段}              → {"title":"t","code":"c","params":{"code":"1663"},"message":"m"}
 *   SyncItemError{只填 Message}        → {"message":"m"}
 * </pre>
 * <p>⚠️ 四个键**全都带 omitempty**，所以零值对象序列化成 <b>{@code {}}</b>——
 * 不是 {@code null}、也不是带空串的对象。这是 {@link SyncResult#errors} 里最常见的形态。</p>
 *
 * <h2>{@code Display()} 的取值序</h2>
 * <pre>
 *   title 与 message 都有 → "title: message"
 *   只有 message           → "message"
 *   其余（含只有 title）    → title
 * </pre>
 *
 * <h2>GORM 隐式行为清单（约定 §3）</h2>
 * <ol>
 *   <li><b>钩子/软删除/自动时间戳/唯一索引/关联预加载/默认排序</b>：全无——
 *       本类型不落表，只作为 {@code SyncResult.Errors} 的元素被序列化进 jsonb。</li>
 * </ol>
 */
@JsonPropertyOrder({"title", "code", "params", "message"})
@JsonDeserialize(using = SyncItemError.Deserializer.class)
public class SyncItemError {

    /** 文档标题（用户内容，不翻译）。omitempty。 */
    @JsonProperty("title")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String title = "";

    /**
     * 前端映射到本地化文案的稳定 key，例如
     * {@code "feishu_rate_limited"} → {@code datasource.syncError.feishu_rate_limited}。
     * omitempty。
     */
    @JsonProperty("code")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String code = "";

    /** 本地化文案的插值参数，例如 {@code {"code":"1663"}}。omitempty。 */
    @JsonProperty("params")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonSerialize(using = DataSourceMapSerializer.class)
    private Map<String, String> params;

    /** 客户端没有 Code 的 i18n key 时用的人话兜底。omitempty。 */
    @JsonProperty("message")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String message = "";

    public String getTitle() { return title; }
    public void setTitle(String v) { title = v == null ? "" : v; }

    public String getCode() { return code; }
    public void setCode(String v) { code = v == null ? "" : v; }

    public Map<String, String> getParams() { return params; }
    public void setParams(Map<String, String> v) { params = v; }

    public String getMessage() { return message; }
    public void setMessage(String v) { message = v == null ? "" : v; }

    /**
     * 对照 Go {@code Display}：把样本渲染成一条纯文本，供服务端使用（日志、致命错误详情）。
     * 本地化那条路在前端由 Code/Params 拼；这里只是**不带语言**的兜底。
     */
    public String display() {
        if (!title.isEmpty() && !message.isEmpty()) {
            return title + ": " + message;
        }
        if (!message.isEmpty()) {
            return message;
        }
        return title;
    }

    /**
     * 对照 Go 的 {@code UnmarshalJSON}：
     * <pre>
     *   裸字符串        → Message = 该串（历史行）
     *   对象            → 逐字段（未知键**忽略**，与 json.Unmarshal 一致）
     *   其它（数字等）  → Message 保持零值（Go 的两条 unmarshal 都会失败并返回错误；
     *                    那会让整条 SyncResult 读不出来，故 Java 侧宽容成"空样本"）
     * </pre>
     * <p>注意这里刻意**不**抛异常：这段 JSON 是历史 jsonb 列的内容，
     * 一条坏样本不该让整个同步历史端点 500。</p>
     */
    public static final class Deserializer extends StdDeserializer<SyncItemError> {

        public Deserializer() {
            super(SyncItemError.class);
        }

        @Override
        public SyncItemError deserialize(JsonParser p, DeserializationContext ctxt)
                throws java.io.IOException {
            SyncItemError out = new SyncItemError();
            JsonNode node = p.readValueAsTree();
            if (node == null || node.isNull()) {
                return out;
            }
            if (node.isTextual()) {
                out.message = node.asText();
                return out;
            }
            if (!node.isObject()) {
                return out;
            }
            if (node.hasNonNull("title")) {
                out.title = node.get("title").asText();
            }
            if (node.hasNonNull("code")) {
                out.code = node.get("code").asText();
            }
            if (node.hasNonNull("message")) {
                out.message = node.get("message").asText();
            }
            JsonNode params = node.get("params");
            if (params != null && params.isObject()) {
                Map<String, String> map = new java.util.LinkedHashMap<>();
                params.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asText()));
                out.params = map;
            }
            return out;
        }
    }
}
