package com.ragagent.memory.domain;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.web.JsonMappers;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * memory 模块两个「JSON 字符串数组」列的处理器：
 * {@code memory_subjects.pending_sessions} 与 {@code memory_topic_stats.aliases}。
 *
 * <p><b>为什么共用一个类</b>：两个 Go 类型的 {@code Value()}/{@code Scan()} 语义逐条相同，
 * 抄两遍只会让两处漂移。</p>
 *
 * <h2>写路径（Go {@code MemoryPendingSessions.Value} / {@code MemoryTopicAliases.Value}）</h2>
 * <pre>
 *   nil / 空    → "[]"      ← 实测确认：nil 与空切片**都**写 {@code []}，不是 SQL NULL
 *   ["a","b"]  → "[\"a\",\"b\"]"
 * </pre>
 * <p>所以这里空列表也写 {@code []}——与 wiki 那套「空列表写 SQL NULL」的处置**相反**，
 * 别套用（§9「jsonb 字符串数组列的 NULL 语义」）。</p>
 *
 * <h2>读路径（Go {@code Scan}）</h2>
 * <ul>
 *   <li>SQL NULL 或空字节 → {@code nil}（Java 侧回 {@code null}）；</li>
 *   <li>{@code []} → **非 nil 的空切片**，即 Java 的空 {@link ArrayList}
 *       （实测：{@code json.Unmarshal("[]", &slice)} 会 MakeSlice，所以不是 nil）。</li>
 * </ul>
 * <p>这条差异是真实可见的：{@code EnsureSubject} 插入后立刻重读，返回给上层的
 * {@code pending_sessions} 因此是 {@code []} 而不是 {@code null}。</p>
 *
 * <h2>回读用的 ObjectMapper</h2>
 * <p>裸 mapper 即可（纯字符串数组，无需时间模块），但必须
 * {@code FAIL_ON_UNKNOWN_PROPERTIES=false}——读路径按约定 §9 要宽容。</p>
 */
public final class MemoryStringListTypeHandler extends BaseTypeHandler<List<String>> {

    private static final TypeReference<List<String>> TYPE = new TypeReference<>() {};

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<String> parameter, JdbcType jdbcType)
            throws SQLException {
        try {
            List<String> value = parameter == null ? List.of() : parameter;
            // setObject(OTHER) 让 PG 的服务端按目标列类型 jsonb 强转（§9）；H2 落 VARCHAR。
            ps.setObject(i, MAPPER.writeValueAsString(value), java.sql.Types.OTHER);
        } catch (Exception e) {
            throw new SQLException("serialize memory string list failed", e);
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

    private static List<String> parse(String json) throws SQLException {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            List<String> parsed = MAPPER.readValue(json, TYPE);
            return parsed == null ? null : new ArrayList<>(parsed);
        } catch (Exception e) {
            throw new SQLException("deserialize memory string list failed: " + e.getMessage(), e);
        }
    }
}
