package com.ragagent.datasource.connector.yuque;

import java.time.Duration;

/**
 * 按 Go 标准库 {@code time.ParseDuration} 语义实现的时长解析器。
 *
 * <h2>为什么需要它</h2>
 * <p>语雀的 {@code parseRetryAfter} 做的是
 * {@code time.ParseDuration(header + "s")}，所以它的<b>失败模式</b>也是语义的一部分：
 * {@code "abc"} → {@code "abcs"} 解析失败 → 回落到退避；{@code "1s"} → {@code "1ss"}
 * 失败 → 同样回落；{@code "0.5"} → {@code "0.5s"} 成功 → 500ms。</p>
 * <p>直接拿 {@code Long.parseLong} 或者"非数字就当整数秒"糊过去，会在这三种输入上
 * 得出与参照语义不同的结果——而 {@code Retry-After} 是<em>对端</em>可控的输入。</p>
 *
 * <h2>覆盖面</h2>
 * <p>支持文法 {@code [-+]?([0-9]*(\.[0-9]*)?[unit])+}，单位表
 * {@code ns/us/µs/μs/ms/s/m/h}，含溢出判定（{@code "9223372036854775807s"}
 * 无效：乘 1e9 越界）。期望值见 {@code GoDurationTest}。</p>
 *
 * <p>非法输入抛 {@link IllegalArgumentException}，消息为
 * {@code time: invalid duration "..."}。</p>
 */
final class GoDuration {

    private GoDuration() {
    }

    /** 单位 → 纳秒。token 是整数部分之后允许出现的前缀集。 */
    private static long unitScale(String unit) {
        switch (unit) {
            case "ns": return 1L;
            case "us":
            case "µs":
            case "μs": return 1_000L;
            case "ms": return 1_000_000L;
            case "s": return 1_000_000_000L;
            case "m": return 60L * 1_000_000_000L;
            case "h": return 3600L * 1_000_000_000L;
            default: return -1L;
        }
    }

    /**
     * 消费单位：单位是**连续到下一个数字或小数点为止**的整段。
     *
     * <p>这一点影响错误文案（对 {@code "1ss"} 报的是
     * {@code unknown unit "ss"}，不是消费一个 {@code 's'} 之后再报语法错），
     * 而文案又决定了它是"解析失败 → 回落退避"还是"解析成功 → 用这个值"，
     * 所以按整段消费，而不是逐个前缀试探。</p>
     *
     * @return 单位长度；0 表示"没有单位"
     */
    private static int unitLength(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) != '.' && !isDigit(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /**
     * 解析时长字符串（如 {@code "300ms"}、{@code "1.5h"}、{@code "-2s"}）。
     *
     * @throws IllegalArgumentException 文法错误或数值溢出
     */
    static Duration parse(String orig) {
        String s = orig == null ? "" : orig;
        if (s.equals("0")) {
            return Duration.ZERO;
        }
        boolean negative = false;
        if (!s.isEmpty() && (s.charAt(0) == '-' || s.charAt(0) == '+')) {
            negative = s.charAt(0) == '-';
            s = s.substring(1);
        }
        if (s.equals("0")) {
            return Duration.ZERO;
        }
        if (s.isEmpty()) {
            throw invalid(orig);
        }

        long total = 0; // 纳秒

        while (!s.isEmpty()) {
            // ── 整数部分 ──
            int i = 0;
            while (i < s.length() && isDigit(s.charAt(i))) {
                i++;
            }
            String pre = s.substring(0, i);
            String post = "";
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
                int j = i;
                while (j < s.length() && isDigit(s.charAt(j))) {
                    j++;
                }
                post = s.substring(i, j);
                i = j;
            }
            s = s.substring(i);
            if (pre.isEmpty() && post.isEmpty()) {
                throw invalid(orig);
            }

            long value = 0;
            for (int k = 0; k < pre.length(); k++) {
                if (value > Long.MAX_VALUE / 10) {
                    throw invalid(orig);
                }
                value = value * 10 + (pre.charAt(k) - '0');
                if (value < 0) {
                    throw invalid(orig);
                }
            }

            // ── 单位 ──
            if (s.isEmpty()) {
                // Go：缺少单位（"missing unit in duration"）
                throw invalid(orig);
            }
            int unitLen = unitLength(s);
            if (unitLen == 0) {
                // Go：缺失单位（"missing unit in duration"）
                throw invalid(orig);
            }
            String unit = s.substring(0, unitLen);
            long scale = unitScale(unit);
            if (scale < 0) {
                // Go：未知单位（"unknown unit ... in duration ..."）
                throw invalid(orig);
            }
            s = s.substring(unitLen);

            if (value > Long.MAX_VALUE / scale) {
                throw invalid(orig); // 溢出判定
            }
            value *= scale;

            // ── 小数部分 ──
            if (!post.isEmpty()) {
                // 小数部分按浮点计算（舍入语义是契约的一部分）：
                double f = 0;
                double scale10 = 1;
                for (int k = 0; k < post.length(); k++) {
                    f = f * 10 + (post.charAt(k) - '0');
                    scale10 *= 10;
                }
                value += (long) (f * (scale / scale10));
            }

            total += value;
            if (total < 0) {
                throw invalid(orig);
            }
        }
        return Duration.ofNanos(negative ? -total : total);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static IllegalArgumentException invalid(String orig) {
        return new IllegalArgumentException("time: invalid duration \"" + orig + "\"");
    }
}
