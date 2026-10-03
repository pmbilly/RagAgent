package com.ragagent.auth.apikey.domain;

/**
 * API Key 的作用域类型。
 *
 * <p>用字符串常量 + 静态归一化方法表达（不引入枚举）：所有出口都过
 * {@link #normalize(String)}，任何无法识别的输入（含空串）都归为
 * {@code tenant}；实体字段可以直连 DB 的 {@code varchar(16)} 列，不需要 TypeHandler。</p>
 */
public final class APIKeyScopeType {

    /** 租户作用域（默认）。 */
    public static final String TENANT = "tenant";
    /** 平台作用域。 */
    public static final String PLATFORM = "platform";

    private APIKeyScopeType() {
    }

    /**
     * 去空白 + 转小写后只认 {@code platform}，
     * 其余（含空串、null）一律回落到 {@code tenant}。
     */
    public static String normalize(String scope) {
        if (scope == null) {
            return TENANT;
        }
        return PLATFORM.equals(scope.trim().toLowerCase(java.util.Locale.ROOT)) ? PLATFORM : TENANT;
    }
}
