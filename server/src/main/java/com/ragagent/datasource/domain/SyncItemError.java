package com.ragagent.datasource.domain;

import java.util.Map;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

/**
 * 一条面向用户的失败样本。
 *
 * <p>老同步日志里每个 error 是一个裸 JSON 字符串，所以反序列化时裸字符串解成
 * {@code Message}（由 {@link SyncItemErrorDeserializer} 同款分支处理，让老日志仍可读）。</p>
 *
 * <h2>JSON 形状（{@code DataSourceJsonTest} 逐字节钉住）</h2>
 * <pre>
 *   SyncItemError{}                   → {}
 *   SyncItemError{全字段}              → {"title":"t","code":"c","params":{"code":"1663"},"message":"m"}
 *   SyncItemError{只填 Message}        → {"message":"m"}
 * </pre>
 * <p>⚠️ 四个键**全部恒输出**，零值对象是
 * {@code {"title":"","code":"","params":null,"message":""}}——
 * 不是 {@code null}、也不是带空串的对象。这是 {@link SyncResult#errors} 里最常见的形态。</p>
 *
 * <h2>{@code Display()} 的取值序</h2>
 * <pre>
 *   title 与 message 都有 → "title: message"
 *   只有 message           → "message"
 *   其余（含只有 title）    → title
 * </pre>
 *
 * <h2>持久化语义</h2>
 * <ol>
 *   <li><b>钩子/软删除/自动时间戳/唯一索引/关联预加载/默认排序</b>：全无——
 *       本类型不落表，只作为 {@code SyncResult.errors} 的元素被序列化进 jsonb。</li>
 * </ol>
 */
@JsonDeserialize(using = SyncItemError.Deserializer.class)
public class SyncItemError {

    /** 文档标题（用户内容，保留原文）。恒输出。 */
    private String title = "";

    /**
     * 前端映射到本地化文案的稳定 key，例如
     * {@code "feishu_rate_limited"} → {@code datasource.syncError.feishu_rate_limited}。
     * 恒输出。
     */
    private String code = "";

    /** 本地化文案的插值参数，例如 {@code {"code":"1663"}}。恒输出。 */
    @JsonSerialize(using = DataSourceMapSerializer.class)
    private Map<String, String> params;

    /** 客户端没有 Code 的 i18n key 时用的人话兜底。恒输出。 */
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
     * 把样本渲染成一条纯文本，供服务端使用（日志、致命错误详情）。
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
     * 反序列化分支：
     * <pre>
     *   裸字符串        → Message = 该串（历史行）
     *   对象            → 逐字段（未知键**忽略**）
     *   其它（数字等）  → Message 保持零值（宽容成"空样本"，不让整条日志读不出来）
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
