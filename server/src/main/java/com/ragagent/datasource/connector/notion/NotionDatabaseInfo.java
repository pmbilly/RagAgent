package com.ragagent.datasource.connector.notion;

/**
 * 一次 API 调用同时拿到的"数据库元数据 + 主数据源 ID"。
 *
 * <p><b>内部值对象，不是契约</b>：不落 jsonb、不作响应体。</p>
 */
public final class NotionDatabaseInfo {

    public final NotionPage page;

    /** 主 data_source 的 ID；{@code GetDatabaseInfo} 在 {@code data_sources} 为空时给 {@code ""}。 */
    public final String dataSourceId;

    public NotionDatabaseInfo(NotionPage page, String dataSourceId) {
        this.page = page;
        this.dataSourceId = dataSourceId == null ? "" : dataSourceId;
    }
}
