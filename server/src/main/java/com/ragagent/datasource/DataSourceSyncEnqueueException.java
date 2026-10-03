package com.ragagent.datasource;

/**
 * 同步任务投递失败。
 *
 * <p>调度器捕获它之后会把 sync_log 置为 {@code failed}、写下
 * {@code "enqueue failed: <getMessage()>"}。</p>
 *
 * <p>为什么不复用 {@link ConnectorException}：这是<b>队列</b>的失败，不是连接器/外部 API 的失败，
 * 而 service 层对 {@code ConnectorException.InvalidCredentials} 有特殊处理
 * （把数据源置为 error 状态并停止排期）。混在一起会让"队列暂时不可用"被误判成"凭据失效"。</p>
 */
public class DataSourceSyncEnqueueException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DataSourceSyncEnqueueException(String message) {
        super(message);
    }

    public DataSourceSyncEnqueueException(String message, Throwable cause) {
        super(message, cause);
    }
}
