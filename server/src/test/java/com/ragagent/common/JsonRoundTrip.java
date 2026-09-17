package com.ragagent.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON 往返断言工具——把「领域对象的 JSON 形状必须与 Go 一致」变成自动化检查。
 *
 * <h2>为什么需要它</h2>
 * 本项目**复发率最高的两类错误**，人工 review 拦不住（阶段 3、4.1 各踩一次）：
 * <ol>
 *   <li><b>便捷方法泄漏成属性</b>：Java 的 {@code isXxx()} 会被 Jackson 当属性序列化，
 *       而 Go 里它只是个方法。典型事故：{@code McpAuthConfig.isOAuth()} 把 {@code "oauth":true}
 *       写进 auth_config 列，回读直接抛 UnrecognizedPropertyException，<b>整列不可用</b>。
 *       修法是加 {@code @JsonIgnore}——但没人保证下次记得。</li>
 *   <li><b>键名漏蛇形</b>：按 Java 习惯写 {@code apiKey}，而 Go 的 json tag 是 {@code api_key}。
 *       既接不住前端按契约发来的请求，也读不出 Go 写的行。</li>
 * </ol>
 *
 * <h2>它怎么抓</h2>
 * {@link #assertRoundTrips} 用**严格** ObjectMapper（{@code FAIL_ON_UNKNOWN_PROPERTIES=true}）
 * 做「序列化 → 反序列化 → 再序列化」：
 * <ul>
 *   <li>多写出了 Go 没有的键（漏 @JsonIgnore）→ <b>反序列化阶段直接炸</b>；</li>
 *   <li>读写的键名不对称（漏 @JsonProperty）→ 往返后 JSON 不相等；</li>
 *   <li>零值语义与 Go 不符（该恒输出的键被省略）→ 与非空值实例的对比断言暴露。</li>
 * </ul>
 *
 * <p>注意：生产路径的 ObjectMapper **要**容忍未知属性（Go 的 {@code json.Unmarshal} 默认忽略），
 * 所以这里刻意用更严的映射器——模拟的是「Java 写出的 JSON 被按契约读回」的场景，
 * 而不是复刻运行时行为。</p>
 */
public final class JsonRoundTrip {

    /** 严格映射器：多出的键即失败——这正是抓 @JsonIgnore 缺失的手段。 */
    private static final ObjectMapper STRICT = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    private JsonRoundTrip() {
    }

    /**
     * 断言序列化 → 反序列化 → 再序列化的幂等性。
     *
     * @param value 待测实例（建议填满字段，空字段会掩盖键名问题）
     * @param type  目标类型
     * @param label 失败信息里的可读标识（通常是「Go 类型名 ← Java 类名」）
     */
    public static <T> void assertRoundTrips(T value, Class<T> type, String label) {
        String first;
        try {
            first = STRICT.writeValueAsString(value);
        } catch (Exception e) {
            fail(label + ": 序列化失败: " + e.getMessage());
            return;
        }
        T back;
        try {
            back = STRICT.readValue(first, type);
        } catch (Exception e) {
            // 最常见的原因：类里有 isXxx()/getXxx() 派生方法没有 @JsonIgnore，
            // 序列化时多写了一个 Go 侧不存在的键，回读时没有对应字段。
            fail(label + ": 反序列化失败（派生方法漏 @JsonIgnore？键名不匹配？）: "
                    + e.getMessage() + "\n  序列化结果: " + first);
            return;
        }
        String second;
        try {
            second = STRICT.writeValueAsString(back);
        } catch (Exception e) {
            fail(label + ": 二次序列化失败: " + e.getMessage());
            return;
        }
        assertEquals(first, second,
                label + ": JSON 往返不幂等（键名/零值语义与 Go 不一致）");
    }

    /** 重载：整型/长整型等无参类型不便构造时的便捷入口。 */
    public static void assertRoundTrips(Object value, String label) {
        @SuppressWarnings("unchecked")
        Class<Object> type = (Class<Object>) value.getClass();
        assertRoundTrips(value, type, label);
    }

    /** 取序列化结果（供「键集合必须与 Go 一致」这类更具体的断言使用）。 */
    public static String toJson(Object value) {
        try {
            return STRICT.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("serialize failed", e);
        }
    }
}
