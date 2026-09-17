package com.ragagent.common.web;

import java.sql.PreparedStatement;
import java.sql.SQLException;

import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * PostgreSQL jsonb 写入适配（对照 Go GORM jsonb 列的透明存取）。
 *
 * MyBatis-Plus 原生 JacksonTypeHandler 用 setString 写 json 串，PG 服务端会拒
 * （"column is of type jsonb but expression is of type character varying"）。
 * PG JDBC 经典解法：setObject(OTHER) → OID unknown，服务端按目标列类型 jsonb 强转。
 * H2 对 OTHER 的 setObject 按普通对象落 VARCHAR，测试库兼容。
 * 读取路径（getString + Jackson 反序列化）与原生 handler 一致。
 */
public class PgJsonTypeHandler extends JacksonTypeHandler {

    public PgJsonTypeHandler(Class<?> type) {
        super(type);
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Object parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, toJson(parameter), java.sql.Types.OTHER);
    }
}
