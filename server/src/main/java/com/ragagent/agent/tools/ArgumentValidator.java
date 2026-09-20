package com.ragagent.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 可选的自定义参数校验接口（对照 Go registry 里的匿名接口断言
 * {@code interface{ ValidateArguments(json.RawMessage) error }}，registry.go:195）。
 *
 * <p>registry 在执行前探测：实现了本接口的工具走自定义校验（如 MCP 工具的完整
 * JSON Schema 校验），否则走 {@link ParamValidator} 的通用 schema 校验。</p>
 */
public interface ArgumentValidator {

    /**
     * @return 校验通过返回 null；失败返回错误文案（写进 "Parameter validation failed: " 之后）。
     */
    String validateArguments(JsonNode args);
}
