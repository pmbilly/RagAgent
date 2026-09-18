package com.ragagent.datasource.domain;

/**
 * 数据源仓储的错误（对照 Go {@code internal/application/repository/datasource_repo.go}
 * 里那一批 {@code errors.New(...)} 返回值）。
 *
 * <h2>为什么保留 Go 的原文案</h2>
 * <p>Go 的仓储返回的是普通 {@code error}，service 层只判 {@code err != nil}；
 * 真正的 HTTP 文案由 handler 另写（{@code GetDataSource} 失败一律
 * {@code 404 "data source not found"}）。所以这里的 message 不直接上线，
 * 但保持逐字一致能让"Go 里 grep 得到的字符串在 Java 里也 grep 得到"，
 * 排查时不用做翻译——与 memory 模块的 {@code MemorySubjectMissingException}
 * （message 就是 {@code "record not found"}）是同一处置。</p>
 *
 * <h2>为什么只有一个类型而不是"N 个异常类"</h2>
 * <p>Go 侧这些错误全靠字符串区分，没有任何类型层次；造一堆子类会凭空发明
 * Go 没有的语义。唯一的例外是 {@link NotFoundException}——service 层要把它
 * 单独映射成 404（Go 里是 handler 无条件映射），这个区别是真实存在的。</p>
 */
public class DataSourceException extends RuntimeException {

    public DataSourceException(String message) {
        super(message);
    }

    public DataSourceException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * "查不到这一行"。Go 侧只有两处会产生它：
     * {@code DataSourceRepository.FindByID} 的 {@code "data source not found"}
     * 与 {@code SyncLogRepository.FindByID} 的 {@code "sync log not found"}。
     *
     * <p>⚠️ {@code SyncLogRepository.FindLatest} **不抛**——它把
     * {@code gorm.ErrRecordNotFound} 吞成 {@code nil, nil}，与同文件其它读方法不同，
     * 别统一。</p>
     */
    public static class NotFoundException extends DataSourceException {
        public NotFoundException(String message) {
            super(message);
        }
    }
}
