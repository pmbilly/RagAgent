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
 * mcp_metadata.tools（jsonb）的 TypeHandler（对照 Go
 * `Tools []*MCPTool \`gorm:"serializer:json;type:jsonb;not null"\``）。
 *
 * <p>为什么不用通用 {@link com.ragagent.common.web.PgJsonTypeHandler}：
 * {@code List<McpTool>} 走泛型会退化成 {@code List<LinkedHashMap>}，元素类型丢失。</p>
 *
 * <p>⚠️ 跨语言字节契约：Go 侧字段 tag 是 {@code require_approval}（snake_case），
 * 而 {@link McpTool} 是 Java 属性名的驼峰 {@code requireApproval}。
 * 用 Jackson MixIn 覆盖属性名，**不修改既有 McpTool 类**，保证 Go 写入的行 Java 读得回、
 * 反之亦然。</p>
 *
 * 刻意不加 {@code @MappedTypes(List.class)}：全局注册会污染所有 List 列。
 */
public class McpToolListTypeHandler extends BaseTypeHandler<List<McpTool>> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    false);

    private static final TypeReference<List<McpTool>> TYPE = new TypeReference<>() {};

    // §14.9n M1：McpTool.requireApproval 的字段名就是落库键名，原来的
    // require_approval 别名 mixin 已退役（存量的下划线行按 HANDOFF 的 SQL 迁移）。

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
