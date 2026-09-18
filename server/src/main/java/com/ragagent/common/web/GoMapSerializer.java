package com.ragagent.common.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

/**
 * 让 {@code Map} 的输出键序与 Go 的 {@code encoding/json} 一致：<b>按键排序，且递归到嵌套的 map 与数组</b>。
 *
 * <h2>为什么需要它</h2>
 * <p>Go 的 {@code json.Marshal} 对 {@code map} 恒按 key 排序（{@code struct} 才按声明序）。
 * 约定 §9 已记录这条，项目此前的做法是「手工按字母序 {@code put}」——
 * 对<b>一层</b>的 map 有效，对<b>从外部解析出来的嵌套 map</b> 无效：
 * 例如 {@code tool_call.data.arguments} 是模型返回的 JSON 参数，其键序由模型决定，
 * 代码里再怎么排外层也管不到它。</p>
 *
 * <p>挂在字段上的本序列化器把整棵子树按其键序重排，一次覆盖所有层级与所有产出方，
 * 因此也让上层的「手工排序」退化成无害的防御性写法。</p>
 *
 * <h2>键序：按 UTF-8 字节，不是 Java 的 {@code String.compareTo}</h2>
 * <p>Go 的字符串比较是<b>逐字节</b>（UTF-8）。Java 的 {@code String.compareTo} 比的是
 * UTF-16 code unit，两者只在「BMP 的 U+E000–U+FFFF」与「增补平面（U+10000 起）」
 * 混排时不同。JSON 键基本都是 ASCII，但既然要做到逐字节一致，就用
 * {@link #GO_KEY_ORDER} 直接比 UTF-8 字节——ASCII 下与自然序完全相同。</p>
 *
 * <h2>使用方式与边界</h2>
 * <ul>
 *   <li>挂在 {@code Map} 型字段/getter 上：{@code @JsonSerialize(using = GoMapSerializer.class)}。</li>
 *   <li>不会与自身的嵌套调用递归打架：内部走 {@code gen.writeObject}，
 *       那时选中的是按<b>运行时类型</b>（{@link TreeMap}）查到的普通 Map 序列化器，
 *       而本序列化器绑在<b>属性</b>上。</li>
 *   <li>只影响 {@code java.util.Map}。{@code ObjectNode}（jsonb 回读路径）有自己既定的
 *       键序约定（PG 的 jsonb 规范化序），<b>不要</b>套到这里来——那会打破阶段 3 的 golden。</li>
 * </ul>
 */
public class GoMapSerializer extends JsonSerializer<Map<String, Object>> {

    /**
     * Go 的字符串序：逐 UTF-8 字节比较，短者在前。
     */
    public static final Comparator<String> GO_KEY_ORDER = (a, b) -> {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        int shared = Math.min(x.length, y.length);
        for (int i = 0; i < shared; i++) {
            int diff = (x[i] & 0xff) - (y[i] & 0xff);
            if (diff != 0) {
                return diff;
            }
        }
        return x.length - y.length;
    };

    /**
     * 让「空 map 被 {@code @JsonInclude(NON_EMPTY)} 省略」继续生效。
     *
     * <p>踩坑：{@code JsonSerializer.isEmpty} 的默认实现<b>只看 {@code value == null}</b>——
     * 一旦字段挂上自定义序列化器，Jackson 就不再调用 {@code MapSerializer.isEmpty} 去判"空容器"，
     * 于是 Go 的 {@code omitempty}（len==0 省略）会退化成"空 map 也输出 {@code {}}"。
     * 由 {@code StreamResponseBuilderTest.omitsEmptyDataMap} 钉住。</p>
     */
    @Override
    public boolean isEmpty(SerializerProvider provider, Map<String, Object> value) {
        return value == null || value.isEmpty();
    }

    @Override
    public void serialize(Map<String, Object> value, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        gen.writeObject(sortDeep(value));
    }

    /**
     * 递归重排：{@code Map} → 按键序的 {@link TreeMap}，集合 → 逐元素重排的列表，其余原样。
     *
     * <p>集合必须逐元素走一遍：Go 排序的是 map 本身，而 map 可以出现在数组里
     * （例如 {@code references: [...]} 每个元素的 {@code metadata}）。</p>
     */
    public static Object sortDeep(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>(GO_KEY_ORDER);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                // Go 的 map[string]X 里键必然是字符串；非字符串键在 Go 侧就构造不出来。
                sorted.put(String.valueOf(entry.getKey()), sortDeep(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> out = new ArrayList<>(collection.size());
            for (Object item : collection) {
                out.add(sortDeep(item));
            }
            return out;
        }
        return value;
    }
}
