package com.ragagent.common.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 加解密的环境配置。
 *
 * <p>env 名保持原样：{@code SYSTEM_AES_KEY} → {@code system.aes-key}（Spring 松散绑定）。
 * 原始串原样承载：长度校验（恰 32 字节才可用）留在 {@link CryptoService}，语义未变。</p>
 */
@ConfigurationProperties(prefix = "system")
public record CryptoEnvProperties(String aesKey) {
}
