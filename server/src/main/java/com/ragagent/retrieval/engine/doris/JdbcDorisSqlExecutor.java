package com.ragagent.retrieval.engine.doris;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.ragagent.common.security.SsrfGuard;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * {@link DorisSqlExecutor} 的 JDBC 实现：MySQL 协议（mysql-connector-j）+ Hikari
 * 连接池。
 *
 * <p><b>实现说明</b>：① 地址在<b>构造期</b>
 * 用 {@link SsrfGuard} 校验一次（与 ES/OpenSearch 驱动的姿态一致——
 * 构造期一次校验，不做逐连接重校验）；② 不读时间列（只读字符串/整型/布尔/ARRAY 字面量），
 * 连接串不带时间参数；
 * ③ {@code charset=utf8mb4} 由连接器的 {@code characterEncoding=UTF-8} 承担
 * （mysql-connector-j 8+ 默认映射到 utf8mb4）；④ Hikari 的 {@code initializationFailTimeout=-1}
 * 保留惰性建连语义（池创建不拨号，首次执行才失败）。</p>
 */
public final class JdbcDorisSqlExecutor implements DorisSqlExecutor {

    /** 连接池最大连接数。 */
    static final int MAX_POOL_SIZE = 20;
    /** 最小空闲连接数。 */
    static final int MIN_IDLE = 5;
    /** 连接最大存活时长。 */
    static final long MAX_LIFETIME_MS = 3_600_000L;

    private final HikariDataSource pool;

    public JdbcDorisSqlExecutor(String addr, String database, String username, String password,
                                SsrfGuard guard) {
        if (guard != null) {
            guard.validateURLForSSRF(addr);
        }
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl(addr, database));
        cfg.setUsername(username == null ? "" : username);
        cfg.setPassword(password == null ? "" : password);
        cfg.setMaximumPoolSize(MAX_POOL_SIZE);
        cfg.setMinimumIdle(MIN_IDLE);
        cfg.setMaxLifetime(MAX_LIFETIME_MS);
        cfg.setInitializationFailTimeout(-1); // 惰性：不在池创建时拨号
        cfg.setPoolName("doris-" + addr);
        this.pool = new HikariDataSource(cfg);
    }

    /** 主链路的 JDBC URL（连接超时 10s）。 */
    static String jdbcUrl(String addr, String database) {
        return jdbcUrl(addr, database, 10_000);
    }

    /** 测试口：不建池（仅暴露 URL 拼装规则）；探针路径用 5s 连接超时。 */
    static String jdbcUrl(String addr, String database, int connectTimeoutMs) {
        return "jdbc:mysql://" + addr + "/" + (database == null ? "" : database)
                + "?characterEncoding=UTF-8&sslMode=DISABLED&allowPublicKeyRetrieval=true"
                + "&connectTimeout=" + connectTimeoutMs + "&socketTimeout=0";
    }

    @Override
    public int execute(String sql, List<Object> args) throws SQLException {
        try (Connection conn = pool.getConnection();
             PreparedStatement ps = prepare(conn, sql, args)) {
            return ps.executeUpdate();
        }
    }

    @Override
    public <T> List<T> query(String sql, List<Object> args, RowMapper<T> mapper)
            throws SQLException {
        try (Connection conn = pool.getConnection();
             PreparedStatement ps = prepare(conn, sql, args);
             ResultSet rs = ps.executeQuery()) {
            JdbcRow row = new JdbcRow(rs);
            List<T> out = new ArrayList<>();
            while (rs.next()) {
                out.add(mapper.map(row));
            }
            return out;
        }
    }

    @Override
    public Object scalar(String sql, List<Object> args) throws SQLException {
        try (Connection conn = pool.getConnection();
             PreparedStatement ps = prepare(conn, sql, args);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return null;
            }
            return rs.getObject(1);
        }
    }

    @Override
    public void close() {
        pool.close();
    }

    private static PreparedStatement prepare(Connection conn, String sql, List<Object> args)
            throws SQLException {
        PreparedStatement ps = conn.prepareStatement(sql);
        if (args != null) {
            for (int i = 0; i < args.size(); i++) {
                ps.setObject(i + 1, args.get(i));
            }
        }
        return ps;
    }

    /** {@link Row} 的 JDBC 视图：1-based 换热 + 列名按 label。 */
    private static final class JdbcRow implements Row {

        private final ResultSet rs;
        private final ResultSetMetaData meta;

        JdbcRow(ResultSet rs) throws SQLException {
            this.rs = rs;
            this.meta = rs.getMetaData();
        }

        @Override
        public int columnCount() {
            try {
                return meta.getColumnCount();
            } catch (SQLException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }

        @Override
        public String columnName(int index) throws SQLException {
            return meta.getColumnLabel(index + 1);
        }

        @Override
        public String string(int index) throws SQLException {
            return rs.getString(index + 1);
        }

        @Override
        public int intValue(int index) throws SQLException {
            return rs.getInt(index + 1);
        }

        @Override
        public double doubleValue(int index) throws SQLException {
            return rs.getDouble(index + 1);
        }

        @Override
        public boolean booleanValue(int index) throws SQLException {
            return rs.getBoolean(index + 1);
        }
    }
}
