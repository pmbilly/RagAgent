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

    /**
     * 宽松读（Jackson 反序列化 / 落库行读取）：未知非空值 → {@code null}。
     *
     * <p><b>勿在写侧用它</b>——未知值静默变 null 会让"保存成功但策略丢失"
     * （2026-10-03 点检实锤：前端发 {@code "apiKey"}、本枚举取值是 {@code "api_key"}，
     * 遂把整条鉴权策略写成 null，凭据被当成 X-API-Key 发出、用户"配了不生效"）。
     * 写侧请用 {@link #parseStrict(String)}。</p>
     */
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

    /**
     * 严格解析（<b>写侧</b>专用）：空串/null → {@link #NONE}；未知值 → 抛异常。
     *
     * <p>调用方（如 {@code McpServiceCrudOps} 的 PUT 路径）应把异常转成 400，
     * 让前端拿到"取值不认识"的明确报错，而不是静默丢策略。</p>
     *
     * @throws IllegalArgumentException 取值非空且不是任何已知策略
     */
    public static McpAuthType parseStrict(String raw) {
        if (raw == null || raw.isEmpty()) {
            return NONE;
        }
        McpAuthType parsed = fromValue(raw);
        if (parsed == null) {
            throw new IllegalArgumentException("unknown authType \"" + raw
                    + "\"; expected one of: \"\" (none), api_key, bearer, oauth");
        }
        return parsed;
    }
}
