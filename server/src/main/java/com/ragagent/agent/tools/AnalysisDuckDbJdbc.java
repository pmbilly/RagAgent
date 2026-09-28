package com.ragagent.agent.tools;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link DataAnalysisTool.AnalysisDuckDb} 的生产实现（对照 Go 侧传入
 * NewDataAnalysisTool 的 {@code *sql.DB} 交互段）。
 *
 * <p>共享一条进程内 DuckDB 内存连接（{@code jdbc:duckdb:}）：表名由
 * {@code tableName(knowledge)} 按知识 ID 命名空间（{@code k_<id>}），多会话/多回合
 * 复用同一连接与 Go 的单例 {@code *sql.DB} 同形；会话收尾由 {@link Cleanable}
 * （DataAnalysisTool.cleanup）按表 DROP。exec/query 在连接上串行——DuckDB 单连接
 * 不保证并发语句安全。</p>
 *
 * <p>依赖 {@code org.duckdb:duckdb_jdbc}（build.gradle 已引入）。{@code read_xlsx}
 * /{@code st_read_meta} 属 DuckDB 扩展（excel/spatial），运行期自动安装失败时
 * Excel 路径报运行时错误、sheet 枚举回落首 sheet——与 Go 环境的失败形态一致；
 * CSV 路径（read_csv_auto 为内置）不依赖扩展。</p>
 */
public final class AnalysisDuckDbJdbc implements DataAnalysisTool.AnalysisDuckDb {

    private static final AnalysisDuckDbJdbc INSTANCE = new AnalysisDuckDbJdbc();

    public static AnalysisDuckDbJdbc get() {
        return INSTANCE;
    }

    private AnalysisDuckDbJdbc() {
    }

    private volatile java.sql.Connection conn;

    private java.sql.Connection connection() {
        java.sql.Connection c = conn;
        if (c != null) {
            return c;
        }
        synchronized (this) {
            if (conn == null) {
                try {
                    conn = java.sql.DriverManager.getConnection("jdbc:duckdb:");
                } catch (SQLException e) {
                    throw new RuntimeException("failed to open DuckDB connection: " + e.getMessage(), e);
                }
            }
            return conn;
        }
    }

    @Override
    public void exec(String sql) {
        synchronized (this) {
            try (Statement st = connection().createStatement()) {
                st.execute(sql);
            } catch (SQLException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }
    }

    @Override
    public DataAnalysisTool.QueryResult query(String sql) {
        synchronized (this) {
            try (Statement st = connection().createStatement(); ResultSet rs = st.executeQuery(sql)) {
                int n = rs.getMetaData().getColumnCount();
                List<String> columns = new ArrayList<>(n);
                for (int i = 1; i <= n; i++) {
                    columns.add(rs.getMetaData().getColumnLabel(i));
                }
                List<List<Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    List<Object> row = new ArrayList<>(n);
                    for (int i = 1; i <= n; i++) {
                        row.add(rs.getObject(i));
                    }
                    rows.add(row);
                }
                return new DataAnalysisTool.QueryResult(columns, rows);
            } catch (SQLException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }
    }

    @Override
    public List<String> listSheets(String xlsxPath) {
        // 对照 Go listExcelSheets：st_read_meta（spatial 扩展）枚举 sheet。扩展未装/
        // 列形态不符时抛 RuntimeException——调用点（loadFromExcel）回落首 sheet，
        // 与 Go 的 err 路径同形。
        synchronized (this) {
            String path = xlsxPath == null ? "" : xlsxPath.replace("'", "''");
            try (Statement st = connection().createStatement();
                 ResultSet rs = st.executeQuery("SELECT layer_name FROM st_read_meta('" + path + "')")) {
                List<String> out = new ArrayList<>();
                while (rs.next()) {
                    String s = rs.getString(1);
                    if (s != null && !s.isEmpty()) {
                        out.add(s);
                    }
                }
                return out;
            } catch (SQLException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }
    }
}
