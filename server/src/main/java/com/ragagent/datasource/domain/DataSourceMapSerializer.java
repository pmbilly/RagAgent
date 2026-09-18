package com.ragagent.datasource.domain;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializable;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.ragagent.common.web.GoDoubleSerializer;
import com.ragagent.common.web.GoMapSerializer;

/**
 * {@link GoMapSerializer} 的 datasource 版本：在"按键排序且递归"之上，**再把嵌套的
 * {@code Double} 按 Go 的浮点编码器输出**。
 *
 * <h2>为什么不能直接用 {@code GoMapSerializer}</h2>
 * <p>{@code GoMapSerializer} 只重排键序，值<b>原样</b>交给 Jackson。而本模块的 map 字段
 * （{@code DataSourceConfig.settings} / {@code Resource.metadata} /
 * {@code SyncCursor.connector_cursor}）装的是**外部系统给的任意 JSON**，里面必然有数字：
 * 分页偏移、条目上限、文档大小。两边的表示不同：</p>
 * <pre>
 *   Go    float64(1.0)   →  1                （专用编码器，整数值不补 .0）
 *   Java  Double(1.0)    →  1.0              （Jackson 走 Double.toString）
 *   Go    float64(1e21)  →  1e+21
 *   Java  Double(1e21)   →  1.0E21
 * </pre>
 * <p>{@code Resource.metadata} 是<b>响应体</b>、{@code connector_cursor} 会经
 * {@code last_sync_result} 原样透给前端——分叉看得见。</p>
 *
 * <h2>为什么不改 {@code GoMapSerializer} 本身</h2>
 * <p>它是阶段 5.2 建立起来、被 MCP / Wiki / stream / llm 共同依赖的<b>共享</b>基础设施，
 * 本轮只授权改 {@code TestSchema}。故在此处收口：本类继承它、复用它的键序比较器
 * 与 {@code sortDeep}，只补一层"值归一"。</p>
 *
 * <h2>归一的手段</h2>
 * <p>把 {@code Double}/{@code Float} 换成实现了 {@link JsonSerializable} 的
 * {@link GoNumber}。{@code sortDeep} 对非 Map/Collection 的值原样透传，而 Jackson 对
 * 实现 {@code JsonSerializable} 的对象会调它自己的 {@code serialize}——
 * 于是嵌套在任意深度的数字都能走 Go 的编码器。用 {@code writeRawValue} 而不是
 * {@code writeNumber} 是因为 Go 的 {@code 1e+21} 不是合法的 Java 数字字面量写法。</p>
 */
public class DataSourceMapSerializer extends GoMapSerializer {

    @Override
    public void serialize(Map<String, Object> value, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        // 先归一（Double → GoNumber），再交给父类的 sortDeep 排序。
        // 顺序不能反：sortDeep 会把 map 换成 TreeMap，之后仍然要递归一遍值。
        gen.writeObject(sortDeep(normalize(value)));
    }

    /** 递归把数字包成 Go 形态；map / 集合的容器结构原样保留（排序交给 {@code sortDeep}）。 */
    private static Object normalize(Object value) {
        if (value instanceof Double d) {
            return new GoNumber(GoDoubleSerializer.format(d));
        }
        if (value instanceof Float f) {
            return new GoNumber(GoDoubleSerializer.format(f.doubleValue()));
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), normalize(entry.getValue()));
            }
            return out;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> out = new java.util.ArrayList<>(collection.size());
            for (Object item : collection) {
                out.add(normalize(item));
            }
            return out;
        }
        return value;
    }

    /**
     * 一个"已经格式化好"的 JSON 数字。
     *
     * <p>{@code JsonSerializable} 是 Jackson 自带的钩子：实现它的对象无论出现在
     * 多深的位置都会被调 {@link #serialize}，不需要额外注册。</p>
     */
    static final class GoNumber implements JsonSerializable {

        private final String text;

        GoNumber(String text) {
            this.text = text;
        }

        /** 供测试直接比对格式（不经过 Jackson）。 */
        public String text() {
            return text;
        }

        @Override
        public void serialize(JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeRawValue(text);
        }

        @Override
        public void serializeWithType(JsonGenerator gen, SerializerProvider serializers,
                                      com.fasterxml.jackson.databind.jsontype.TypeSerializer typeSer)
                throws IOException {
            serialize(gen, serializers);
        }
    }
}
