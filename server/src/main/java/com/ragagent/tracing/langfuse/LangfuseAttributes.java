package com.ragagent.tracing.langfuse;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.ragagent.common.web.GoDoubleSerializer;

/**
 * Langfuse / OpenTelemetry 语义约定属性键与序列化辅助。
 *
 * <p>属性值恒为<b>字符串</b>（结构化字段先 JSON 序列化再包成 string attribute，
 * 与 langfuse-python v4 的存储方式一致）。JSON 编码规则：
 * map 键序 + {@code < > &} 转义 + 整数型 double 不带 .0，与 StreamJson 同款。</p>
 */
public final class LangfuseAttributes {

    private LangfuseAttributes() {
    }

    // ── 属性键（与 langfuse-python v4 SDK 的 _client/attributes.py 一致） ──

    public static final String ATTR_OBS_TYPE = "langfuse.observation.type";
    public static final String ATTR_OBS_INPUT = "langfuse.observation.input";
    public static final String ATTR_OBS_OUTPUT = "langfuse.observation.output";
    public static final String ATTR_OBS_METADATA = "langfuse.observation.metadata";
    public static final String ATTR_OBS_MODEL = "langfuse.observation.model.name";
    public static final String ATTR_OBS_MODEL_PARAMS = "langfuse.observation.model.parameters";
    public static final String ATTR_OBS_USAGE_DETAILS = "langfuse.observation.usage_details";
    public static final String ATTR_OBS_COMPLETION_START = "langfuse.observation.completion_start_time";
    public static final String ATTR_TRACE_NAME = "langfuse.trace.name";
    public static final String ATTR_TRACE_INPUT = "langfuse.trace.input";
    public static final String ATTR_TRACE_OUTPUT = "langfuse.trace.output";
    public static final String ATTR_TRACE_METADATA = "langfuse.trace.metadata";
    public static final String ATTR_TRACE_TAGS = "langfuse.trace.tags";
    public static final String ATTR_USER_ID = "user.id";
    public static final String ATTR_SESSION_ID = "session.id";
    public static final String ATTR_ENVIRONMENT = "langfuse.environment";
    public static final String ATTR_RELEASE = "langfuse.release";
    public static final String ATTR_LANGFUSE_PUBLIC_KEY = "langfuse.public.key";

    /** OTel instrumentation scope 名/版本（随载荷上报）。 */
    public static final String SCOPE_NAME = "langfuse-sdk";
    public static final String SCOPE_VERSION = "4.0.0";

    /** OTel resource 的 service.name 属性。 */
    public static final String SERVICE_NAME = "weknora";

    /** resource 级属性 public_key。 */
    public static final String ATTR_SCOPE_PUBLIC_KEY = "public_key";

    // ── 观测类型（langfuse.observation.type 的取值） ──

    public static final String OBS_TYPE_TRACE = "trace";
    public static final String OBS_TYPE_SPAN = "span";
    public static final String OBS_TYPE_GENERATION = "generation";

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** 紧凑 JSON 编码器（map 键序 + 整数型 double 直写；2026-10-03 B38 起不再复刻 Go 转义）。 */
    private static final ObjectMapper JSON = buildJson();

    private static ObjectMapper buildJson() {
        ObjectMapper mapper = JsonMapper.builder()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
        SimpleModule module = new SimpleModule();
        module.addSerializer(Double.class, new GoDoubleSerializer());
        mapper.registerModule(module);
        return mapper;
    }

    /**
     * 属性值序列化为紧凑 JSON 字符串；null/空/字面量 {@code null}
     * → 返回 null（调用方跳过该属性，而非写入空值）。
     */
    public static String jsonAttrValue(Object v) {
        if (v == null) {
            return null;
        }
        String json;
        try {
            json = JSON.writeValueAsString(v);
        } catch (IOException e) {
            return null;
        }
        if (json.isEmpty() || "null".equals(json)) {
            return null;
        }
        return json;
    }

    /** metadata 合并：start 打底、finish 覆盖；两者皆空 → null（不写属性）。 */
    public static Map<String, Object> mergeMetadata(Map<String, Object> start,
                                                    Map<String, Object> finish) {
        boolean startEmpty = start == null || start.isEmpty();
        boolean finishEmpty = finish == null || finish.isEmpty();
        if (startEmpty && finishEmpty) {
            return null;
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        if (!startEmpty) {
            merged.putAll(start);
        }
        if (!finishEmpty) {
            merged.putAll(finish);
        }
        return merged;
    }

    /** ISO-8601 时间：UTC 毫秒精度（如 {@code 2026-01-02T15:04:05.000Z}）。 */
    public static String isoTime(long epochMillis) {
        return ISO_MILLIS.format(Instant.ofEpochMilli(epochMillis));
    }

    /** W3C 32 位十六进制随机 trace id。 */
    public static String randomTraceIdHex() {
        return randomHex(16);
    }

    /** 16 位十六进制 span id。 */
    public static String randomSpanIdHex() {
        return randomHex(8);
    }

    private static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return toHex(buf);
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 十六进制 → 字节；非十六进制或奇数长度 → null。 */
    public static byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty() || hex.length() % 2 != 0) {
            return null;
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** 判空（null 或空串）。 */
    public static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    /** 收集非空属性（helper：避免调用点堆 if）。 */
    public static void putIfPresent(Map<String, String> attrs, String key, String jsonValue) {
        if (jsonValue != null && !jsonValue.isEmpty()) {
            attrs.put(key, jsonValue);
        }
    }

    /** 只读视图（导出时遍历）。 */
    public static List<String> keysOf(Map<String, String> attrs) {
        return new ArrayList<>(attrs.keySet());
    }
}
