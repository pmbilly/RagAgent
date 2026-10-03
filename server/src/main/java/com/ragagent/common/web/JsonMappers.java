package com.ragagent.common.web;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 读 JSON 的统一策略：忽略未知属性 + 标准 java.time 支持。
 *
 * <p>落库 jsonb 与模型/外部返回的 JSON 都可能带有本仓未知的键，逐类挂
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} 是把同一条策略复制多份；
 * 统一由本工厂（以及 Spring MVC 侧的 {@code spring.jackson} 配置）承担。
 *
 * <p><b>java.time 为什么必须在本工厂注册</b>：jsonb 读路径用的是这里造的 mapper，
 * 而裸 {@code new ObjectMapper()} 未注册 JSR-310 模块，遇到 {@code java.time} 字段会抛
 * {@code InvalidDefinitionException}。时间类型由本工厂统一按 ISO-8601 处理
 * （{@code WRITE_DATES_AS_TIMESTAMPS} 关闭，输出带偏移的字符串而非 epoch 数字）。</p>
 */
public final class JsonMappers {

    private JsonMappers() {
    }

    /**
     * 宽松 reader/writer：未知属性不报错，时间类型按 ISO-8601 处理，其余与
     * {@code new ObjectMapper()} 等价。
     */
    public static ObjectMapper lenient() {
        return JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }
}
