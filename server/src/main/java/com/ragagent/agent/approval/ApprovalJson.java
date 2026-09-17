package com.ragagent.agent.approval;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * 本包的 JSON 工具（对照 Go 的 encoding/json 直接调用点）。
 *
 * <p>用独立的 ObjectMapper 而不是注入 Spring 的：本包的 JSON 只用于
 * <b>实例间 pubsub 报文</b>与事件体解析，字段名全部由注解显式钉死，
 * 不应受 web 层 ObjectMapper 定制（时区、命名策略）影响。</p>
 */
final class ApprovalJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            // 对照 Go：未知字段被忽略（跨版本滚动升级时新旧实例互不报错）
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
     * 解析为指定类型；失败返回 {@code null}（对照 Go 里
     * {@code json.Unmarshal 失败 → log.Warnf + continue} 的调用点，由调用方决定是否告警）。
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
     * 把原始 JSON 字符串解析成 JSON 树（对照 Go 的 {@code json.RawMessage} 语义）。
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
     * 把原始 JSON 字符串解析成通用对象（对照 Go
     * {@code var argsObj interface{}; _ = json.Unmarshal(req.Args, &argsObj)}）：
     * 失败被忽略，事件体里的 {@code args} 保持为 null。
     */
    static Object parseLoose(String rawJson) {
        return rawNode(rawJson);
    }
}
