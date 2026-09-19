package com.ragagent.vectorstore.domain;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * vector_stores.index_config jsonb 列的 TypeHandler（对照 Go IndexConfig.Value()/Scan()——
 * 无加密，仅 JSON 往返；未知键容忍）。
 */
public class IndexConfigTypeHandler extends BaseTypeHandler<IndexConfig> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, IndexConfig parameter, JdbcType jdbcType)
            throws SQLException {
        try {
            // PG jsonb 列必须 setObject(Types.OTHER)（setString 报 "column ... is of type jsonb but expression is of type character varying"，§9 阶段 2）
            ps.setObject(i, MAPPER.writeValueAsString(parameter), java.sql.Types.OTHER);
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("serialize index_config failed", e);
        }
    }

    @Override
    public IndexConfig getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public IndexConfig getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public IndexConfig getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private IndexConfig parse(String raw) throws SQLException {
        if (raw == null || raw.isEmpty()) {
            return new IndexConfig();
        }
        try {
            return MAPPER.readValue(raw, IndexConfig.class);
        } catch (Exception e) {
            throw new SQLException("deserialize index_config failed", e);
        }
    }
}
