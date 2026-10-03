package com.ragagent.datasource.domain;

/**
 * 数据源仓储的错误。
 *
 * <h2>为什么保持原文案</h2>
 * <p>真正的 HTTP 文案由 handler 另写（{@code GetDataSource} 失败一律
 * {@code 404 "data source not found"}）。所以这里的 message 不直接上线，
 * 但文本逐字稳定，日志里可 grep——与 memory 模块的 {@code MemorySubjectMissingException}
 * （message 就是 {@code "record not found"}）是同一处置。</p>
 *
 * <h2>为什么只有一个类型而不是"N 个异常类"</h2>
 * <p>仓储错误全靠 message 区分，没有更细的类型层次；造一堆子类会凭空发明
 * 没有的语义。唯一的例外是 {@link NotFoundException}——service 层要把它
 * 单独映射成 404，这个区别是真实存在的。</p>
 */
public class DataSourceException extends RuntimeException {

    public DataSourceException(String message) {
        super(message);
    }

    public DataSourceException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * "查不到这一行"。只有两处会产生它：
     * {@code DataSourceRepository.findById} 的 {@code "data source not found"}
     * 与 {@code SyncLogRepository.findById} 的 {@code "sync log not found"}。
     *
     * <p>⚠️ {@code SyncLogRepository.findLatest} **不抛**——它把
     * "未命中"吞成 {@code null} 返回，与同文件其它读方法不同，
     * 别统一。</p>
     */
    public static class NotFoundException extends DataSourceException {
        public NotFoundException(String message) {
            super(message);
        }
    }
}
