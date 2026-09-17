package com.ragagent.apikey.domain;

/**
 * API Key 的作用域类型（对照 Go {@code types.APIKeyScopeType}，
 * internal/types/tenant_api_key.go L40-54）。
 *
 * <p>Go 侧是 {@code type APIKeyScopeType string}——**不是枚举**，零值是 {@code ""}，
 * 但所有出口都过 {@link #normalize(String)}，任何无法识别的输入（含空串）都归为
 * {@code tenant}。Java 用字符串常量 + 静态归一化方法，保持同一语义；
 * 这样实体字段可以直连 DB 的 {@code varchar(16)} 列，不需要 TypeHandler。</p>
 */
public final class APIKeyScopeType {

    /** 对照 {@code APIKeyScopeTenant} */
    public static final String TENANT = "tenant";
    /** 对照 {@code APIKeyScopePlatform} */
    public static final String PLATFORM = "platform";

    private APIKeyScopeType() {
    }

    /**
     * 对照 {@code NormalizeAPIKeyScopeType}（L47-54）：
     * {@code strings.ToLower(strings.TrimSpace(...))} 后只认 {@code platform}，
     * 其余（含空串、null）一律回落到 {@code tenant}。
     */
    public static String normalize(String scope) {
        if (scope == null) {
            return TENANT;
        }
        return PLATFORM.equals(scope.trim().toLowerCase(java.util.Locale.ROOT)) ? PLATFORM : TENANT;
    }
}
