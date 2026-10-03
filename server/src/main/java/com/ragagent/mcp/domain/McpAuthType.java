package com.ragagent.mcp.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * MCP 服务的鉴权策略。
 *
 * NONE 的空串取值是**向后兼容契约**：早于该字段的历史行读出来就是空串，
 * 空串必须被当作"无鉴权"而不是未知值。
 */
public enum McpAuthType {

    /** 无鉴权（或仅静态自定义头）。值就是空串——别改成 "none"。 */
    NONE(""),
    /** 静态 API key 头（默认 X-API-Key） */
    API_KEY("api_key"),
    /** 静态 Authorization: Bearer &lt;token&gt; */
    BEARER("bearer"),
    /**
     * MCP OAuth2 授权码流程（发现 + 动态客户端注册 + PKCE），按用户维度。
     * Token 按 (tenant, user, service) 存在 mcp_oauth_tokens。
     */
    OAUTH("oauth");

    private final String value;

    McpAuthType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static McpAuthType fromValue(String v) {
        if (v == null || v.isEmpty()) {
            return NONE; // 空串 = 无鉴权（历史行兼容）
        }
        for (McpAuthType t : values()) {
            if (t.value.equals(v)) {
                return t;
            }
        }
        return null;
    }
}
