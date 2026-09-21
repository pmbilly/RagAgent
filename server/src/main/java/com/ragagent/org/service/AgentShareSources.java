package com.ragagent.org.service;

/**
 * 共享 agent 源空间选择子解析（对照 Go internal/types/agent_share_source.go
 * ParseAgentSourceTenantID）。
 *
 * <p>空/纯空白输入视为缺席（返回 0）；非空但非法的值抛
 * {@link IllegalArgumentException}，message 逐字对齐 Go 的
 * {@code fmt.Errorf("invalid %s: %w", ...)} 包装后的文案——调用方 fail-closed，
 * 不得静默回落。Go 内层是 strconv.ParseUint 的错误原文。</p>
 */
public final class AgentShareSources {

    public static final String AGENT_SOURCE_TENANT_ID_PARAM = "agent_source_tenant_id";

    private AgentShareSources() {}

    /**
     * 对照 ParseAgentSourceTenantID。
     *
     * @throws IllegalArgumentException message = "invalid agent_source_tenant_id:
     *         strconv.ParseUint: parsing \"&lt;raw&gt;\": invalid syntax" / "value out of range"
     */
    public static long parse(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty()) {
            return 0;
        }
        // Go strconv.ParseUint：接受可选前导 '+'，其余字符必须全为数字
        String digits = t.startsWith("+") ? t.substring(1) : t;
        if (digits.isEmpty() || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException("invalid " + AGENT_SOURCE_TENANT_ID_PARAM
                    + ": strconv.ParseUint: parsing \"" + t + "\": invalid syntax");
        }
        try {
            return Long.parseUnsignedLong(digits);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid " + AGENT_SOURCE_TENANT_ID_PARAM
                    + ": strconv.ParseUint: parsing \"" + t + "\": value out of range");
        }
    }
}
