package com.ragagent.datasource.connector.notion;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Notion 的 page / database / data_source 三种对象共用的形状。
 *
 * <p><b>内部 API 形状，不是契约</b>：只进出于 Notion API 的 JSON，从不落 jsonb、
 * 从不作 HTTP 响应体。</p>
 *
 * <h2>三个关键点</h2>
 * <ol>
 *   <li><b>{@code title} 派生字段标 {@link JsonIgnore}</b>：它不是 API 字段，而是
 *       {@code extractTitle} 在反序列化之后填进去的派生值——字段名叫 {@code title}
 *       会与 {@link #rawTitle} 的 {@code "title"} 抢同一个属性名。</li>
 *   <li><b>{@code rawTitle} 是**顶层** {@code title} 数组</b>（数据库对象用它，
 *       页面对象用 {@code properties}）。</li>
 *   <li><b>{@code rawProperties} 保留原始 JSON</b>：后续 {@code extractTitle} /
 *       {@code extractPropertySchema} / {@code propertyToString} 都在这棵原始树上做
 *       "按文档序取第一个 title"与"按名字取值"，而不是反序列化成强类型——
 *       这正是这些函数"通用、不硬编码 22 种属性"的原因。</li>
 * </ol>
 */
public final class NotionPage {

    @JsonProperty("id")
    public String id;

    /** {@code "page"} | {@code "database"} | {@code "data_source"}（2025-09-03+）。 */
    @JsonProperty("object")
    public String object;

    @JsonProperty("parent")
    public NotionParent parent;

    @JsonProperty("url")
    public String url;

    /**
     * Notion 若省了这个字段，这里保持 {@code null}，
     * {@code FetchedItem.UpdatedAt} 随后归一成零值 {@code "0001-01-01T00:00:00Z"}，
     * 判定"有没有时间"用 {@code != null}。
     */
    @JsonProperty("last_edited_time")
    public OffsetDateTime lastEditedTime;

    @JsonProperty("in_trash")
    public boolean inTrash;

    /** 由 {@code extractTitle} 填（不参与 JSON）。 */
    @JsonIgnore
    public String title;

    /** 顶层 {@code title} 数组（数据库对象）。 */
    @JsonProperty("title")
    public JsonNode rawTitle;

    /** 属性原始 JSON，供三个属性抽取函数使用。 */
    @JsonProperty("properties")
    public JsonNode rawProperties;

    /**
     * 数据源在工作区层级里的位置（仅 data_source 对象有，API 2025-09-03+）。
     * 例如数据库放在某个页面里 → {@code {type:"page_id", page_id:"…"}}。
     */
    @JsonProperty("database_parent")
    public NotionParent databaseParent;

    public String id() {
        return id == null ? "" : id;
    }

    public String object() {
        return object == null ? "" : object;
    }

    public String url() {
        return url == null ? "" : url;
    }

    public NotionParent parent() {
        return parent == null ? new NotionParent() : parent;
    }

    /** database 与 data_source 都算。 */
    public boolean isDatabase() {
        String obj = object();
        return "database".equals(obj) || "data_source".equals(obj);
    }
}
