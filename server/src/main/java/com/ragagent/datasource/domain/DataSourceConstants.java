package com.ragagent.datasource.domain;

/**
 * 数据源相关的全部字符串常量（对照 Go {@code internal/types/datasource.go} 顶部的
 * 四组 {@code const} 块，L14-62）。
 *
 * <p>Go 里它们是包级常量（{@code types.ConnectorTypeFeishu} 等），Java 侧集中到一个
 * 不可实例化的类里，命名逐字对应 Go 的标识符（大写下划线化），这样从 Go 代码过来
 * 找常量不需要猜。与 memory 模块的 {@code MemoryKinds} 是同一处置。</p>
 *
 * <p><b>为什么不做成枚举</b>：这些值直接落库（{@code data_sources.type} /
 * {@code sync_mode} / {@code status} / {@code conflict_strategy}、
 * {@code sync_logs.status}），而 Go 侧是裸 string——未知取值（历史行、新连接器）
 * 必须能原样读写。枚举会逼出一个 {@code UNKNOWN} 分支并悄悄改写数据，
 * 与"逐字节一致"的目标相反。</p>
 */
public final class DataSourceConstants {

    private DataSourceConstants() {
    }

    // ── Connector types ────────────────────────────────────────────────────

    /** 飞书（open.feishu.cn）。 */
    public static final String CONNECTOR_TYPE_FEISHU = "feishu";

    /**
     * Lark 是飞书的国际版（open.larksuite.com），复用飞书连接器，
     * 只有 API host 与 tenant 不同。
     */
    public static final String CONNECTOR_TYPE_LARK = "lark";

    /**
     * 飞书云盘模式：同步用户给定 folder_token 下的文档（而不是整个 Wiki 空间）。
     * 复用 feishu 连接器包，只有资源枚举与抓取不同。
     */
    public static final String CONNECTOR_TYPE_FEISHU_DRIVE = "feishu_drive";

    /** Lark 云盘模式，{@link #CONNECTOR_TYPE_FEISHU_DRIVE} 的国际版。 */
    public static final String CONNECTOR_TYPE_LARK_DRIVE = "lark_drive";

    public static final String CONNECTOR_TYPE_NOTION = "notion";
    public static final String CONNECTOR_TYPE_CONFLUENCE = "confluence";
    public static final String CONNECTOR_TYPE_YUQUE = "yuque";
    public static final String CONNECTOR_TYPE_GITHUB = "github";
    public static final String CONNECTOR_TYPE_GOOGLE_DRIVE = "google_drive";
    public static final String CONNECTOR_TYPE_ONEDRIVE = "onedrive";
    public static final String CONNECTOR_TYPE_DINGTALK = "dingtalk";
    public static final String CONNECTOR_TYPE_WEB_CRAWLER = "web_crawler";
    public static final String CONNECTOR_TYPE_SLACK = "slack";
    public static final String CONNECTOR_TYPE_IMAP = "imap";
    public static final String CONNECTOR_TYPE_RSS = "rss";
    public static final String CONNECTOR_TYPE_GITLAB = "gitlab";
    public static final String CONNECTOR_TYPE_IMA = "ima";

    // ── Sync modes ─────────────────────────────────────────────────────────

    public static final String SYNC_MODE_INCREMENTAL = "incremental";
    public static final String SYNC_MODE_FULL = "full";

    // ── Data source status ─────────────────────────────────────────────────

    public static final String DATA_SOURCE_STATUS_ACTIVE = "active";
    public static final String DATA_SOURCE_STATUS_PAUSED = "paused";
    public static final String DATA_SOURCE_STATUS_ERROR = "error";
    public static final String DATA_SOURCE_STATUS_DELETED = "deleted";

    // ── Sync log status ────────────────────────────────────────────────────

    public static final String SYNC_LOG_STATUS_RUNNING = "running";
    public static final String SYNC_LOG_STATUS_SUCCESS = "success";
    public static final String SYNC_LOG_STATUS_PARTIAL = "partial";
    public static final String SYNC_LOG_STATUS_FAILED = "failed";
    public static final String SYNC_LOG_STATUS_CANCELED = "canceled";

    // ⚠️ {@code "pending"} **没有**常量——它只出现在 CancelPendingByDataSource 的
    // 内联切片里（{ SyncLogStatusRunning, "pending" }），是一个从未被写入过的"半终态"取值。
    // 照抄 Go，不为它造常量。

    // ── Conflict resolution strategies ─────────────────────────────────────

    public static final String CONFLICT_STRATEGY_OVERWRITE = "overwrite";
    public static final String CONFLICT_STRATEGY_SKIP = "skip";

    // ── 非字符串常量（Go 里散落在 tag / 代码中的字面量） ────────────────────

    /**
     * {@code data_sources.sync_mode} 的 DDL/struct tag 默认值
     * （{@code gorm:"default:'incremental'"}）。
     */
    public static final String DEFAULT_SYNC_MODE = SYNC_MODE_INCREMENTAL;

    /** {@code data_sources.status} 的默认值（{@code gorm:"default:'active'"}）。 */
    public static final String DEFAULT_STATUS = DATA_SOURCE_STATUS_ACTIVE;

    /** {@code data_sources.conflict_strategy} 的默认值。 */
    public static final String DEFAULT_CONFLICT_STRATEGY = CONFLICT_STRATEGY_OVERWRITE;

    /** {@code data_sources.sync_deletions} 的默认值（{@code gorm:"default:true"}）。 */
    public static final boolean DEFAULT_SYNC_DELETIONS = true;

    /** {@code data_sources.sync_log_retention_days} 的默认值（{@code gorm:"default:30"}）。 */
    public static final int DEFAULT_SYNC_LOG_RETENTION_DAYS = 30;

    /** {@code CleanupOldLogs} 在 {@code retentionDays <= 0} 时回落的 30 天。 */
    public static final int FALLBACK_RETENTION_DAYS = 30;

    /** {@code FindByDataSource} 在 {@code limit <= 0} 时回落的页大小。 */
    public static final int DEFAULT_SYNC_LOG_PAGE_SIZE = 10;
}
