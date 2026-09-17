package com.ragagent.config;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OffsetDateTime 的 JSON 序列化对齐 Go time.Time。
 *
 * 实测 Go dev server（gin/json，time.Time 来自 pgx 解码 + 本地时区）输出：
 * "2026-09-17T15:44:16.950624+08:00"——RFC3339Nano，服务器本地时区偏移。
 * Java 侧 JDBC 读出为 UTC 偏移，此处统一转为 JVM 默认时区再按 ISO_OFFSET_DATE_TIME 输出
 * （其小数部分对纳秒做尾部零裁剪，与 RFC3339Nano 字节一致）。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer offsetDateTimeCustomizer() {
        return builder -> builder.serializerByType(OffsetDateTime.class, new JsonSerializer<OffsetDateTime>() {
            @Override
            public void serialize(OffsetDateTime value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                OffsetDateTime local = value.atZoneSameInstant(ZoneId.systemDefault()).toOffsetDateTime();
                gen.writeString(local.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            }
        });
    }
}
