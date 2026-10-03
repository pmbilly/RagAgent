package com.ragagent.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 签名密钥的部署配置。
 *
 * <p>env 名保持原样：{@code JWT_SECRET} → {@code jwt.secret}（Spring 松散绑定）。</p>
 *
 * <p>只承载原始串：trim、空白视为未配置、未配置时随机 32 字节兜底（进程内一次，重启即换）
 * 的规则留在 {@code JwtService}/{@code OidcStateCodec}——两者各自兜底的行为也与改前一致。</p>
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(String secret) {
}
