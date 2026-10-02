package com.ragagent.storage.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.crypto.CryptoService;
import com.ragagent.common.error.BizException;
import com.ragagent.storage.dto.StorageConfig;
import org.springframework.stereotype.Component;

/**
 * 存储后端行配置（{@code storage_backends.config} jsonb）的<b>唯一读写口</b>：camel 行配置
 * ⇄ 落库 JSON，并在这一个地方负责凭据的加/解密。
 *
 * <p><b>为什么必须唯一</b>：这条链路上已经出过三个同源缺陷——引擎面凭据键分族、落库面词汇
 * 不符、以及<b>供给器绕过加密直接写库</b>（环境供给行的私钥以明文落库）。只要还存在第二条写入
 * 路径，第三类问题就会换个门再出现；因此"写行"必须只有这一个口。</p>
 *
 * <p>语义（与既有 {@code StorageBackendService.configOf/serializeConfig} 逐字一致）：</p>
 * <ul>
 *   <li>{@link #decode}：严格解密——带 {@code enc:v1:} 前缀才解密，失败抛
 *       {@link BizException}（拖垮行加载，与 Go 一致）；无前缀原样。</li>
 *   <li>{@link #encode}：凭据加密后才落库；无密钥（未配置 {@code SYSTEM_AES_KEY}）或已带前缀
 *       一律原样（{@link CryptoService#encryptAESGCM} 自身语义）。</li>
 * </ul>
 */
@Component
public class StorageConfigCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final CryptoService crypto;

    public StorageConfigCodec(CryptoService crypto) {
        this.crypto = crypto;
    }

    /** 库中 JSON → 行配置（camel）；凭据严格解密（失败拖垮行加载——与 Go 一致）。 */
    public StorageConfig decode(JsonNode stored) {
        if (stored == null || stored.isNull()) {
            return new StorageConfig();
        }
        try {
            StorageConfig c = MAPPER.treeToValue(stored, StorageConfig.class);
            c.accessKeyId = crypto.decryptStoredSecret(c.accessKeyId);
            c.secretAccessKey = crypto.decryptStoredSecret(c.secretAccessKey);
            return c;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("parse storage backend config failed", e);
        }
    }

    /**
     * 行配置（JsonNode，camel）→ 落库字符串：凭据加密。
     *
     * <p>{@code null} 配置按空 {@link StorageConfig} 处理（与既有写出路径同形）。</p>
     */
    public String encode(JsonNode rowConfig) {
        try {
            StorageConfig c = rowConfig == null || rowConfig.isNull()
                    ? new StorageConfig()
                    : MAPPER.treeToValue(rowConfig, StorageConfig.class);
            byte[] key = crypto.getAESKey();
            c.accessKeyId = crypto.encryptAESGCM(c.accessKeyId, key);
            c.secretAccessKey = crypto.encryptAESGCM(c.secretAccessKey, key);
            return MAPPER.writeValueAsString(c);
        } catch (Exception e) {
            throw new IllegalStateException("serialize storage backend config failed", e);
        }
    }
}
