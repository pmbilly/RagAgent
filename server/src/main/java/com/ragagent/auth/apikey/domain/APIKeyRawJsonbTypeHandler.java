package com.ragagent.auth.apikey.domain;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * 已编码好的 jsonb 文本 → 列值的写路径 TypeHandler（只用于写参数，不用于结果映射）。
 *
 * <h2>为什么需要它（与 {@link APIKeyStringListTypeHandler} 的分工）</h2>
 * <p>MyBatis 的 {@code BaseTypeHandler.setParameter} 在参数为 {@code null} 时<b>不会</b>
 * 调用 {@code setNonNullParameter}，而是走 {@code ps.setNull(i, jdbcType)} ——
 * 也就是写到 PG 的 jsonb 列上时得到的是 **SQL NULL**。而
 * {@code tenant_api_keys.knowledge_base_ids / capabilities} 是
 * {@code NOT NULL DEFAULT '[]'}，写 SQL NULL 会被 PG 拒绝。</p>
 *
 * <p>写入语义：空列表写 {@code []}，未设置（null）写**字面量
 * {@code null}**（一个合法的 jsonb 非 NULL 值）。
 * 所以写路径必须能表达"值是 json 文本 {@code null}"这件事，不能是 SQL NULL。</p>
 *
 * <p>做法：参数先用 {@link APIKeyStringListTypeHandler#encode(java.util.List)}
 * 在仓储层编码成文本（null → {@code "null"}），再经本 handler 以
 * {@code setObject(..., Types.OTHER)} 写入——PG 收到 unknown 类型后按目标列
 * jsonb 强转（{@code setString} 会被 PG 以 "column is of type jsonb but expression
 * is of type character varying" 拒绝），H2 按 VARCHAR 落库。</p>
 *
 * <p>读路径不走本 handler：结果映射用
 * {@link APIKeyStringListTypeHandler}。</p>
 */
public class APIKeyRawJsonbTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, parameter, java.sql.Types.OTHER);
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }
}
