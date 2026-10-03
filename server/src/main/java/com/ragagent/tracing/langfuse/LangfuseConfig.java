package com.ragagent.tracing.langfuse;

import java.util.Locale;

/**
 * langfuse 运行配置：
 * 环境变量驱动（LANGFUSE_*，走 @ConfigurationProperties 绑定），非法/非正值保持默认，
 * 有凭据（PUBLIC_KEY + SECRET_KEY）且未显式禁用时自动启用（与 Python SDK 一致）。
 *
 * <p>时长解析：本包内置最小解析器（ns/us/µs/ms/s/m/h 复合串 + 纯数字按秒）。
 * 与 datasource.connector.yuque.GoDuration 同形，但该类型包私有，故各自维护。</p>
 */
public record LangfuseConfig(
        boolean enabled,
        String host,
        String publicKey,
        String secretKey,
        int flushAt,
        long flushIntervalMs,
        int queueSize,
        long requestTimeoutMs,
        String release,
        String environment,
        double sampleRate,
        boolean debug) {

    /** 默认值。 */
    public static final String DEFAULT_HOST = "https://cloud.langfuse.com";
    public static final int DEFAULT_FLUSH_AT = 15;
    public static final long DEFAULT_FLUSH_INTERVAL_MS = 3000;
    public static final int DEFAULT_QUEUE_SIZE = 2048;
    public static final long DEFAULT_REQUEST_TIMEOUT_MS = 10_000;

    /**
     * 取值来自 {@link LangfuseEnvProperties}，
     * 非法/缺失回落上方默认值。
     */
    public static LangfuseConfig fromEnv(LangfuseEnvProperties env) {
        String host = firstNonEmpty(env.host(), DEFAULT_HOST);
        String publicKey = trim(env.publicKey());
        String secretKey = trim(env.secretKey());
        String release = trim(env.release());
        String environment = trim(env.environment());

        boolean enabled;
        String enabledRaw = trim(env.enabled());
        if (!enabledRaw.isEmpty()) {
            enabled = parseBool(enabledRaw);
        } else {
            enabled = !publicKey.isEmpty() && !secretKey.isEmpty();
        }

        int flushAt = DEFAULT_FLUSH_AT;
        int parsed = parseIntOrZero(trim(env.flushAt()));
        if (parsed > 0) {
            flushAt = parsed;
        }

        long flushIntervalMs = DEFAULT_FLUSH_INTERVAL_MS;
        String flushRaw = trim(env.flushInterval());
        if (!flushRaw.isEmpty()) {
            long d = parseGoDurationMs(flushRaw);
            if (d <= 0) {
                int secs = parseIntOrZero(flushRaw);
                d = secs > 0 ? secs * 1000L : 0;
            }
            if (d > 0) {
                flushIntervalMs = d;
            }
        }

        int queueSize = DEFAULT_QUEUE_SIZE;
        parsed = parseIntOrZero(trim(env.queueSize()));
        if (parsed > 0) {
            queueSize = parsed;
        }

        long requestTimeoutMs = DEFAULT_REQUEST_TIMEOUT_MS;
        String timeoutRaw = trim(env.requestTimeout());
        if (!timeoutRaw.isEmpty()) {
            long d = parseGoDurationMs(timeoutRaw);
            if (d > 0) {
                requestTimeoutMs = d;
            }
        }

        double sampleRate = 1.0;
        String sampleRaw = trim(env.sampleRate());
        if (!sampleRaw.isEmpty()) {
            try {
                double f = Double.parseDouble(sampleRaw);
                if (f >= 0 && f <= 1) {
                    sampleRate = f;
                }
            } catch (NumberFormatException ignored) {
                // 非法 → 保持默认
            }
        }
        // 0 = 全不采样 → 视作整体关闭（此前被悄悄改写成 1.0，旋钮失效）。
        // (0,1) 区间：trace 根上的概率采样尚未实现（恒采样备案见 LangfuseTracing），
        // 保持默认全采样与既有行为一致。
        if (sampleRate == 0) {
            enabled = false;
        }

        boolean debug = false;
        String debugRaw = trim(env.debug());
        if (!debugRaw.isEmpty()) {
            debug = parseBool(debugRaw);
        }

        return new LangfuseConfig(enabled, host, publicKey, secretKey, flushAt, flushIntervalMs,
                queueSize, requestTimeoutMs, release, environment, sampleRate, debug);
    }

    /** 配置校验：失败消息固定（调用方透传）。 */
    public void validate() {
        if (!enabled) {
            return;
        }
        if (trim(host).isEmpty()) {
            throw new IllegalStateException("langfuse: host is required when enabled");
        }
        if (publicKey == null || publicKey.isEmpty() || secretKey == null || secretKey.isEmpty()) {
            throw new IllegalStateException("langfuse: public_key and secret_key are required when enabled");
        }
    }

    static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    static String firstNonEmpty(String... values) {
        for (String v : values) {
            String s = trim(v);
            if (!s.isEmpty()) {
                return s;
            }
        }
        return "";
    }

    /** 布尔解析："1"/"true"/"t"/"yes"/"y"/"on" 为真，其余为假。 */
    static boolean parseBool(String v) {
        String s = trim(v).toLowerCase(Locale.ROOT);
        return switch (s) {
            case "1", "true", "t", "yes", "y", "on" -> true;
            default -> false;
        };
    }

    private static int parseIntOrZero(String v) {
        if (v == null || v.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 时长解析的最小实现（支持 {@code 1h30m}、{@code 500ms}、
     * {@code 1.5s}、负号）；无法解析或结果 &lt;= 0 → 返回 -1（调用方保持默认）。
     */
    static long parseGoDurationMs(String orig) {
        if (orig == null || orig.isEmpty()) {
            return -1;
        }
        String s = orig.trim();
        boolean neg = false;
        int i = 0;
        if (!s.isEmpty() && (s.charAt(0) == '+' || s.charAt(0) == '-')) {
            neg = s.charAt(0) == '-';
            i = 1;
        }
        if (s.regionMatches(true, i, "0", 0, 1) && s.length() == i + 1) {
            return 0;
        }
        double totalMs = 0;
        boolean sawUnit = false;
        while (i < s.length()) {
            int numStart = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                i++;
            }
            if (i == numStart) {
                return -1;
            }
            double value;
            try {
                value = Double.parseDouble(s.substring(numStart, i));
            } catch (NumberFormatException e) {
                return -1;
            }
            int unitStart = i;
            while (i < s.length() && !Character.isDigit(s.charAt(i)) && s.charAt(i) != '.') {
                i++;
            }
            String unit = s.substring(unitStart, i);
            double scale;
            switch (unit) {
                case "ns" -> scale = 1e-6;
                case "us", "µs", "μs" -> scale = 1e-3;
                case "ms" -> scale = 1;
                case "s" -> scale = 1000;
                case "m" -> scale = 60_000;
                case "h" -> scale = 3_600_000;
                default -> {
                    return -1;
                }
            }
            sawUnit = true;
            totalMs += value * scale;
        }
        if (!sawUnit) {
            return -1;
        }
        long ms = (long) totalMs;
        if (neg) {
            ms = -ms;
        }
        return ms <= 0 ? -1 : ms;
    }
}
