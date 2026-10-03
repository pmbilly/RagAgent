package com.ragagent.datasource.connector.notion;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 页面/数据库的父关系。
 *
 * <p><b>内部 API 形状，不是契约</b>：只进出于 Notion API 的 JSON，从不落 jsonb、
 * 从不作 HTTP 响应体，所以不需要 {@code @JsonIgnore}/{@code SortedMapSerializer}
 * 那一套。字段用 public（Jackson 直接绑定），取值一律靠 {@link #parentId()} 归一化。</p>
 *
 * <p>本类型从不写出，只在解析时用。</p>
 */
public final class NotionParent {

    @JsonProperty("type")
    public String type;

    @JsonProperty("page_id")
    public String pageId;

    @JsonProperty("database_id")
    public String databaseId;

    @JsonProperty("data_source_id")
    public String dataSourceId;

    @JsonProperty("block_id")
    public String blockId;

    /** 缺字段时为 {@code ""}。 */
    public String type() {
        return type == null ? "" : type;
    }

    /**
     * 按 type 取对应的那个 ID，
     * 其它 type（含 {@code "workspace"} 与空 type）一律回 {@code ""}。
     */
    public String parentId() {
        switch (type()) {
            case NotionConstants.PARENT_TYPE_PAGE_ID:
                return pageId == null ? "" : pageId;
            case NotionConstants.PARENT_TYPE_DATABASE_ID:
                return databaseId == null ? "" : databaseId;
            case NotionConstants.PARENT_TYPE_DATA_SOURCE_ID:
                return dataSourceId == null ? "" : dataSourceId;
            case NotionConstants.PARENT_TYPE_BLOCK_ID:
                return blockId == null ? "" : blockId;
            default:
                return "";
        }
    }
}
