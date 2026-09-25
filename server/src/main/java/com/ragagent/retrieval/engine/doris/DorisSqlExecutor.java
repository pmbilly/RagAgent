package com.ragagent.retrieval.engine.doris;

import java.sql.SQLException;
import java.util.List;

/**
 * Doris SQL 执行口（本仓测试接缝）——对照 Go 侧的 {@code *sql.DB}（database/sql）用法面：
 * {@code ExecContext / QueryContext / QueryRowContext.Scan}。
 *
 * <p>Go 直接对着 {@code *sql.DB} 写；Java 若直接持 JDBC 对象，SQL 文本与结果扫描路径
 * 就无法在单测里被钉住（Doris 方言 SQL 也没有本地可用的等价数据库）。这里抽一个窄口，
 * 生产实现是 {@link JdbcDorisSqlExecutor}（Hikari 池 + MySQL 协议），测试实现是记账用的
 * 假执行器——SQL 文本 / 参数序 / 扫描分支因此都能逐条断言。</p>
 *
 * <p>与 Go 的差异（备案）：Go 的 {@code ctx} 与 {@code error} 在 Java 里分别落成
 * 无 ctx（同其它店驱动）与 {@link SQLException}（受检，与 Go 的 error 语义对齐）。</p>
 */
public interface DorisSqlExecutor extends AutoCloseable {

    /** 结果行视图（下标 0-based；JDBC 的 1-based 由实现内部换算）。 */
    interface Row {

        int columnCount();

        /** 列名（JDBC 的 label）——{@code SHOW INDEX} 的列序在不同小版本有差异，按名匹配。 */
        String columnName(int index) throws SQLException;

        String string(int index) throws SQLException;

        int intValue(int index) throws SQLException;

        double doubleValue(int index) throws SQLException;

        boolean booleanValue(int index) throws SQLException;
    }

    /** 行映射器；在结果集仍打开时被调用，实现方须把需要的值拷出。 */
    interface RowMapper<T> {
        T map(Row row) throws SQLException;
    }

    /** 对照 {@code ExecContext}：返回影响行数（上游不消费）。 */
    int execute(String sql, List<Object> args) throws SQLException;

    /** 对照 {@code QueryContext}：把全部行映射成列表。 */
    <T> List<T> query(String sql, List<Object> args, RowMapper<T> mapper) throws SQLException;

    /** 对照 {@code QueryRowContext(...).Scan(&v)}：首行首列；无行返回 null。 */
    Object scalar(String sql, List<Object> args) throws SQLException;

    @Override
    void close();
}
