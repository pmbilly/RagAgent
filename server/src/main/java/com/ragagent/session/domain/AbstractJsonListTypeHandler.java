package com.ragagent.session.domain;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * 元素类型已知的 JSON 数组列处理器基类。
 *
 * <p><b>为什么不能直接用通用的 {@code PgJsonTypeHandler}</b>：{@code List<MessageImage>}
 * 泛型擦除后只剩 {@code List.class}，Jackson 会反序列化成 {@code List<LinkedHashMap>}，
 * 元素类型丢失——读回来一取元素就 {@code ClassCastException}。
 * 这与 wiki / apikey / mcp 各自手写 List 处理器的原因是同一个，只是这里用基类收口，
 * 子类只提供 {@link #typeReference()}。</p>
 *
 * <p><b>写路径对齐 Go</b>：Go 这几个类型的 {@code Value()} 对 nil 切片返回
 * {@code []}（唯独 {@code References} 例外，它直接 {@code json.Marshal} 一个 nil 切片
 * 会得到 JSON {@code null}）。所以这里空列表也写成 {@code []}——与 wiki 那边
 * 「空列表写 SQL NULL」的处置**相反**，别套用。</p>
 *
 * <p><b>读路径宽容</b>：SQL NULL 与 JSON {@code null} 都回 {@code null}
 * （响应侧：无 omitempty 的字段输出 {@code null}，有 omitempty 的直接省略）；
 * 空数组回空列表。</p>
 */
public abstract class AbstractJsonListTypeHandler<T> extends BaseTypeHandler<List<T>> {

    /**
     * §9：jsonb 回读必须容忍未知属性（Go 的 json.Unmarshal 默认忽略未知字段）。
     * ⚠️ 必须挂 JavaTimeModule：元素类型（如 MessageArtifact）带 OffsetDateTime 字段
     * （mod_time/created_at），缺模块时读回即抛 "Java 8 date/time type not supported"
     * 让整列不可用（G6 契约测试抓到的真缺陷）。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    protected abstract TypeReference<List<T>> typeReference();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<T> parameter, JdbcType jdbcType)
            throws SQLException {
        try {
            // 空列表写 []（不是 NULL）——见类注释
            List<T> value = parameter == null ? List.of() : parameter;
            ps.setObject(i, MAPPER.writeValueAsString(value), java.sql.Types.OTHER);
        } catch (Exception e) {
            throw new SQLException("serialize json list failed", e);
        }
    }

    @Override
    public List<T> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public List<T> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public List<T> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private List<T> parse(String json) throws SQLException {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, typeReference());
        } catch (Exception e) {
            throw new SQLException("deserialize json list failed: " + e.getMessage(), e);
        }
    }
}
