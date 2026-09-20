package com.ragagent.agent.tools;

import java.time.Duration;
import java.util.Map;

/**
 * sandbox 命令执行结果（对照 Go {@code sandbox.ExecuteResult}，internal/sandbox/sandbox.go:219-236）。
 * Duration 用 Duration 承载；visible 输出里的 {@code %v} 形态（如 1.5s / 10m0s）由
 * {@link #goDuration()} 复刻。
 */
public record SandboxExecuteResult(
        String stdout,
        String stderr,
        int exitCode,
        Duration duration,
        boolean killed,
        String error) {

    public SandboxExecuteResult {
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
        error = error == null ? "" : error;
        duration = duration == null ? Duration.ZERO : duration;
    }

    /** Go Duration 的 String() 形态（h/m/s/ms/µs/ns 组合）。 */
    public String goDuration() {
        return GoDuration.of(duration);
    }

    /** 对照 ExecuteResult.IsSuccess（工具面未用到，保留语义）。 */
    public boolean isSuccess() {
        return exitCode == 0 && !killed && error.isEmpty();
    }

    /** Go time.Duration.String() 的逐字复刻（shell_exec 的 **Duration** 行是字节契约）。 */
    public static final class GoDuration {

        private GoDuration() {
        }

        public static String of(Duration d) {
            if (d.isZero()) {
                return "0s";
            }
            boolean neg = d.isNegative();
            java.time.Duration abs = neg ? d.negated() : d;
            long nanos = abs.toNanos();
            StringBuilder sb = new StringBuilder();
            if (neg) {
                sb.append('-');
            }
            if (nanos < 1_000_000_000L) {
                // sub-second: ns / µs / ms
                if (nanos < 1_000L) {
                    sb.append(nanos).append("ns");
                } else if (nanos < 1_000_000L) {
                    sb.append(nanos / 1_000L);
                    appendFraction(sb, nanos % 1_000L, 1_000L).append("µs");
                } else {
                    sb.append(nanos / 1_000_000L);
                    appendFraction(sb, nanos % 1_000_000L, 1_000_000L).append("ms");
                }
                return sb.toString();
            }
            long hours = nanos / 3_600_000_000_000L;
            long minutes = (nanos / 60_000_000_000L) % 60;
            long seconds = (nanos / 1_000_000_000L) % 60;
            if (hours > 0) {
                sb.append(hours).append('h');
            }
            if (hours > 0 || minutes > 0) {
                sb.append(minutes).append('m');
            }
            long secNanos = nanos % 1_000_000_000L;
            sb.append(seconds);
            if (secNanos == 0) {
                sb.append('s');
                return sb.toString();
            }
            appendFraction(sb, secNanos, 1_000_000_000L).append('s');
            return sb.toString();
        }

        /** 小数部分：值已是单位内的余量，输出去尾零的小数（无余量则只出整数——Go 形态）。 */
        private static StringBuilder appendFraction(StringBuilder sb, long frac, long unit) {
            if (frac == 0) {
                return sb;
            }
            sb.append('.');
            String digits = String.format("%09d", frac);
            int end = digits.length();
            while (end > 0 && digits.charAt(end - 1) == '0') {
                end--;
            }
            sb.append(digits, 0, Math.max(end, 1));
            return sb;
        }
    }
}
