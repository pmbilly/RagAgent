package com.ragagent.common.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 数据库方言判定。
 *
 * <p>不少仓储在 SQL 里对 PostgreSQL 与其它库走不同分支（如 {@code ->>} JSON 取值、
 * {@code ON CONFLICT}、位运算写法）。此前每个类各自写一遍"连一次库、取
 * {@code DatabaseMetaData.getDatabaseProductName()}、判断是否 postgres"的探测逻辑，
 * 全仓重复了十几处。本类把它收敛成一处：判定时机与语义不变（构造期探测一次），
 * 读代码时也不用再逐个实现去确认"它到底怎么判的"。</p>
 */
public final class DatabaseDialects {

    private DatabaseDialects() {
    }

    /**
     * 当前数据源是否 PostgreSQL。
     *
     * @return 探测失败（连不上 / 元数据异常）时返回 {@code false}——与非 PG 分支同语义
     */
    public static boolean isPostgres(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase(Locale.ROOT).contains("postgres");
        } catch (SQLException e) {
            return false;
        }
    }

    /** 同 {@link #isPostgres(DataSource)}，便于已持有 {@link JdbcTemplate} 的调用方直接使用。 */
    public static boolean isPostgres(JdbcTemplate jdbcTemplate) {
        return isPostgres(jdbcTemplate.getDataSource());
    }
}
