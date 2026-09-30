package com.ragagent.common.web;

import java.sql.PreparedStatement;
import java.sql.SQLException;

import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * PostgreSQL jsonb 写入适配（对照 Go GORM jsonb 列的透明存取）。
 *
 * MyBatis-Plus 原生 JacksonTypeHandler 用 setString 写 json 串，PG 服务端会拒
 * （"column is of type jsonb but expression is of type character varying"）。
 * PG JDBC 经典解法：setObject(OTHER) → OID unknown，服务端按目标列类型 jsonb 强转。
 * H2 对 OTHER 的 setObject 按普通对象落 VARCHAR，测试库兼容。
 * 读取路径（getString + Jackson 反序列化）与原生 handler 一致。
 */
public class PgJsonTypeHandler extends JacksonTypeHandler {

    private final Class<?> targetType;

    public PgJsonTypeHandler(Class<?> type) {
        super(type);
        this.targetType = type;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Object parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, toJson(parameter), java.sql.Types.OTHER);
    }

    /** 落库 JSON 可能含旧键/未知键 → 统一宽松读（见 JsonMappers）。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper READER =
            JsonMappers.lenient();

    static {
        // 写路径（父类 toJson）默认用 MyBatis-Plus 内部裸 mapper——未注册 JSR-310，
        // 落 java.time 字段会抛 InvalidDefinitionException。用官方钩子换成本仓工厂，
        // 使 jsonb 读写两侧同源（时间按 ISO-8601 字符串落库，不再依赖逐字段注解）。
        JacksonTypeHandler.setObjectMapper(READER);
    }

    /**
     * 读路径 jsonb 规范化：PG jsonb 不保留键序，对象键按（长度, 字节序）排序
     * （H2 的 VARCHAR 原样返回插入串，这里模拟 jsonb 行为使两侧读回一致；
     * 仅 JsonNode 目标需要——值类型配置反序列化后键序本就丢失）。
     */
    /** 3.5.7 中 parse 为 public */
    @Override
    public Object parse(String json) {
        try {
            if (!com.fasterxml.jackson.databind.JsonNode.class.isAssignableFrom(targetType)) {
                return READER.readValue(json, targetType);
            }
            return canonicalize(READER.readTree(json));
        } catch (java.io.IOException e) {
            throw new RuntimeException("failed to parse jsonb column: " + e.getMessage(), e);
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode canonicalize(
            com.fasterxml.jackson.databind.JsonNode node) {
        if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode obj) {
            var out = READER.createObjectNode();
            var names = new java.util.ArrayList<String>();
            obj.fieldNames().forEachRemaining(names::add);
            names.sort(java.util.Comparator
                    .comparingInt(String::length)
                    .thenComparing(java.util.Comparator.naturalOrder()));
            for (String name : names) {
                out.set(name, canonicalize(obj.get(name)));
            }
            return out;
        }
        if (node instanceof com.fasterxml.jackson.databind.node.ArrayNode arr) {
            var out = READER.createArrayNode();
            for (var item : arr) {
                out.add(canonicalize(item));
            }
            return out;
        }
        return node;
    }
}
