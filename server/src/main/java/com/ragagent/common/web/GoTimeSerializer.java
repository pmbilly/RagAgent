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
 * {@code ISO_OFFSET_DATE_TIME} 输出，纳秒尾部零裁剪与 RFC3339Nano 字节相同，见约定 §9）。
 * 本类只多加一条：<b>Go 的零值 {@code time.Time} 输出 {@code "0001-01-01T00:00:00Z"}，
 * 而不是 {@code null}</b>。</p>
 *
 * <h2>为什么必须显式处理</h2>
 * <p>Go 的 {@code time.Time} 是<b>值类型</b>：没有"缺省"这回事，未赋值就是零值，
 * 而它的 {@code MarshalJSON} 会把零值写成 year 1 的 RFC3339 串。Java 侧字段是可空的
 * {@link OffsetDateTime}，不处理就会写出 {@code null}——前端拿到的即时字符串凭空少了一个。</p>
 *
 * <p>实测（{@code /tmp} 下的 Go 程序，类型逐字抄自 {@code internal/types/agent.go}）：</p>
 * <pre>
 *   AgentStep{}.Timestamp        → "0001-01-01T00:00:00Z"
 *   time.Date(2026,9,18,…, UTC)  → "2026-09-18T10:00:00Z"
 * </pre>
 *
 * <h2>零值判定按「瞬时」而非「字面量」</h2>
 * <p>判据是 {@code value.toInstant()} 等于 year 1 元旦 UTC 那个瞬时。于是
 * {@code 0001-01-01T08:00:00+08:00}（<b>另一个</b>瞬时）仍按 §9 的常规规则
 * 归一化到 JVM 默认时区——Go 对它的处理也一样（保留自己的 location）。</p>
 */
public class GoTimeSerializer extends JsonSerializer<OffsetDateTime> {

    /** Go 零值 {@code time.Time} 的瞬时。 */
    public static final Instant GO_ZERO_TIME = Instant.parse("0001-01-01T00:00:00Z");

    /** Go 零值的字面输出。 */
    public static final String GO_ZERO_TIME_LITERAL = "0001-01-01T00:00:00Z";

    /**
     * Go 零值时间的 Java 表示——供字段**默认值**使用。
     *
     * <p>⚠️ 这点很关键：Jackson 对 {@code null} 值调用的是 {@code nullSerializer}，
     * **不会**走 {@code @JsonSerialize(using=…)} 指定的序列化器。
     * 所以"字段为 null 时输出 year-1 字面量"这个想法是行不通的——
     * 必须让字段本身就持有零值时间（这也更贴近 Go 的值类型语义）。</p>
     */
    public static final OffsetDateTime GO_ZERO_DATE_TIME =
            OffsetDateTime.ofInstant(GO_ZERO_TIME, java.time.ZoneOffset.UTC);

    /** 该值是否就是 Go 的零值时间。 */
    public static boolean isGoZero(OffsetDateTime value) {
        return value == null || GO_ZERO_TIME.equals(value.toInstant());
    }

    @Override
    public void serialize(OffsetDateTime value, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {
        if (isGoZero(value)) {
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
     * UTC 变体：GORM/lib-pq 扫描 timestamptz 得到的 time.Time 带 UTC location，
     * Go 原生 marshal 输出 {@code Z}——storage-backends 等直接 marshal struct 的
     * 路径逐字节对齐用（2026-09-22 双端实录抓回）。
     */
    public static final class Utc extends GoTimeSerializer {
        @Override
        protected ZoneId targetZone() {
            return java.time.ZoneOffset.UTC;
        }
    }
}
