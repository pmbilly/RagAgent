package com.ragagent.datasource.connector.notion;

/**
 * Notion 连接器的全部常量（对照 Go {@code notion} 包散落在 types.go / client.go /
 * connector.go 三处的 {@code const} 块）。值**一字不改**照抄。
 *
 * <h2>每个常量的 Go 出处</h2>
 * <ul>
 *   <li>{@link #API_VERSION} / {@link #DEFAULT_BASE_URL} — types.go L23-26</li>
 *   <li>{@link #PARENT_TYPE_WORKSPACE} 等五个 — types.go L73-80</li>
 *   <li>{@link #MAX_RETRIES} — client.go L45（{@code maxRetries = 3}）</li>
 *   <li>{@link #MAX_BLOCK_DEPTH} / {@link #MAX_BLOCKS_PER_PAGE} — client.go L273-274</li>
 *   <li>{@link #MAX_DOWNLOAD_SIZE} — client.go L377（{@code 100 * 1024 * 1024}）</li>
 *   <li>{@link #CONTENT_TYPE_MARKDOWN} / {@code objectType*} / {@link #DEFAULT_UNTITLED_NAME}
 *       — connector.go L15-21</li>
 *   <li>{@link #CHANNEL_NOTION} — {@code types.ChannelNotion}（internal/types/knowledge.go L35）。
 *       Java 侧暂无该常量（知识库模块未翻译过半数字段），故在连接器包内自持一份，
 *       值与 Go 相同：{@code "notion"}。</li>
 * </ul>
 *
 * <p>本类不是契约、不落 jsonb、不作响应体：它只是把 Go 的编译期常量原样搬过来，
 * 好让"Go 里 grep 得到的常量在 Java 里也 grep 得到"。</p>
 */
public final class NotionConstants {

    private NotionConstants() {
    }

    /** 对照 Go {@code NotionAPIVersion}：本连接器使用的 Notion API 版本。 */
    public static final String API_VERSION = "2026-03-11";

    /** 对照 Go {@code DefaultBaseURL}。 */
    public static final String DEFAULT_BASE_URL = "https://api.notion.com";

    // ── notionParent.Type 的取值（Go 的五个 parentType* 常量） ──────────────

    public static final String PARENT_TYPE_WORKSPACE = "workspace";
    public static final String PARENT_TYPE_PAGE_ID = "page_id";
    public static final String PARENT_TYPE_DATABASE_ID = "database_id";
    public static final String PARENT_TYPE_DATA_SOURCE_ID = "data_source_id";
    public static final String PARENT_TYPE_BLOCK_ID = "block_id";

    // ── 抓取/重试上限 ──────────────────────────────────────────────────────

    /** 对照 Go {@code maxRetries}：重试**次数**（总请求数 = 1 + 3 = 4）。 */
    public static final int MAX_RETRIES = 3;

    /** 对照 Go {@code maxBlockDepth}：块递归深度上限。 */
    public static final int MAX_BLOCK_DEPTH = 5;

    /** 对照 Go {@code maxBlocksPerPage}：单页最多抓多少块，防止 API 调用失控。 */
    public static final int MAX_BLOCKS_PER_PAGE = 1000;

    /** 对照 Go {@code maxDownloadSize}：100MB，防止超大附件把进程撑爆。 */
    public static final int MAX_DOWNLOAD_SIZE = 100 * 1024 * 1024;

    // ── 抓取结果的形状 ────────────────────────────────────────────────────

    /** 对照 Go {@code contentTypeMarkdown}。 */
    public static final String CONTENT_TYPE_MARKDOWN = "text/markdown";

    /** 对照 Go {@code objectTypePage}。 */
    public static final String OBJECT_TYPE_PAGE = "page";

    /** 对照 Go {@code objectTypeDatabase}。 */
    public static final String OBJECT_TYPE_DATABASE = "database";

    /** 对照 Go {@code objectTypeAttachment}。 */
    public static final String OBJECT_TYPE_ATTACHMENT = "attachment";

    /** 对照 Go {@code defaultUntitledName}。 */
    public static final String DEFAULT_UNTITLED_NAME = "Untitled";

    /** 对照 Go {@code types.ChannelNotion}（{@code "notion"}）。 */
    public static final String CHANNEL_NOTION = "notion";
}
