package com.ragagent.auth.apikey.domain;

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
 * {@code tenant_api_keys} 的两个 jsonb 字符串数组列
 * （{@code knowledge_base_ids} / {@code capabilities}）的 TypeHandler。
 *
 * <p>对照 Go {@code types.StringArray} 的 {@code driver.Valuer} / {@code sql.Scanner}
 * （internal/types/session.go L238-253）：
 * {@code Value()} 就是 {@code json.Marshal(c)}，{@code Scan()} 就是
 * {@code json.Unmarshal}（{@code value == nil} 时原地不动）。</p>
 *
 * <p><b>与 wiki 的 {@code WikiStringListTypeHandler} 的关键差异——不要照抄它</b>：
 * wiki 那些列把"空列表"也写成 SQL NULL，因为 golden 里它们恒为 NULL 且响应输出
 * {@code null}。这里的两个列在 Go 里**区分三态**，这个区别会直接体现在响应 JSON 上：</p>
 * <ul>
 *   <li>{@code nil} 切片 → {@code json.Marshal} 得字面量 {@code null} → 响应
 *       {@code "knowledge_base_ids":null}（full-access Key 建出来就是这个形态：
 *       service 显式把 {@code KnowledgeBaseIDs} / {@code Capabilities} 置 nil）；</li>
 *   <li>空切片 {@code types.StringArray{}} → {@code []} → 响应
 *       {@code "knowledge_base_ids":[]}（scoped Key 未指定白名单时的形态，
 *       repository L221 的 {@code normalizeAPIKeyIDs} 返回非 nil 空切片）；</li>
 *   <li>有值 → 数组。</li>
 * </ul>
 * 所以这里：**写路径对 null 写字面量 {@code null}（不是 SQL NULL——列是 NOT NULL），
 * 读路径把 {@code null} 读回 null、把 {@code []} 读回空列表**，三态完整往返。
 *
 * <p>写路径用 {@code setObject(..., Types.OTHER)} 让 PG 按 jsonb 列类型强转
 * （{@code setString} 会被 PG 拒绝，见 {@code PgJsonTypeHandler} 的注释）；
 * H2 按 VARCHAR 落库，测试库兼容。</p>
 */
public class APIKeyStringListTypeHandler extends BaseTypeHandler<List<String>> {

    /** §9：jsonb 回读必须容忍未知属性（Go 的 json.Unmarshal 默认忽略未知字段）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final TypeReference<List<String>> TYPE = new TypeReference<>() {};

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<String> parameter, JdbcType jdbcType)
            throws SQLException {
        try {
            ps.setObject(i, encode(parameter), java.sql.Types.OTHER);
        } catch (Exception e) {
            throw new SQLException("serialize tenant api key string array failed", e);
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
            throw new SQLException("parse tenant api key string array failed: " + json, e);
        }
    }

    /**
     * 对照 Go {@code StringArray.Value}：{@code json.Marshal} 的结果——
     * null → 字面量 {@code null}；空列表 → {@code []}；否则数组。
     */
    public static String encode(List<String> values) {
        try {
            return values == null ? "null" : MAPPER.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException("serialize tenant api key string array failed", e);
        }
    }

    /**
     * 对照 Go {@code StringArray.Scan}：SQL NULL → null（Go 在 {@code value == nil}
     * 时直接返回，指针保持 nil）；字面量 {@code null} → null；{@code []} → 空列表。
     *
     * <p>单独暴露成静态方法，便于像 Go 的 Value/Scan 往返测试那样直接驱动，
     * 不必绕过 JDBC。</p>
     */
    public static List<String> decode(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        if ("null".equals(json.trim())) {
            return null;
        }
        try {
            List<String> values = MAPPER.readValue(json, TYPE);
            if (values == null) {
                return null;
            }
            // 可变列表：调用方可能就地修改
            return new ArrayList<>(values);
        } catch (Exception e) {
            throw new IllegalArgumentException("parse tenant api key string array failed: " + json, e);
        }
    }
}
