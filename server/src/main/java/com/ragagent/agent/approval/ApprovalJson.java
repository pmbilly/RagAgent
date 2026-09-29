package com.ragagent.agent.approval;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * 本包的 JSON 工具。
 *
 * <p>用独立的 ObjectMapper 而不是注入 Spring 的：本包的 JSON 只用于
 * <b>实例间 pubsub 报文</b>与事件体解析，JSON 键名 = record 组件名（Java 字段名,无逐字段注解），
 * 不应受 web 层 ObjectMapper 定制（时区、命名策略）影响。</p>
 */
final class ApprovalJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            // 未知字段被忽略（跨版本滚动升级时新旧实例互不报错）
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private ApprovalJson() {
    }

    static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw ApprovalException.internal("encode approval payload: " + e.getMessage(), e);
        }
    }

    /**
     * 解析为指定类型；失败返回 {@code null}，由调用方决定如何处理（如记日志后跳过）。
     */
    static <T> T read(String json, Class<T> type) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 把原始 JSON 字符串解析成 JSON 树。
     * 空串/非法 JSON 返回 {@code null}。
     */
    static JsonNode rawNode(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(rawJson);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 把原始 JSON 字符串解析成通用对象；失败被忽略，事件体里的 {@code args} 保持为 null。
     */
    static Object parseLoose(String rawJson) {
        return rawNode(rawJson);
    }
}
