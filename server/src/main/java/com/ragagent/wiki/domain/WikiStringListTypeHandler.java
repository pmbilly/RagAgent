package com.ragagent.wiki.domain;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * wiki 各表的 {@code jsonb} 字符串数组列（category_path / source_refs / chunk_refs /
 * in_links / out_links / aliases / suspected_knowledge_ids）的 TypeHandler。
 *
 * <p><b>为什么不用通用 {@link com.ragagent.common.web.PgJsonTypeHandler}</b>：
 * {@code List<String>} 走泛型擦除后是 {@code List.class}，Jackson 会反序列化成
 * {@code List<LinkedHashMap>} / {@code List<Object>}，元素类型丢失。</p>
 *
 * <p><b>写路径约定</b>：**空列表也写成 SQL NULL**——出口契约里这些列都是
 * NULL（响应输出 {@code "aliases":null}），而 Java 的实体无法区分"未设置"与"显式空"，
 * 统一按 NULL 落库。</p>
 *
 * <p>代价：查询侧不能再依赖 {@code in_links = '[]'::JSONB}（对 NULL 不成立），
 * 需要用 {@code COALESCE(in_links, '[]'::jsonb)}。仓储层已按此写法处理。</p>
 *
 * <p>读路径宽容：SQL NULL / 空串 / JSON {@code null} 一律回空列表；
 * 序列化时空列表再转回 {@code null}（见 {@link EmptyListAsNullSerializer}），
 * 与写路径约定闭环。</p>
 */
public class WikiStringListTypeHandler extends BaseTypeHandler<List<String>> {

    /** jsonb 回读必须容忍未知属性 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final TypeReference<List<String>> TYPE = new TypeReference<>() {};

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<String> parameter, JdbcType jdbcType)
            throws SQLException {
        if (parameter == null || parameter.isEmpty()) {
            // 空列表 → SQL NULL（见类注释）
            ps.setNull(i, java.sql.Types.OTHER);
            return;
        }
        try {
            // PG jsonb：setObject(OTHER) 让服务端按列类型强转（H2 按 VARCHAR 落库）
            ps.setObject(i, MAPPER.writeValueAsString(parameter), java.sql.Types.OTHER);
        } catch (Exception e) {
            throw new SQLException("serialize wiki string array failed", e);
        }
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public List<String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private List<String> parse(String json) throws SQLException {
        try {
            return decode(json);
        } catch (Exception e) {
            throw new SQLException("parse wiki string array failed: " + json, e);
        }
    }

    /**
     * SQL NULL / 空串 / JSON {@code null} 一律回空列表；
     * 元素为 null 的脏数据归一成空串。
     *
     * <p>单独暴露成静态方法是为了让单测能直接驱动写/读往返，
     * 不必绕过 JDBC。</p>
     */
    public static List<String> decode(String json) {
        // 必须返回**可变**列表：调用方（如 updateInLinks）会就地 add，
        // List.of() 会抛 UnsupportedOperationException
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            List<String> values = MAPPER.readValue(json, TYPE);
            if (values == null) {
                return new ArrayList<>();
            }
            List<String> out = new ArrayList<>(values.size());
            for (String v : values) {
                out.add(v == null ? "" : v);
            }
            return out;
        } catch (Exception e) {
            throw new IllegalArgumentException("parse wiki string array failed: " + json, e);
        }
    }

    /**
     * null / 空列表 → 字面量 {@code "null"}
     * （与写路径一致，见类注释）。
     */
    public static String encode(List<String> values) {
        try {
            return values == null || values.isEmpty()
                    ? "null"
                    : MAPPER.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException("serialize wiki string array failed", e);
        }
    }
}
