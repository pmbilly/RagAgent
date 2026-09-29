package com.ragagent.common.web;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * 读 JSON 的统一策略：忽略未知属性。
 *
 * <p>落库 jsonb 与模型/外部返回的 JSON 都可能带有本仓未知的键，逐类挂
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} 是把同一条策略复制多份；
 * 统一由本工厂（以及 Spring MVC 侧的 {@code spring.jackson} 配置）承担。
 */
public final class JsonMappers {

    private JsonMappers() {
    }

    /** 宽松 reader/writer：未知属性不报错，其余与 {@code new ObjectMapper()} 等价。 */
    public static ObjectMapper lenient() {
        return JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }
}
