package com.ragagent.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link GoDoubleSerializer} 的语料测试——<b>期望值全部是 Go 实录</b>。
 *
 * <p>做法：写一个与 Go 侧同形的结构体（{@code struct{ V float64 `json:"v"` }}），
 * 用 {@code encoding/json} 打印每个语料值，把输出原样抄进下面的表。
 * 这样测的不是"我认为 Go 会这么格式化"，而是"Go 确实这么输出"。</p>
 *
 * <p><b>为什么不用属性/随机测试</b>：两语言的分歧集中在少数构型（整数尾 {@code .0}、
 * 指数写法、次正规数），随机采样撞不到；精选语料逐条钉住更有效。</p>
 */
class GoDoubleSerializerTest {

    /** 语料（值 → Go 的 {@code {"v":<这里>}} 实录）。 */
    static Stream<Arguments> goCorpus() {
        return Stream.of(
                // ── 'f' 形态：整数值不带 .0 ──────────────────────────────
                Arguments.of(0.0, "0"),
                Arguments.of(1.0, "1"),
                Arguments.of(100.0, "100"),
                Arguments.of(1000.0, "1000"),
                Arguments.of(42.0, "42"),

                // ── 'f' 形态：普通小数 ───────────────────────────────────
                Arguments.of(0.5, "0.5"),
                Arguments.of(0.9, "0.9"),
                Arguments.of(0.03125, "0.03125"),                       // 2^-5，二进制精确
                Arguments.of(3.141592653589793, "3.141592653589793"),
                Arguments.of(0.3333333333333333, "0.3333333333333333"),
                Arguments.of(0.30000000000000004, "0.30000000000000004"), // 0.1+0.2 的真值
                Arguments.of(123456789012345680000.0, "123456789012345680000"),
                Arguments.of(9.999999999999999e20, "999999999999999900000"),

                // ── 'f' 形态：下界 1e-6 恰好走 'f'（不是 'e'） ────────────
                Arguments.of(1e-6, "0.000001"),
                Arguments.of(1e-5, "0.00001"),
                Arguments.of(2.5e-6, "0.0000025"),

                // ── 'e' 形态：abs < 1e-6，指数不补零 ──────────────────────
                Arguments.of(1e-7, "1e-7"),
                Arguments.of(-1e-7, "-1e-7"),
                Arguments.of(-2.5e-6, "-0.0000025"),                     // 负值 + 'f'（|x| ≥ 1e-6）

                // ── 'e' 形态：abs >= 1e21，正指数带 '+' ───────────────────
                Arguments.of(1e21, "1e+21"),
                Arguments.of(1e22, "1e+22"),
                Arguments.of(1.5e21, "1.5e+21"),

                // ── 上界以下仍是 'f' ─────────────────────────────────────
                Arguments.of(1e20, "100000000000000000000"),

                // ── 极端值 ──────────────────────────────────────────────
                Arguments.of(Double.MAX_VALUE, "1.7976931348623157e+308"),
                // Java 的 Double.toString 给 4.9E-324（2 位），Go 给 1 位——见类注释
                Arguments.of(Double.MIN_VALUE, "5e-324"));
    }

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("goCorpus")
    void formatsLikeGo(double value, String expected) {
        assertThat(GoDoubleSerializer.format(value)).isEqualTo(expected);
    }

    /**
     * 负零：Go 在**运行期**取负（{@code v := 0.0; -v}）时输出 {@code -0}。
     *
     * <p>注意不能用 Go 的字面量 {@code -0.0} 录制——那是无类型常量 {@code 0}，
     * 编译期就把符号丢了，录出来是 {@code 0}。这条以运行期语义为准。</p>
     */
    @Test
    void formatsNegativeZeroLikeGo() {
        assertThat(GoDoubleSerializer.format(-0.0)).isEqualTo("-0");
    }

    /** 正零与"负零经 {@code Math.abs} 抹平"的路径不能串味。 */
    @Test
    void formatsPositiveZero() {
        assertThat(GoDoubleSerializer.format(0.0)).isEqualTo("0");
    }

    /** 序列化器本身（不只 {@code format}）也要吃满语料——它是挂在字段上的那个。 */
    @Test
    void serializerWritesRawNumberToken() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(mapper.writeValueAsString(new DoubleHolder(1e21))).isEqualTo("{\"v\":1e+21}");
        assertThat(mapper.writeValueAsString(new DoubleHolder(0.5))).isEqualTo("{\"v\":0.5}");
    }

    static final class DoubleHolder {
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = GoDoubleSerializer.class)
        public double v;

        DoubleHolder(double v) {
            this.v = v;
        }
    }
}
