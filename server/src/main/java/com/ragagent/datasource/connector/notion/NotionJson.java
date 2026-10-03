package com.ragagent.datasource.connector.notion;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Notion 连接器内部的 JSON 编解码器。
 *
 * <h2>这是内部 API 形状的 mapper，不是契约 mapper</h2>
 * <p>它只服务于"Notion API 的请求/响应"这一条链路——这些类型**从不**落 jsonb、
 * **从不**作 HTTP 响应体，所以它们不需要 {@code @JsonIgnore}/{@code SortedMapSerializer}
 * 那一套（那套约束的对象是"会落 jsonb 或作响应体的类型"）。
 * 类注释里写清这一点，是为了避免后来人把它们误当契约类型去加注解。</p>
 *
 * <h2>三项关键配置</h2>
 * <ol>
 *   <li><b>未知字段不报错</b>：Notion 的响应字段极多，
 *       {@code FAIL_ON_UNKNOWN_PROPERTIES=false} 是必须的。</li>
 *   <li><b>时区不做上下文调整</b>：反序列化保留原偏移
 *       （{@code "…Z"} 就是 UTC，{@code "+08:00"} 就是 +08:00）。
 *       Jackson 的 {@code ADJUST_DATES_TO_CONTEXT_TIME_ZONE} 默认会把它们挪到
 *       {@code TimeZone.getDefault()}，必须关掉，否则 {@code last_edited_time}
 *       的偏移被改写、增量比对虽然仍按瞬时相等、但写回 cursor 的字面量会漂。</li>
 *   <li><b>时间按 ISO 字符串读写</b>（{@code WRITE_DATES_AS_TIMESTAMPS} 关闭）。</li>
 * </ol>
 *
 * <p>{@link JavaTimeModule} 是必需的：Notion 的时间是
 * {@code "2026-01-15T10:00:00.000Z"} 形态的 RFC3339，{@code OffsetDateTime}
 * 没有它直接抛 {@code InvalidDefinitionException}。</p>
 */
final class NotionJson {

    private NotionJson() {
    }

    static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE, false)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
}
