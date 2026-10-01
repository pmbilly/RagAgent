package com.ragagent.auth.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * tenants.api_principal_config（jsonb）的载荷类型
 * （对照 Go types/tenant.go {@code APIPrincipalConfig}，含 Value()/Scan() 的加密语义）。
 *
 * <p>键名即 Java 字段名（camelCase，§2 第 11 条；恒输出——空串/false/null 照写）。
 * <b>未知键容忍</b>：裸 SQL 写的行可能有实体不认识的键 →
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} 必挂。</p>
 *
 * <p><b>hmac_secret 的加密在 TypeHandler 层</b>（对照 Go 的 driver.Valuer/Scanner 钩子）：
 * 写库前 AES-256-GCM 加密（enc:v1: 前缀），读库后宽容解密（解密失败置空，
 * 对照 DecryptStoredSecretLenient——密钥缺失/轮换时行照常加载、密钥视为未配置）。
 * 本类型自身只持有明文形态。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class APIPrincipalConfig {
    public static final String MODE_TENANT = "tenant";
    public static final String MODE_DIRECT_HEADER = "direct_header";
    public static final String MODE_SIGNED_TOKEN = "signed_token";

    public String mode = "";

    public String directHeaderName = "";

    public String signedTokenHeaderName = "";

    public boolean requireDirectHeader;

    /** TypeHandler 写库前加密；本字段持有明文 */
    public String hmacSecret = "";
}
