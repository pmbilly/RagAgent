package com.ragagent.auth.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * tenants.api_principal_config（jsonb）的载荷类型
 * （对照 Go types/tenant.go {@code APIPrincipalConfig}，含 Value()/Scan() 的加密语义）。
 *
 * <p><b>未知键容忍</b>：Go 的 {@code json.Unmarshal} 默认忽略未知字段（§9 波 2 FAQ：
 * 裸 SQL / 新迁移写的行可能有 Java 实体不认识的键）→
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

    @JsonProperty("mode")
    public String mode = "";

    @JsonProperty("direct_header_name")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public String directHeaderName = "";

    @JsonProperty("signed_token_header_name")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public String signedTokenHeaderName = "";

    /** omitempty 的 bool：false 省略 */
    @JsonProperty("require_direct_header")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public boolean requireDirectHeader;

    /** TypeHandler 写库前加密；本字段持有明文（omitempty：空串省略） */
    @JsonProperty("hmac_secret")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public String hmacSecret = "";
}
