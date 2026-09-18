package com.ragagent.datasource.connector.notion;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Notion 分页响应的公共外壳（对照 Go {@code paginatedResponse}，types.go L267-272）。
 *
 * <p><b>内部 API 形状，不是契约</b>：只进出于 Notion API 的 JSON。</p>
 *
 * <h2>{@link #results} 的两态必须是可区分的</h2>
 * <p>Go 的 {@code Results json.RawMessage}：字段**缺席** → {@code nil} →
 * {@code json.Unmarshal(nil, &pages)} 报 {@code "unexpected end of JSON input"}，
 * 整个分页以错误收场；字段是字面量 {@code null} → {@code RawMessage("null")}
 * （非 nil）→ 解出来是**零值切片**，分页正常继续。</p>
 * <p>Jackson 对 {@code JsonNode} 字段：缺席 → {@code null}；显式 {@code null}
 * → {@code NullNode}。两种情形因此天然可分，调用方按 {@code == null} 判前者。</p>
 */
public final class NotionPaginatedResponse {

    /** 恒为 {@code "list"}。 */
    @JsonProperty("object")
    public String object;

    @JsonProperty("results")
    public JsonNode results;

    @JsonProperty("has_more")
    public boolean hasMore;

    @JsonProperty("next_cursor")
    public String nextCursor;

    public String nextCursor() {
        return nextCursor == null ? "" : nextCursor;
    }
}
