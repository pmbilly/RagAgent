package com.ragagent.config;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.ragagent.common.web.GoJsonEscapes;
import com.ragagent.common.web.GoWriterJsonFactory;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * HTTP 响应这个 ObjectMapper 的两项"对齐 Go"配置。
 *
 * <h2>1. OffsetDateTime ↔ Go 的 time.Time</h2>
 * <p>实测 Go dev server（gin/json，time.Time 来自 pgx 解码 + 本地时区）输出：
 * "2026-09-17T15:44:16.950624+08:00"——RFC3339Nano，服务器本地时区偏移。
 * Java 侧 JDBC 读出为 UTC 偏移，此处统一转为 JVM 默认时区再按 ISO_OFFSET_DATE_TIME 输出
 * （其小数部分对纳秒做尾部零裁剪，与 RFC3339Nano 字节一致）。</p>
 *
 * <h2>2. 字符串的 HTML 转义（{@link GoJsonEscapes}）</h2>
 * <p>Go 的 {@code encoding/json} **默认**把 {@code <} {@code >} {@code &} 转义成
 * {@code \u003c} / {@code \u003e} / {@code \u0026}（小写十六进制），Jackson 默认原样输出。
 * 实测确认这个差异是**全应用性**的：</p>
 * <pre>
 *   Go    : {"content":"a \u003c b \u0026 c \u003e d"}
 *   Spring: {"content":"a < b & c > d"}
 * </pre>
 *
 * <p>对**解析 JSON 的客户端**来说两者等价（解出来是同一个字符串），所以这不是前端可见的 bug。
 * 但本项目验收标准是「与 Go 实录**逐字节**一致」，而 A/B 的手段是 {@code diff}——
 * 不对齐的话每次对比都会被这类无害差异刷屏，真回归会被淹掉。</p>
 *
 * <p><b>为什么是全局装而不是只给踩到的那条路径打补丁</b>：这个差异早就存在，
 * 只是 golden 契约文件里 {@code < > &} 的出现次数一直是 <b>0</b>，从没被测到。
 * 聊天正文（散文）是第一个踩到的（SSE），但 KB 名称/描述、模型 description 同样会踩。
 * 只给 SSE 装会让"同一段文本在两条响应路径上给出不同字节"——
 * 那是将来排查 A/B 差异时反复踩的认知坑。</p>
 *
 * <p>⚠️ Jackson 的 {@code CharacterEscapes} 是**整表替换**而非叠加，
 * {@link GoJsonEscapes} 里已显式声明 {@code \b \t \n \f \r \" \\} 这些
 * "两边本来就一致"的短转义——漏掉它们会直接漏成原文（阶段 5 在 Redis 那条路径上踩过）。</p>
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

    /**
     * 给 HTTP 响应用的 JsonFactory 装上 Go 的转义表。
     *
     * <p>用 {@code postConfigurer} 而不是 {@code characterEscapes(...)}：
     * 转义表挂在 **JsonFactory** 上、不在 mapper 上，得拿到建好的 mapper 才能装。</p>
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer goJsonEscapesCustomizer() {
        return builder -> builder.postConfigurer(
                mapper -> mapper.getFactory().setCharacterEscapes(new GoJsonEscapes()));
    }

    /**
     * HTTP 响应走 Writer 路径的 JsonFactory（{@link GoWriterJsonFactory}）：
     * 修复补充字符（emoji）被 UTF8JsonGenerator 转义成代理对 {@code \uD83D\uDCDA}、
     * 而 Go 输出 raw UTF-8 的字节差。装在 HTTP mapper 上，不影响 jsonb/存储侧
     * 各服务自建的 ObjectMapper。
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer goWriterJsonFactoryCustomizer() {
        return builder -> builder.factory(new GoWriterJsonFactory());
    }

    /**
     * float[] 的 Go 字节形态（models/{id}/debug 的 embedding raw_response）。
     * float[] 在既有响应面不出现，按类型注册无传染面（对照约定 §9 的
     * "double/map 全局注册会污染"教训——这里仅注册数组类型本身）。
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer goFloatArrayCustomizer() {
        return builder -> builder.serializerByType(float[].class,
                new com.ragagent.common.web.GoFloatArraySerializer());
    }
}
