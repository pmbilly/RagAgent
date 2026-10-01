package com.ragagent.memory.domain;

import com.ragagent.common.web.JsonMappers;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * {@code memory_subjects.extraction_state}（jsonb）的读写处理器，
 * 对照 Go {@code MemoryExtractionState.Value()}/{@code Scan()}。
 *
 * <h2>⚠️ 零值也**不是** SQL NULL</h2>
 * <p>Go 的 {@code Value()} 是 {@code json.Marshal(s)}，永远返回一段 JSON——零值写出的是</p>
 * <pre>{"leaseId":"","leaseUntil":"0001-01-01T00:00:00Z"}</pre>
 * <p>而不是 NULL。所以这一列在 Go 里**从不**为 NULL，Java 侧也必须保持同一形态：
 * 字段默认值就是 {@code new MemoryExtractionState()}，且用
 * {@code insertStrategy/updateStrategy = ALWAYS} 保证连"看起来空"的值也落库。</p>
 *
 * <h2>读路径的 mapper</h2>
 * <p>用的是 {@code JsonMappers.lenient()}：带 {@code JavaTimeModule}（时间按 ISO-8601），
 * 且 {@code FAIL_ON_UNKNOWN_PROPERTIES=false}——后者是 Go {@code json.Unmarshal} 的默认行为，
 * 否则给 {@link MemoryExtractionState} 加字段会让历史行读不出来。这里再显式 configure 一次，
 * 是把这个前提钉在调用点上。</p>
 *
 * <p>⚠️ 宽松也有代价：改名前写下的 {@code {"lease_id":…}} 会被静默忽略成零值租约，
 * 所以改键名必须配存量迁移（HANDOFF §14.9k M2）。</p>
 */
public class MemoryExtractionStateTypeHandler extends BaseTypeHandler<MemoryExtractionState> {

    private static final ObjectMapper MAPPER = JsonMappers.lenient()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, MemoryExtractionState parameter,
                                    JdbcType jdbcType) throws SQLException {
        try {
            ps.setObject(i, MAPPER.writeValueAsString(parameter), java.sql.Types.OTHER);
        } catch (Exception e) {
            throw new SQLException("serialize memory extraction state failed", e);
        }
    }

    @Override
    public MemoryExtractionState getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public MemoryExtractionState getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public MemoryExtractionState getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    /**
     * 空列回**零值对象**而不是 {@code null}：Go 的 {@code Scan} 遇到 nil 会把接收者重置为
     * {@code MemoryExtractionState{}}（{@code *s = MemoryExtractionState{}} 在 switch 之前，
     * 且 nil 分支直接 return），调用方拿到的永远是一个可用的零值。
     */
    private static MemoryExtractionState parse(String json) throws SQLException {
        if (json == null || json.isEmpty()) {
            return new MemoryExtractionState();
        }
        try {
            MemoryExtractionState parsed = MAPPER.readValue(json, MemoryExtractionState.class);
            return parsed == null ? new MemoryExtractionState() : parsed;
        } catch (Exception e) {
            throw new SQLException("deserialize memory extraction state failed: " + e.getMessage(), e);
        }
    }
}
