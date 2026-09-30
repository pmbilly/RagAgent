package com.ragagent.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * **编码器级**的差分排查：把 Go {@code encoding/json} 的每一类输出与容器里的 mapper 对表。
 *
 * <h2>为什么单独做这一份</h2>
 * <p>{@link GoJsonEscapesContractTest} 只覆盖了「HTML 转义」这一类——而那一类被发现，
 * 是因为聊天正文碰巧踩到了。其余类别**没有任何 golden 覆盖**，是同一类潜伏风险：
 * 只要某个响应恰好在某个字段里出现某种字符或数值，就会分叉，而且没人会预料到。</p>
 *
 * <p>所以这里不靠推理：读 Go encoder 的源码定出**类别**（转义分支、浮点编码器、整数、容器），
 * 为每类构造语料，把 Go 的真实输出录下来，再拿**容器里那个 mapper** 对表。</p>
 *
 * <h2>期望值来源</h2>
 * <p>Go 实录——{@code json.Marshal(struct{V any `json:"v"`}{v})} 的输出。
 * 语料用结构体包一层，与线上同形。</p>
 *
 * <h2>本测试<b>不</b>覆盖的两类（逐字段的事，不是 mapper 配置能解的）</h2>
 * <ul>
 *   <li><b>map 键序</b>：Go 对 map 恒按字节序排，Jackson 不排。项目靠
 *       {@link GoMapSerializer} 逐字段处理。</li>
 *   <li><b>double 的值格式</b>：{@link GoDoubleSerializer} 同理逐字段挂。
 *       **刻意不做全局注册**——那会连带改掉发给 LLM provider 的请求体
 *       （{@code ChatOptions} 里的 temperature 等），收益为零、风险实在。</li>
 * </ul>
 */
@SpringBootTest
class GoJsonEncodingContractTest {

    /** 与 Go 的 {@code struct{V any `json:"v"`}} 同形。 */
    public static final class Box {
        @JsonProperty("v")
        public Object v;

        public Box() {
        }

        Box(Object v) {
            this.v = v;
        }
    }

    /** 挂了 {@link GoDoubleSerializer} 的对照物。 */
    public static final class AnnotatedBox {
        @JsonProperty("v")
        public double v;

        AnnotatedBox(double v) {
            this.v = v;
        }
    }

    @Autowired
    private ObjectMapper mapper;

    private String jsonOf(Object value) throws Exception {
        return mapper.writeValueAsString(new Box(value));
    }

    // ── 字符串的转义分支（Go 实录） ────────────────────────────────────────

    static Stream<Arguments> goStringCorpus() {
        return Stream.of(
                Arguments.of("ascii", "plain", "{\"v\":\"plain\"}"),
                Arguments.of("quote-backslash", "q\"b\\s", "{\"v\":\"q\\\"b\\\\s\"}"),
                Arguments.of("short-escapes", "a\tb\nc\rd\be\ff",
                        "{\"v\":\"a\\tb\\nc\\rd\\be\\ff\"}"),
                // 控制字符用四位**小写**十六进制转义；0x08 走短转义
                Arguments.of("ctrl-00-1f", "\u0000\u0001\b\u000b\u001f",
                        "{\"v\":\"\\u0000\\u0001\\b\\u000b\\u001f\"}"),
                // HTML 敏感字符（全局转义表负责）
                Arguments.of("html-sensitive", "< > &", "{\"v\":\"\\u003c \\u003e \\u0026\"}"),
                // 下面这些 Go **不**转义，必须原样输出
                Arguments.of("del-7f", "", "{\"v\":\"\"}"),
                Arguments.of("u0085-nel", "\u0085", "{\"v\":\"\u0085\"}"),
                Arguments.of("u00a0-nbsp", "\u00a0", "{\"v\":\"\u00a0\"}"),
                Arguments.of("ufeff-bom", "\ufeff", "{\"v\":\"\ufeff\"}"),
                Arguments.of("cn-emoji", "中😀", "{\"v\":\"中😀\"}"),
                Arguments.of("replacement", "\ufffd", "{\"v\":\"\ufffd\"}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("goStringCorpus")
    void stringsMatchGo(String label, String value, String expected) throws Exception {
        assertThat(jsonOf(value)).as(label).isEqualTo(expected);
    }

    /**
     * ⚠️ <b>已知且刻意保留的差异</b>：U+2028 / U+2029 在 Go 里会被转义
     * （{@code "a\u2028b"}，目的是让 JSON 能直接当 JavaScript 求值），
     * 而 Jackson 的 {@code CharacterEscapes} **够不到非 ASCII**——
     * 生成器对 {@code > 0x7F} 的字符走另一条路径，压根不查转义表。
     *
     * <p>要复刻只能给 {@code String} 注册一个全局自定义序列化器，
     * 或者开 {@code ESCAPE_NON_ASCII}（那会把中文也一起转义，反而更远）。
     * 这两个字符出现在响应正文里的概率极低，且对解析 JSON 的客户端不可见——
     * 权衡后保留差异，并在此**显式钉住当前行为**，免得将来有人以为它对齐了。</p>
     */
    @Test
    void u2028AndU2029AreAKnownDeliberateDifference() throws Exception {
        // Go 实录分别是 {"v":"a\u2028b"} / {"v":"a\u2029b"}（含转义序列）
        assertThat(jsonOf("a\u2028b")).isEqualTo("{\"v\":\"a\u2028b\"}");
        assertThat(jsonOf("a\u2029b")).isEqualTo("{\"v\":\"a\u2029b\"}");
    }

    // ── 数值（Go 实录） ────────────────────────────────────────────────────

    static Stream<Arguments> goNumberCorpus() {
        return Stream.of(
                Arguments.of("0", "{\"v\":0}"),
                Arguments.of("1", "{\"v\":1}"),
                Arguments.of("neg-1", "{\"v\":-1}"),
                Arguments.of("half", "{\"v\":0.5}"),
                Arguments.of("pi", "{\"v\":3.141592653589793}"),
                Arguments.of("1e20", "{\"v\":100000000000000000000}"),
                Arguments.of("1e21", "{\"v\":1e+21}"),
                Arguments.of("1e-6", "{\"v\":0.000001}"),
                Arguments.of("1e-7", "{\"v\":1e-7}"),
                Arguments.of("max", "{\"v\":1.7976931348623157e+308}"),
                Arguments.of("min-subnormal", "{\"v\":5e-324}"),
                Arguments.of("int64-max", "{\"v\":9223372036854775807}"),
                Arguments.of("uint64-max", "{\"v\":18446744073709551615}"));
    }

    /** 这些值经 {@link GoDoubleSerializer} 之后必须与 Go 一致。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("goNumberCorpus")
    void doubleSerializerMatchesGo(String label, String expected) throws Exception {
        Object value = switch (label) {
            case "0" -> 0.0;
            case "1" -> 1.0;
            case "neg-1" -> -1.0;
            case "half" -> 0.5;
            case "pi" -> 3.141592653589793;
            case "1e20" -> 1e20;
            case "1e21" -> 1e21;
            case "1e-6" -> 1e-6;
            case "1e-7" -> 1e-7;
            case "max" -> Double.MAX_VALUE;
            case "min-subnormal" -> Double.MIN_VALUE;
            case "int64-max" -> Long.MAX_VALUE;
            case "uint64-max" -> new BigInteger("18446744073709551615");
            default -> throw new IllegalStateException(label);
        };
        if (value instanceof Double d) {
            assertThat(mapper.writeValueAsString(new AnnotatedBox(d))).as(label).isEqualTo(expected);
        } else {
            assertThat(jsonOf(value)).as(label).isEqualTo(expected);
        }
    }

    /**
     * ⚠️ <b>钉的是"裸 double 与 Go 不一致"这个事实，而不是它的修复</b>。
     *
     * <p>Go 的 {@code float64} 走专用编码器（整数值不补 {@code .0}），Jackson 走
     * {@code Double.toString}。所以线上**凡 double 字段都必须挂 {@link GoDoubleSerializer}**
     * （{@code SearchResult.score} 就是这么做的）。</p>
     */
    @Test
    void rawDoubleDiffersFromGoUnlessAnnotated() throws Exception {
        assertThat(jsonOf(1.0)).as("Go 是 {\"v\":1}").isEqualTo("{\"v\":1.0}");
        assertThat(mapper.writeValueAsString(new AnnotatedBox(1.0))).isEqualTo("{\"v\":1}");
    }

    // ── 零值形状（mapper 不额外加工；取舍是逐字段的事） ──────────────────────

    @Test
    void nullAndEmptyContainerShapesMatchGo() throws Exception {
        assertThat(jsonOf(null)).isEqualTo("{\"v\":null}");
        assertThat(jsonOf(List.of())).isEqualTo("{\"v\":[]}");
        assertThat(jsonOf(Map.of())).isEqualTo("{\"v\":{}}");
    }
}
