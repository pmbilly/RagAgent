package com.ragagent.datasource.connector.notion;

/**
 * Notion 连接器的全部常量。值与既有线格式**一字不改**。
 *
 * <p>本类不是契约、不落 jsonb、不作响应体：它只是把散落在各处的编译期常量
 * 收拢到一处。</p>
 */
public final class NotionConstants {

    private NotionConstants() {
    }

    /** 本连接器使用的 Notion API 版本。 */
    public static final String API_VERSION = "2026-03-11";

    /** Notion API 默认地址。 */
    public static final String DEFAULT_BASE_URL = "https://api.notion.com";

    // ── notionParent.Type 的取值 ──────────────────────────────────────────

    public static final String PARENT_TYPE_WORKSPACE = "workspace";
    public static final String PARENT_TYPE_PAGE_ID = "page_id";
    public static final String PARENT_TYPE_DATABASE_ID = "database_id";
    public static final String PARENT_TYPE_DATA_SOURCE_ID = "data_source_id";
    public static final String PARENT_TYPE_BLOCK_ID = "block_id";

    // ── 抓取/重试上限 ──────────────────────────────────────────────────────

    /** 重试**次数**（总请求数 = 1 + 3 = 4）。 */
    public static final int MAX_RETRIES = 3;

    /** 块递归深度上限。 */
    public static final int MAX_BLOCK_DEPTH = 5;

    /** 单页最多抓多少块，防止 API 调用失控。 */
    public static final int MAX_BLOCKS_PER_PAGE = 1000;

    /** 100MB，防止超大附件把进程撑爆。 */
    public static final int MAX_DOWNLOAD_SIZE = 100 * 1024 * 1024;

    // ── 抓取结果的形状 ────────────────────────────────────────────────────

    /** 知识条目的 Content-Type。 */
    public static final String CONTENT_TYPE_MARKDOWN = "text/markdown";

    /** 对象类型：页面。 */
    public static final String OBJECT_TYPE_PAGE = "page";

    /** 对象类型：数据库。 */
    public static final String OBJECT_TYPE_DATABASE = "database";

    /** 对象类型：附件。 */
    public static final String OBJECT_TYPE_ATTACHMENT = "attachment";

    /** 无标题页面的默认名。 */
    public static final String DEFAULT_UNTITLED_NAME = "Untitled";

    /** 渠道标签（{@code "notion"}）。 */
    public static final String CHANNEL_NOTION = "notion";
}
