package com.ragagent.common.web;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

/**
 * 让 {@code double} 的输出字节与 Go 的 {@code encoding/json} 一致。
 *
 * <h2>为什么需要它</h2>
 * <p>目标格式是 encoding/json 里专用的浮点编码器
 * （{@code floatEncoder}）：</p>
 * <pre>
 *   fmt := 'f'
 *   if abs != 0 &amp;&amp; (abs &lt; 1e-6 || abs &gt;= 1e21) { fmt = 'e' }
 *   strconv.AppendFloat(b, f, fmt, -1, 64)   // -1 = 最短能往返的位数
 *   // 'e' 分支再把 e-09 收成 e-9（指数至少一位，不补零）
 * </pre>
 * <p>目标输出示例（语料见 {@code GoDoubleSerializerTest}）：</p>
 * <pre>
 *   0 → 0        1 → 1        0.5 → 0.5      -0.0 → -0
 *   0.0000025 → 0.0000025    1e-7 → 1e-7    1e21 → 1e+21
 *   1e20 → 100000000000000000000             0.1+0.2 → 0.30000000000000004
 * </pre>
 * <p>而 Jackson 对 {@code double} 直接调 {@code Double.toString}：</p>
 * <pre>
 *   0.0 → 0.0    1.0 → 1.0    1.0E-7 → 1.0E-7    1.0E21 → 1.0E21
 * </pre>
 * <p>两处系统性差异：<b>整数值多出 {@code .0}</b>、<b>指数写法不同</b>
 * （{@code e+21} vs {@code E21}）。这正是 {@code knowledge_references[].score}
 * 这类字段会踩到的——SSE 契约要求逐字节一致，故由本类对齐。</p>
 *
 * <h2>实现</h2>
 * <p>用 Java 的 {@code Double.toString} 取「最短能往返的十进制」，再按目标格式规则
 * 选 'f' 或 'e' 形态输出。
 * 不走 {@code new BigDecimal(double)}——那是**精确二进制展开**，位数远多于最短表示。</p>
 *
 * <h2>为什么还要 {@link #shortestRoundTrip} 再收一遍</h2>
 * <p>JDK 21 的 Java {@code Double.toString} 在<b>次正规数</b>上并不给最短表示：
 * 最小次正规数它给 {@code 4.9E-324}（2 位有效数字），而真正能唯一往返的是
 * {@code 5e-324}（1 位），目标格式正是后者。<b>直接用 {@code Double.toString} 就会在这里分叉。</b></p>
 *
 * <p>故在 Java 结果的基础上再做一轮「有效位数递减」：从 1 位起，把精确二进制值四舍五入到
 * 该位数，若结果能解析回原值就采用更短的那个。这<b>只会朝更短移动</b>——
 * Java 已经最短时收不出更短的，而一旦收出更短的，说明目标格式（要求最短能往返）
 * 也一定用更短的。</p>
 *
 * <p>代价是每个非整数 double 多几次 {@code BigDecimal.round} + {@code parseDouble}。
 * {@code score} 这类字段每次响应出现几十次，可忽略。</p>
 */
public class GoDoubleSerializer extends JsonSerializer<Double> {

    /** 'e' 形态切换下界（含）。 */
    private static final double SCIENTIFIC_LOWER = 1e-6;
    /** 'e' 形态切换上界（含）。 */
    private static final double SCIENTIFIC_UPPER = 1e21;

    @Override
    public void serialize(Double value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        gen.writeNumber(format(value));
    }

    /**
     * 按类注释所述的浮点编码规则格式化。
     *
     * <p>负零单独处理：{@code -0.0} 经 {@code Double.toString} 是 {@code "-0.0"}，
     * 但 {@code Math.abs} 会把它抹成 {@code +0.0}，而目标输出是 {@code -0}。</p>
     */
    public static String format(double value) {
        String sign = "";
        if (value < 0 || (value == 0 && Double.doubleToRawLongBits(value) != 0L)) {
            sign = "-";
        }
        double abs = Math.abs(value);
        if (abs == 0) {
            return sign + "0";
        }

        BigDecimal shortest = shortestRoundTrip(abs);

        if (abs < SCIENTIFIC_LOWER || abs >= SCIENTIFIC_UPPER) {
            return sign + scientific(shortest);
        }
        return sign + shortest.stripTrailingZeros().toPlainString();
    }

    /**
     * 最短能唯一往返的十进制表示——先取 Java 的 {@code Double.toString}，再尝试压缩有效位数。
     *
     * @param abs 非零、已取绝对值的待格式化值
     */
    private static BigDecimal shortestRoundTrip(double abs) {
        BigDecimal stripped = new BigDecimal(Double.toString(abs)).stripTrailingZeros();
        int jdkPrecision = stripped.precision();
        if (jdkPrecision <= 1) {
            return stripped;
        }
        // 精确的二进制展开：四舍五入到 k 位有效数字时的基准。
        BigDecimal exact = new BigDecimal(abs);
        for (int precision = 1; precision < jdkPrecision; precision++) {
            BigDecimal candidate = exact.round(new MathContext(precision, RoundingMode.HALF_EVEN));
            if (Double.parseDouble(candidate.toString()) == abs) {
                return candidate.stripTrailingZeros();
            }
        }
        return stripped;
    }

    /**
     * 'e' 形态：{@code d[.ddd]e[+|-]exp}——指数带符号，且至少一位、不补零
     * （指数最少两位写法的 {@code e-09} 在这里写作 {@code e-9}）。
     */
    private static String scientific(BigDecimal shortest) {
        BigDecimal normalized = shortest.stripTrailingZeros();
        String digits = normalized.unscaledValue().abs().toString();
        int exponent = (digits.length() - 1) - normalized.scale();

        StringBuilder out = new StringBuilder(digits.length() + 8);
        out.append(digits.charAt(0));
        if (digits.length() > 1) {
            out.append('.').append(digits, 1, digits.length());
        }
        out.append('e');
        if (exponent >= 0) {
            out.append('+');
        }
        return out.append(exponent).toString();
    }
}
