package com.ragagent.datasource.connector.notion;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Notion 连接器里的三个"Go 语义"纯函数：{@code strings.TrimSpace}、
 * {@code fmt.Sprintf("%d"/"%g")}、以及 {@code time.Time.MarshalJSON} 的字面量格式。
 *
 * <h2>为什么不能直接用 Java 的同类 API</h2>
 * <ol>
 *   <li><b>{@code strings.TrimSpace} ≠ {@code String.strip()}</b>：Go 的
 *       {@code unicode.IsSpace} = 显式列举的 {@code \t \n \v \f \r 空格 U+0085 U+00A0}
 *       外加 {@code unicode.White_Space} 的其余成员。Java 的 {@code Character.isWhitespace}
 *       <b>不含</b> U+00A0 / U+2007 / U+202F，却<b>含</b> U+001C–U+001F——两个方向都不对。
 *       而 Notion 正文里出现 NBSP（U+00A0）是常事，
 *       {@code lines.TrimSpace(markdown) != ""} 这个"页面是否为空"的判定会因此分叉。
 *       （与约定 §9 memory 那条"Java 的 isWhitespace 不含 U+00A0"同族。）</li>
 *   <li><b>{@code fmt.Sprintf("%g")} ≠ {@code Double.toString}</b>：见 {@link #goFormatG}。</li>
 *   <li><b>{@code time.Time} 的 MarshalJSON 保留自己的时区偏移</b>，而项目的
 *       {@code GoTimeSerializer} 会把时间归一化到 JVM 默认时区（那条规则服务的是
 *       落 jsonb 的领域对象）。连接器 cursor 里的 {@code page_edit_times} 是
 *       <b>Notion 原样给的 UTC 串</b>，归一化会写出 {@code +08:00}——
 *       与 Go 写出的 {@code …Z} 不同字节，故这里另写一份。</li>
 * </ol>
 */
final class NotionValues {

    private NotionValues() {
    }

    // ──────────────────────────────────────────────────────────────────────
    // strings.TrimSpace
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code unicode.IsSpace}。
     *
     * <p>Go 的实现是"先枚举 ASCII/Latin-1 的 8 个，再查 {@code unicode.White_Space}
     * 里 Latin-1 之外的部分"。Java 侧逐条照抄成显式集合，而不是去映射
     * {@code Character.isWhitespace}（那个不等价）。</p>
     */
    static boolean isGoSpace(char c) {
        switch (c) {
            case '\t':   // 0x09
            case '\n':   // 0x0A
            case 0x0B:   // \v
            case '\f':   // 0x0C
            case '\r':   // 0x0D
            case ' ':    // 0x20
            case 0x85:   // NEL
            case 0xA0:   // NBSP
                return true;
            default:
                break;
        }
        // unicode.White_Space 的 Latin-1 之外部分
        return c == 0x1680
                || (c >= 0x2000 && c <= 0x200A)
                || c == 0x2028
                || c == 0x2029
                || c == 0x202F
                || c == 0x205F
                || c == 0x3000;
    }

    /** 对照 Go {@code strings.TrimSpace}（两端的 Unicode 空白）。 */
    static String trimSpace(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        int start = 0;
        int end = s.length();
        while (start < end && isGoSpace(s.charAt(start))) {
            start++;
        }
        while (end > start && isGoSpace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 数字 → 字符串
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code extractValue} 的 {@code float64} 分支：
     * <pre>
     *   if val == float64(int64(val)) { return fmt.Sprintf("%d", int64(val)) }
     *   return fmt.Sprintf("%g", val)
     * </pre>
     *
     * <p>Go 的 JSON 解码器把**所有**数字都变成 {@code float64}，Jackson 却会按需
     * 给出 {@code IntNode}/{@code LongNode}/{@code DoubleNode}/{@code BigIntegerNode}，
     * 所以这里先统一取 {@code doubleValue()} 再走 Go 的分支——
     * 这是"看起来多此一举、实则必须"的一步。</p>
     *
     * <p>越界转换（如 {@code 1e20}）在两侧都是"饱和到 long 边界"，
     * 于是比较必然失败、落到 {@code %g}，实测 {@code 1e20 → "1e+20"}。</p>
     */
    static String jsonNumberToString(double val) {
        long asLong = (long) val;
        if (val == (double) asLong) {
            return Long.toString(asLong);
        }
        return goFormatG(val);
    }

    /**
     * 对照 Go {@code fmt.Sprintf("%g", v)}（即 {@code strconv.AppendFloat(buf, v, 'g', -1, 64)}）。
     *
     * <h2>与 encoding/json 的浮点编码器**不是**一回事</h2>
     * <p>{@code GoDoubleSerializer} 复刻的是 {@code encoding/json} 的专用编码器
     * （'f' 与 'e' 的分界在 {@code 1e-6 / 1e21}）。{@code %g} 走的是 strconv 的
     * {@code formatDigits}：'e' 形态的分界是
     * <b>{@code exp < -4 || exp >= 6}</b>（`shortest` 时 {@code eprec=6}），
     * 且指数**至少两位**（{@code 1e-05} 而不是 {@code 1e-5}）。</p>
     * <p>实测（{@code /tmp/weknora-copy} 的 probe）：</p>
     * <pre>
     *   1000000  → 走 %d 分支 → "1000000"
     *   1234567.5 → "1.2345675e+06"
     *   0.00001   → "1e-05"        0.0001 → "0.0001"
     *   1e20      → "1e+20"        1e21   → "1e+21"
     *   -0.5      → "-0.5"
     * </pre>
     */
    static String goFormatG(double val) {
        if (Double.isNaN(val)) {
            return "NaN";
        }
        if (Double.isInfinite(val)) {
            return val > 0 ? "+Inf" : "-Inf";
        }
        String sign = "";
        double abs = val;
        if (val < 0 || (val == 0 && Double.doubleToRawLongBits(val) != 0L)) {
            sign = "-";
            abs = -val;
        }
        if (abs == 0) {
            return sign + "0";
        }
        BigDecimal shortest = shortestRoundTrip(abs).stripTrailingZeros();
        String digits = shortest.unscaledValue().abs().toString();
        int nd = digits.length();
        int dp = nd - shortest.scale();
        int exp = dp - 1;
        if (exp < -4 || exp >= 6) {
            StringBuilder out = new StringBuilder(digits.length() + 8);
            out.append(sign).append(digits.charAt(0));
            if (nd > 1) {
                out.append('.').append(digits, 1, nd);
            }
            // Go 的 fmtE：指数带符号，且**至少两位**（"1e+06" / "1e-05"）。
            out.append('e');
            int e = exp;
            if (e < 0) {
                out.append('-');
                e = -e;
            } else {
                out.append('+');
            }
            if (e < 10) {
                out.append('0');
            }
            out.append(e);
            return out.toString();
        }
        return sign + shortest.toPlainString();
    }

    /**
     * 最短能唯一往返的十进制表示——与 {@code GoDoubleSerializer} 的做法相同
     * （{@code Double.toString} 在次正规数上不是最短表示，故再压缩有效位数，
     * 这只会朝 Go 移动）。
     */
    private static BigDecimal shortestRoundTrip(double abs) {
        BigDecimal stripped = new BigDecimal(Double.toString(abs)).stripTrailingZeros();
        int jdkPrecision = stripped.precision();
        if (jdkPrecision <= 1) {
            return stripped;
        }
        BigDecimal exact = new BigDecimal(abs);
        for (int precision = 1; precision < jdkPrecision; precision++) {
            BigDecimal candidate = exact.round(new MathContext(precision, RoundingMode.HALF_EVEN));
            if (Double.parseDouble(candidate.toString()) == abs) {
                return candidate.stripTrailingZeros();
            }
        }
        return stripped;
    }

    // ──────────────────────────────────────────────────────────────────────
    // time.Time.MarshalJSON
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 对照 Go 的 {@code time.Time.MarshalJSON}（{@code RFC3339Nano} 布局
     * {@code 2006-01-02T15:04:05.999999999Z07:00}）：<b>保留时间自己的偏移</b>，
     * 小数秒去掉尾随零、为零时整个小数部分省略。
     *
     * <p>实测（probe 的 {@code cursor.json}）：</p>
     * <pre>
     *   2026-01-15T10:00:00Z（UTC）              → "2026-01-15T10:00:00Z"
     *   10:00:00.123 +0000                       → "2026-01-15T10:00:00.123Z"
     *   14:07:39.482344 +08:00                   → "2026-09-18T14:07:39.482344+08:00"
     *   10:00:00 +05:30                          → "2026-01-15T10:00:00+05:30"
     * </pre>
     */
    static String rfc3339Nano(OffsetDateTime time) {
        StringBuilder sb = new StringBuilder(32);
        int year = time.getYear();
        appendPadded(sb, year, 4);
        sb.append('-');
        appendPadded(sb, time.getMonthValue(), 2);
        sb.append('-');
        appendPadded(sb, time.getDayOfMonth(), 2);
        sb.append('T');
        appendPadded(sb, time.getHour(), 2);
        sb.append(':');
        appendPadded(sb, time.getMinute(), 2);
        sb.append(':');
        appendPadded(sb, time.getSecond(), 2);

        int nanos = time.getNano();
        if (nanos != 0) {
            String fraction = Integer.toString(nanos + 1_000_000_000).substring(1); // 补齐 9 位
            int end = fraction.length();
            while (end > 0 && fraction.charAt(end - 1) == '0') {
                end--;
            }
            sb.append('.').append(fraction, 0, end);
        }

        int offsetSeconds = time.getOffset().getTotalSeconds();
        if (offsetSeconds == 0) {
            sb.append('Z');
        } else {
            sb.append(offsetSeconds < 0 ? '-' : '+');
            int total = Math.abs(offsetSeconds);
            appendPadded(sb, total / 3600, 2);
            sb.append(':');
            appendPadded(sb, (total % 3600) / 60, 2);
        }
        return sb.toString();
    }

    /** 解析 Notion 给回来的 RFC3339 时间；失败时返回 {@code null}（调用方决定语义）。 */
    static OffsetDateTime parseRfc3339(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 供 cursor 构造用的"当前时间"（Go 的 {@code time.Now()}：本地时区）。 */
    static OffsetDateTime now() {
        return OffsetDateTime.now();
    }

    /** 便于测试的"指定偏移的当前时间"。 */
    static OffsetDateTime now(ZoneOffset offset) {
        return OffsetDateTime.now(offset);
    }

    private static void appendPadded(StringBuilder sb, int value, int width) {
        String s = Integer.toString(value);
        for (int i = s.length(); i < width; i++) {
            sb.append('0');
        }
        sb.append(s);
    }
}
