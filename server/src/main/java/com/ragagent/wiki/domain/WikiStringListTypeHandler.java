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
 * <p>对照 Go {@code types.StringArray}（internal/types/session.go L237-253）的
 * {@code driver.Valuer}/{@code sql.Scanner} 实现。</p>
 *
 * <p><b>为什么不用通用 {@link com.ragagent.common.web.PgJsonTypeHandler}</b>：
 * {@code List<String>} 走泛型擦除后是 {@code List.class}，Jackson 会反序列化成
 * {@code List<LinkedHashMap>} / {@code List<Object>}，元素类型丢失。</p>
 *
 * <p><b>与 Go 的一处刻意差异（保真说明）</b>：Go 的 {@code Value()} 对 nil 切片
 * {@code json.Marshal} 出字面量 {@code null}；本 handler 对 null/空列表统一写
 * {@code []}（= 各迁移里这些列的 DEFAULT）。原因有两条：</p>
 * <ol>
 *   <li>{@code wiki_pages.category_path} 等的列默认值就是 {@code '[]'::JSONB}，
 *       写 {@code []} 与"GORM 省略零值让 DB 默认值兜底"的结果一致；</li>
 *   <li>Go 的 PG 语义下 {@code in_links = '[]'::JSONB} 对 jsonb 字面量 {@code null}
 *       不成立（{@code CountOrphans} 会漏数），写 {@code []} 消除这个方言陷阱，
 *       且与 SQLite 分支（{@code json_array_length(in_links) = 0}）的判定一致。</li>
 * </ol>
 *
 * <p>读路径宽容：SQL NULL、空串、JSON {@code null} 一律回空列表。</p>
 */
public class WikiStringListTypeHandler extends BaseTypeHandler<List<String>> {

    /** §9：jsonb 回读必须容忍未知属性（Go 的 json.Unmarshal 默认忽略未知字段） */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final TypeReference<List<String>> TYPE = new TypeReference<>() {};

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<String> parameter, JdbcType jdbcType)
            throws SQLException {
        List<String> value = parameter == null ? List.of() : parameter;
        try {
            // PG jsonb：setObject(OTHER) 让服务端按列类型强转（H2 按 VARCHAR 落库）
            ps.setObject(i, MAPPER.writeValueAsString(value), java.sql.Types.OTHER);
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
     * 对照 Go StringArray.Scan：SQL NULL / 空串 / JSON {@code null} 一律回空列表；
     * 元素为 null 的脏数据归一成空串。
     *
     * <p>单独暴露成静态方法是为了让单测能像 Go 的 Value/Scan 往返测试那样直接驱动它，
     * 不必绕过 JDBC。</p>
     */
    public static List<String> decode(String json) {
        if (json == null || json.isEmpty()) {
            return List.of();
        }
        try {
            List<String> values = MAPPER.readValue(json, TYPE);
            if (values == null) {
                return List.of();
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
     * 对照 Go StringArray.Value：序列化成紧凑 JSON 数组（见类注释：nil/空列表统一写
     * {@code []} 而非 Go 的字面量 {@code null}）。
     */
    public static String encode(List<String> values) {
        try {
            return MAPPER.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            throw new IllegalStateException("serialize wiki string array failed", e);
        }
    }
}
