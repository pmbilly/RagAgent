package com.ragagent.common.web;

import java.io.IOException;
import java.math.BigDecimal;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

/**
 * 让 {@code float}（float32）的输出字节与 Go 的 {@code encoding/json} 一致——
 * {@link GoDoubleSerializer} 的 float32 变体。
 *
 * <p>差异点与 double 版相同（整数值的 {@code .0}、指数写法），但「最短能往返」
 * 的语义必须按 <b>float32</b> 取：{@code Float.toString} 给的正是 float32 最短
 * 往返表示（JDK 21），与 Go 的 {@code AppendFloat(..., -1, 32)} 同语义。
 * 不能把 float 提升成 double 再格式化——0.1f 提升后是 0.10000000149011612，
 * 而 Go 输出 0.1。</p>
 *
 * <p>首个消费点：models/{id}/debug 的 embedding raw_response（{@code []float32}）。</p>
 */
public class GoFloatSerializer extends JsonSerializer<Float> {

    /** Go 的 'e' 形态切换阈值（floatEncoder 对 float32 与 float64 用同一对阈值）。 */
    private static final double SCIENTIFIC_LOWER = 1e-6;
    private static final double SCIENTIFIC_UPPER = 1e21;

    @Override
    public void serialize(Float value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        gen.writeNumber(format(value));
    }

    /** 按 Go floatEncoder 规则格式化 float32。 */
    public static String format(float value) {
        String sign = "";
        if (value < 0 || (value == 0 && Float.floatToRawIntBits(value) != 0)) {
            sign = "-";
        }
        float abs = Math.abs(value);
        if (abs == 0) {
            return sign + "0";
        }
        // Float.toString 已是 float32 最短往返；BigDecimal 只用来取有效数字与指数
        BigDecimal shortest = new BigDecimal(Float.toString(abs)).stripTrailingZeros();
        if (abs < SCIENTIFIC_LOWER || abs >= SCIENTIFIC_UPPER) {
            return sign + scientific(shortest);
        }
        return sign + shortest.toPlainString();
    }

    /** Go 的 'e' 形态：d[.ddd]e[+|-]exp（指数带符号、不补零）。 */
    private static String scientific(BigDecimal shortest) {
        String digits = shortest.unscaledValue().abs().toString();
        int exponent = (digits.length() - 1) - shortest.scale();
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
