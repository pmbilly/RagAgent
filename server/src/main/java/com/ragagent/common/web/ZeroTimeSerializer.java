package com.ragagent.common.web;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

/**
 * 落 jsonb / 作响应体的 {@code time.Time} 字段的序列化器。
 *
 * <p>常规规则与 {@link com.ragagent.config.JacksonConfig} 完全一致（转 JVM 默认时区后按
 * {@code ISO_OFFSET_DATE_TIME} 输出，纳秒尾部零裁剪与 RFC3339Nano 字节相同）。
 * 本类只多加一条：<b>零值时间输出 {@code "0001-01-01T00:00:00Z"}，
 * 而不是 {@code null}</b>。</p>
 *
 * <h2>为什么必须显式处理</h2>
 * <p>时间字段没有"缺省"可言：未赋值就是零值，
 * 序列化约定会把零值写成 year 1 的 RFC3339 串。Java 侧字段是可空的
 * {@link OffsetDateTime}，不处理就会写出 {@code null}——前端拿到的即时字符串凭空少了一个。</p>
 *
 * <p>目标输出形态：</p>
 * <pre>
 *   零值时间                     → "0001-01-01T00:00:00Z"
 *   2026-09-18T10:00:00Z (UTC)   → "2026-09-18T10:00:00Z"
 * </pre>
 *
 * <h2>零值判定按「瞬时」而非「字面量」</h2>
 * <p>判据是 {@code value.toInstant()} 等于 year 1 元旦 UTC 那个瞬时。于是
 * {@code 0001-01-01T08:00:00+08:00}（<b>另一个</b>瞬时）仍按常规规则
 * 归一化到 JVM 默认时区，不会被当成零值。</p>
 */
public class ZeroTimeSerializer extends JsonSerializer<OffsetDateTime> {

    /** 零值时间的瞬时（year 1 元旦 UTC）。 */
    public static final Instant GO_ZERO_TIME = Instant.parse("0001-01-01T00:00:00Z");

    /** 零值的字面输出。 */
    public static final String GO_ZERO_TIME_LITERAL = "0001-01-01T00:00:00Z";

    /**
     * 零值时间的 Java 表示——供字段**默认值**使用。
     *
     * <p>⚠️ 这点很关键：Jackson 对 {@code null} 值调用的是 {@code nullSerializer}，
     * **不会**走 {@code @JsonSerialize(using=…)} 指定的序列化器。
     * 所以"字段为 null 时输出 year-1 字面量"这个想法是行不通的——
     * 必须让字段本身就持有零值时间（贴合"时间非空"的既定语义）。</p>
     */
    public static final OffsetDateTime ZERO_DATE_TIME =
            OffsetDateTime.ofInstant(GO_ZERO_TIME, java.time.ZoneOffset.UTC);

    /** 该值是否就是零值时间。 */
    public static boolean isZeroValue(OffsetDateTime value) {
        return value == null || GO_ZERO_TIME.equals(value.toInstant());
    }

    @Override
    public void serialize(OffsetDateTime value, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {
        if (isZeroValue(value)) {
            gen.writeString(GO_ZERO_TIME_LITERAL);
            return;
        }
        gen.writeString(value.atZoneSameInstant(targetZone()).toOffsetDateTime()
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    }

    /** 渲染时区：默认 JVM 本地（常规路径），UTC 变体覆盖。 */
    protected ZoneId targetZone() {
        return ZoneId.systemDefault();
    }

    /**
     * UTC 变体：timestamptz 列扫描出的时间带 UTC offset，
     * storage-backends 等直接序列化整行数据的路径需要输出 {@code Z} 时用它。
     */
    public static final class Utc extends ZeroTimeSerializer {
        @Override
        protected ZoneId targetZone() {
            return java.time.ZoneOffset.UTC;
        }
    }
}
