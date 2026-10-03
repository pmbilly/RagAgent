package com.ragagent.stream;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 流事件进出 Redis 用的 ObjectMapper（**不是** HTTP 响应那个）。
 *
 * <p><b>2026-10-03（B38）</b>：Go 版已下线、不再双跑——不再复刻 Go 的
 * {@code encoding/json} 转义（{@code < > &} 形态）。以下两处保留：</p>
 * <ol>
 *   <li><b>map 按键字母序</b>：{@code UpdateSteerEventData} 的 CAS 把**读到的原文**
 *       与 LSET 前的槽位比对——同一 data 必须序列化出<b>稳定字节</b>，键序不能随机。</li>
 *   <li><b>timestamp 用本地时区 + ISO_OFFSET_DATE_TIME</b>：与
 *       {@code config.JacksonConfig} 对 OffsetDateTime 的处置一致（RFC3339Nano，
 *       纳秒尾部零裁剪），见约定 §9「Go 时间序列化」。该覆盖在 JavaTimeModule
 *       之后注册（后者后注册者胜），由 {@code StreamJsonTest} 钉住。</li>
 *   <li><b>容忍未知属性</b>：Go 的 {@code json.Unmarshal} 默认忽略未知字段，
 *       Jackson 默认失败。旧版本写下的行不能因为多了个字段就整条读不出来。</li>
 * </ol>
 */
public final class StreamJson {

    private static final ObjectMapper MAPPER = build();

    private static ObjectMapper build() {
        ObjectMapper mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .addModule(offsetDateTimeModule())
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        return mapper;
    }

    private StreamJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new StreamStoreException("failed to marshal event: " + e.getMessage(), e);
        }
    }

    /**
     * 序列化单个字符串（对照 Go 的 {@code json.Marshal(id)}）。
     *
     * <p>{@code ClearLiveRun} 的 CAS 要在原始 JSON 里做子串匹配
     * （{@code "assistant_message_id":<这里>}），所以引号与转义必须由同一个
     * mapper 产出，不能手工拼 {@code "\"" + id + "\""}。</p>
     */
    public static String writeString(String value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new StreamStoreException("failed to marshal assistant message id: " + e.getMessage(), e);
        }
    }

    /** 反序列化失败时抛 {@link StreamStoreException}，消息即 Jackson 的原始描述——由调用方加前缀。 */
    public static <T> T read(String raw, Class<T> type) {
        try {
            return MAPPER.readValue(raw, type);
        } catch (IOException e) {
            throw new StreamStoreException(String.valueOf(e.getMessage()), e);
        }
    }

    /** 与 {@code config.JacksonConfig} 同一套 OffsetDateTime 输出规则（本地时区 + RFC3339Nano）。 */
    private static Module offsetDateTimeModule() {
        return new SimpleModule().addSerializer(OffsetDateTime.class, new JsonSerializer<OffsetDateTime>() {
            @Override
            public void serialize(OffsetDateTime value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                OffsetDateTime local = value.atZoneSameInstant(ZoneId.systemDefault()).toOffsetDateTime();
                gen.writeString(local.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            }
        });
    }
}
