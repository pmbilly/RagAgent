package com.ragagent.mcp.domain;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * mcp_metadata.tools（jsonb，NOT NULL）的 TypeHandler。
 *
 * <p>为什么不用通用 {@link com.ragagent.common.web.PgJsonTypeHandler}：
 * {@code List<McpTool>} 走泛型会退化成 {@code List<LinkedHashMap>}，元素类型丢失。</p>
 *
 * <p>⚠️ 落库键名＝{@link McpTool} 的 Java 属性名（{@code requireApproval} 驼峰）。
 * 存量的 {@code require_approval} 下划线行已按 SQL 迁移改写。</p>
 *
 * 刻意不加 {@code @MappedTypes(List.class)}：全局注册会污染所有 List 列。
 */
public class McpToolListTypeHandler extends BaseTypeHandler<List<McpTool>> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    false);

    private static final TypeReference<List<McpTool>> TYPE = new TypeReference<>() {};

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<McpTool> parameter, JdbcType jdbcType)
            throws SQLException {
        try {
            // PG jsonb：setObject(OTHER) 让服务端按列类型强转（H2 按 VARCHAR 落库）
            ps.setObject(i, MAPPER.writeValueAsString(parameter), java.sql.Types.OTHER);
        } catch (Exception e) {
            throw new SQLException("serialize mcp metadata tools failed", e);
        }
    }

    @Override
    public List<McpTool> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public List<McpTool> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public List<McpTool> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private List<McpTool> parse(String json) throws SQLException {
        if (json == null || json.isEmpty()) {
            return List.of();
        }
        try {
            List<McpTool> tools = MAPPER.readValue(json, TYPE);
            return tools == null ? List.of() : tools;
        } catch (Exception e) {
            throw new SQLException("parse mcp metadata tools failed", e);
        }
    }
}
