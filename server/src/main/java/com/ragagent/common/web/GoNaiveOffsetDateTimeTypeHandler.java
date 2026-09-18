package com.ragagent.common.web;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/**
 * naive 时间列（TIMESTAMP WITHOUT TIME ZONE）的 OffsetDateTime 处理器。
 *
 * <p>写入：取原 offset 的墙钟时间存 naive（Go/pgx 语义——time.Time 写 naive 列
 * 保留本地墙钟）；读取：naive 值按 **UTC** 解释（pgx 扫描语义），序列化后是
 * {@code …Z} 形态。created_at 这类 DB 默认值（PG 服务器 UTC）与 Go 侧
 * time.Now()（本地墙钟）因此分别呈现 11:50Z / 19:50Z——golden 实测形态。</p>
 */
@MappedTypes(OffsetDateTime.class)
public class GoNaiveOffsetDateTimeTypeHandler extends BaseTypeHandler<OffsetDateTime> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, OffsetDateTime value,
            JdbcType jdbcType) throws SQLException {
        ps.setObject(i, value.toLocalDateTime());
    }

    @Override
    public OffsetDateTime getNullableResult(ResultSet rs, String columnName) throws SQLException {
        LocalDateTime local = rs.getObject(columnName, LocalDateTime.class);
        return local == null ? null : local.atOffset(ZoneOffset.UTC);
    }

    @Override
    public OffsetDateTime getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        LocalDateTime local = rs.getObject(columnIndex, LocalDateTime.class);
        return local == null ? null : local.atOffset(ZoneOffset.UTC);
    }

    @Override
    public OffsetDateTime getNullableResult(CallableStatement cs, int columnIndex)
            throws SQLException {
        LocalDateTime local = cs.getObject(columnIndex, LocalDateTime.class);
        return local == null ? null : local.atOffset(ZoneOffset.UTC);
    }
}
