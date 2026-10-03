package com.ragagent.llm.chat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;

import org.springframework.http.HttpHeaders;

/**
 * OpenAI 兼容后端中**厂商特有**的全部行为。
 *
 * 每个方法在 {@link BaseProvider} 都有合理默认值，新增厂商只需继承 BaseProvider
 * 并覆写真正不同的那一两个方法。
 *
 * **实现说明**：本接口统一用 {@link ObjectNode} 构造并发送请求体，
 * 不存在"强类型 SDK struct 带不了某些字段 → 转裸 HTTP 发原始 body"的问题，因此：
 * - 没有"强制裸 HTTP"开关
 * - `shapeRequest` 直接改 {@link ObjectNode}
 * 只有 `endpoint` 覆写与 `auth` 分叉仍需保留。
 */
public interface ProviderAdapter {

    /** 本适配器负责的 provider 名。 */
    String name();

    /**
     * 是否适用于给定模型名。用于子厂商路由（如阿里云内的 Qwen thinking 模型、
     * OpenAI 内的 reasoning 模型）。默认 true。
     */
    default boolean matches(String model) {
        return true;
    }

    /** 该厂商如何编码 ChatOptions.Thinking。默认不发送任何字段。 */
    default ThinkingStrategy thinking() {
        return new ThinkingStrategies.None();
    }

    /**
     * 就地对标准请求体做厂商特有的参数整形（剥掉不支持的字段、钉死 temperature 等）。
     * 默认 no-op。
     */
    default void shapeRequest(ObjectNode body, ChatOptions opts, boolean isStream) {
    }

    /**
     * 改写已转换的消息（如把多内容降级为纯文本）。默认原样返回。
     * 注意实现需**保留** tool_calls / tool_call_id / name，否则函数调用协议会断。
     */
    default List<ChatMessage> transformMessages(List<ChatMessage> messages) {
        return messages;
    }

    /** 覆写请求 URL；空串表示走调用方的标准 "<baseURL>/chat/completions"。默认空串。 */
    default String endpoint(String baseUrl, String modelId, boolean isStream) {
        return "";
    }

    /** 在原始 HTTP 请求上设置鉴权头。默认 Bearer。 */
    default void auth(HttpHeaders headers, AuthCreds creds, byte[] body) {
        headers.set("Authorization", "Bearer " + creds.apiKey());
    }

    /**
     * 从原始 OpenAI 兼容 tool_call 对象里抓取厂商特有状态。默认 null。
     * （典型：Gemini 的 extra_content.google 思考签名）
     */
    default Map<String, JsonNode> extractToolCallMetadata(JsonNode raw) {
        return null;
    }

    /** 把厂商特有状态写回出站的 tool_call 对象。默认 no-op。 */
    default void injectToolCallMetadata(ObjectNode toolCall, Map<String, JsonNode> metadata) {
    }

    /**
     * 原始 HTTP 请求鉴权所需的凭据。
     * apiKey 覆盖常见的 Bearer / api-key 场景；appId/appSecret 仅签名类厂商
     * （WeKnoraCloud）使用。
     */
    record AuthCreds(String apiKey, String appId, String appSecret) {
    }
}
