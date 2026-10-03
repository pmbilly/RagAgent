package com.ragagent.event;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Map;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ragagent.common.web.GoDoubleSerializer;
import com.ragagent.common.web.ZeroTimeSerializer;

/**
 * 事件 payload 进出 JSON 的唯一 ObjectMapper，集中定义三条序列化行为
 * （2026-10-03 B39/B40：原第 1 条「HTML 转义复刻 Go」已随 Go 版下线退役）。
 *
 * <ol>
 *   <li><b>map 键按字母序</b>：{@code ORDER_MAP_ENTRIES_BY_KEYS}。payload 的
 *       {@code extra}/{@code arguments}/{@code data}/{@code args} 装的是任意 JSON，
 *       序列化时键恒按字母序输出。</li>
 *   <li><b>浮点整数不补 {@code .0}</b>：{@link GoDoubleSerializer} 注册为 Double/Float 的
 *       序列化器，整数值输出 {@code "n":2} 而非 Jackson 默认的 {@code 2.0}
 *       （这些 map 会经 toolApprovalDataToMap 进 SSE 帧，差异对外可见）。
 *       只注册在<b>本 mapper</b>，不碰全局 HTTP mapper，避免污染。</li>
 *   <li><b>时间 RFC3339Nano</b>：OffsetDateTime 转 JVM 默认时区后 ISO 输出（与
 *       {@code config.JacksonConfig} 一致）；零值时间输出
 *       {@code "0001-01-01T00:00:00Z"}（{@link ZeroTimeSerializer}，如 CommandOutputData
 *       的零值 {@code started_at}）。</li>
 * </ol>
 *
 * <p>读路径容忍未知属性：旧事件里多出的字段不能让整条读不出来。
 * 典型用途 {@code toolApprovalDataToMap}（见 AgentStreamBridge）：
 * {@code write(payload)} → {@code readToMap(json)}。</p>
 */
public final class EventJson {

    private static final ObjectMapper MAPPER = build();

    private static ObjectMapper build() {
        SimpleModule goNumbers = new SimpleModule();
        goNumbers.addSerializer(Double.class, new GoDoubleSerializer());
        goNumbers.addSerializer(Float.class, new JsonSerializer<Float>() {
            @Override
            public void serialize(Float value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                gen.writeRawValue(GoDoubleSerializer.format(value.doubleValue()));
            }
        });

        SimpleModule goTime = new SimpleModule();
        goTime.addSerializer(OffsetDateTime.class, new ZeroTimeSerializer());

        ObjectMapper mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                // ZeroTimeSerializer：常规时间转 JVM 默认时区 + RFC3339Nano，零值输出 year-1 字面量。
                // 只此一份——若再叠一个普通 OffsetDateTime 序列化器会后注册者胜、丢掉零值分支。
                .addModule(goTime)
                .addModule(goNumbers)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        return mapper;
    }

    private EventJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** 序列化 payload。 */
    public static String write(Object payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (IOException e) {
            throw new IllegalStateException("failed to marshal event payload: " + e.getMessage(), e);
        }
    }

    /** 反序列化 payload（容忍未知字段）。 */
    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new IllegalStateException("failed to unmarshal event payload: " + e.getMessage(), e);
        }
    }

    /** 反序列化为 map（toolApprovalDataToMap 的后半段）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readToMap(String json) {
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (IOException e) {
            throw new IllegalStateException("failed to unmarshal event payload map: " + e.getMessage(), e);
        }
    }
}
