package com.ragagent.auth.apikey.mapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.ragagent.auth.apikey.domain.APIKeyStringListTypeHandler;
import com.ragagent.auth.apikey.domain.TenantAPIKey;
import com.ragagent.common.crypto.CryptoService;
import org.springframework.stereotype.Component;

/**
 * 租户 API Key 仓储。
 *
 * <h2>加解密与落库行为清单</h2>
 * <ol>
 *   <li><b>写路径加密</b>：已配置 AES 密钥且 {@code apiKey} 非空时，
 *       先 {@code EncryptAESGCM} 再落列值，
 *       失败即中断写入。<br>
 *       → 落点：{@link #create} 里 {@code encryptForStorage(key.getApiKey())}。
 *       <b>内存对象保持明文</b>（只覆盖落库列值；创建响应要回显明文）。</li>
 *   <li><b>读路径解密</b>：解密失败**抛错**。<br>
 *       → 落点：{@link #decryptRow}，在每条需要解密的读路径后调用。</li>
 *   <li><b>按 hash 认证查找不解密</b>——这条路径只读非秘密列。
 *       占位摘要 id 探测同样与解密无关；占位摘要列表
 *       **必须解密**，调用方拿到的必须是明文才能重算摘要。三条路径的开关逐个对齐，
 *       见各方法注释。</li>
 *   <li><b>更新语义</b>：name/full_access/knowledge_base_ids/capabilities/expires_at
 *       **无条件覆盖**（改成空值也真的写空）。Java 侧 UPDATE 语句逐列 SET，同一语义。</li>
 *   <li><b>RowsAffected == 0 → not found</b>：
 *       撤销/更新未命中一律 {@link TenantAPIKeyNotFoundException}。</li>
 * </ol>
 */
@Component
public class TenantAPIKeyRepository {

    private final TenantAPIKeyMapper mapper;
    private final CryptoService crypto;

    public TenantAPIKeyRepository(TenantAPIKeyMapper mapper, CryptoService crypto) {
        this.mapper = mapper;
        this.crypto = crypto;
    }

    // ── 写 ──

    /**
     * 新增（含写路径加密）。
     * 加密失败**绝不放行明文**：直接抛 {@code IllegalStateException}。
     *
     * <p><b>主键回填</b>：INSERT 后需要把自增主键写回内存对象，
     * 调用方随后用 {@code key.getId()} 建响应、
     * 做认证后的 last_used 节流。本 INSERT 是批量标量参数，拿不到生成键，
     * 因此插入后按唯一键 {@code key_hash} 复查一次把 id 拷回内存对象。</p>
     */
    public void create(TenantAPIKey key) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (key.getCreatedAt() == null) {
            key.setCreatedAt(now);
        }
        if (key.getUpdatedAt() == null) {
            key.setUpdatedAt(now);
        }
        mapper.insert(
                key.getTenantId(),
                key.getScopeType(),
                key.getName(),
                key.getKeyHash(),
                encryptForStorage(key.getApiKey()),
                key.isFullAccess(),
                APIKeyStringListTypeHandler.encode(key.getKnowledgeBaseIds()),
                APIKeyStringListTypeHandler.encode(key.getCapabilities()),
                key.getExpiresAt(),
                key.getCreatedAt(),
                key.getUpdatedAt());
        // 主键回填（见方法注释）：只拷 id，绝不覆盖 api_key
        // —— 内存对象里必须是**明文**（创建响应要回显），而库里那列可能是密文。
        TenantAPIKey persisted = mapper.selectByHash(key.getKeyHash());
        if (persisted != null) {
            key.setId(persisted.getId());
        }
    }

    /**
     * 更新：先 UPDATE（租户 + scope_type 双重边界），
     * 零行 → not found；再按 {@code id + tenant_id + revoked_at IS NULL} 复查
     * （**复查条件不带 scope_type**，刻意比 update 宽）。
     *
     * <p>注意：入参 {@code update} 只带可配置列——{@code tenant_id}/{@code scope_type}/
     * {@code key_hash}/{@code api_key} 都不参与更新。</p>
     */
    public TenantAPIKey update(long tenantId, long id, TenantAPIKey update) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int rows = mapper.updateForTenant(
                id, tenantId, update.getName(), update.isFullAccess(),
                APIKeyStringListTypeHandler.encode(update.getKnowledgeBaseIds()),
                APIKeyStringListTypeHandler.encode(update.getCapabilities()),
                update.getExpiresAt(), now);
        if (rows == 0) {
            throw new TenantAPIKeyNotFoundException();
        }
        TenantAPIKey updated = mapper.selectByIdForTenant(id, tenantId);
        if (updated == null) {
            throw new TenantAPIKeyNotFoundException();
        }
        return decryptRow(updated);
    }

    /** 软撤销，零行 → not found。 */
    public void revoke(long tenantId, long id) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (mapper.revokeForTenant(id, tenantId, now, now) == 0) {
            throw new TenantAPIKeyNotFoundException();
        }
    }

    /** 撤销平台级 Key，零行 → not found。 */
    public void revokePlatform(long id) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (mapper.revokePlatform(id, now, now) == 0) {
            throw new TenantAPIKeyNotFoundException();
        }
    }

    /** 迁移回填用：更新 key_hash，**不带租户边界**（刻意如此）。 */
    public void updateKeyHash(long id, String hash) {
        mapper.updateKeyHash(id, hash, OffsetDateTime.now(ZoneOffset.UTC));
    }

    /** 更新最后使用时间。 */
    public void updateLastUsed(long id, OffsetDateTime at) {
        mapper.updateLastUsed(id, at, OffsetDateTime.now(ZoneOffset.UTC));
    }

    // ── 读 ──

    /**
     * 按 hash 查 Key：<b>不解密</b>。
     *
     * <p>语义上这是安全的：认证只需要 {@code key_hash} 命中，
     * 调用方（认证通道）也只读 revoked_at/expires_at/full_access/
     * capabilities 这些非秘密列。真实用户拿到明文 {@code api_key} 的路径是
     * 管理端点的 List/Update。</p>
     */
    public TenantAPIKey getByHash(String hash) {
        TenantAPIKey key = mapper.selectByHash(hash);
        if (key == null) {
            throw new TenantAPIKeyNotFoundException();
        }
        return key;
    }

    /** 租户 Key 列表：逐行解密。 */
    public List<TenantAPIKey> listByTenant(long tenantId) {
        return decryptAll(mapper.listByTenant(tenantId));
    }

    /** 平台 Key 列表：逐行解密。 */
    public List<TenantAPIKey> listPlatform() {
        return decryptAll(mapper.listPlatform());
    }

    /**
     * 是否存在占位摘要的 Key：{@code SELECT id ... LIMIT 1}，
     * 零行 → null → false。
     */
    public boolean hasKeysWithPlaceholderHash() {
        Long id = mapper.selectFirstPlaceholderHashId(TenantAPIKeyMapper.PLACEHOLDER_HASH_PREFIX + "%");
        return id != null && id != 0L;
    }

    /**
     * 占位摘要 Key 列表：**逐行解密**，
     * 调用方拿到的是解密后的明文，用于重算真实 SHA-256。
     */
    public List<TenantAPIKey> listKeysWithPlaceholderHash() {
        return decryptAll(mapper.listByPlaceholderHash(TenantAPIKeyMapper.PLACEHOLDER_HASH_PREFIX + "%"));
    }

    // ── 加解密 ──

    /**
     * 写路径加密：已配置 AES 密钥且明文非空才加密；
     * 加密失败抛错而非静默存明文。
     */
    private String encryptForStorage(String plaintext) {
        byte[] aesKey = crypto.getAESKey();
        if (aesKey == null || plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        try {
            return crypto.encryptAESGCM(plaintext, aesKey);
        } catch (RuntimeException e) {
            throw new IllegalStateException("encrypt tenant_api_keys.api_key failed", e);
        }
    }

    /**
     * 严格解密——带 {@code enc:v1:} 前缀但密钥缺失/解密失败时**抛错**。
     * 历史上这条路径曾让整个列表接口 500，
     * 但那是刻意选择：宁可响亮失败，也不静默把秘密置空后覆盖回库。
     */
    private TenantAPIKey decryptRow(TenantAPIKey key) {
        if (key == null) {
            return null;
        }
        key.setApiKey(crypto.decryptStoredSecret(key.getApiKey()));
        return key;
    }

    private List<TenantAPIKey> decryptAll(List<TenantAPIKey> keys) {
        List<TenantAPIKey> out = new ArrayList<>(keys == null ? 0 : keys.size());
        if (keys == null) {
            return out;
        }
        for (TenantAPIKey key : keys) {
            out.add(decryptRow(key));
        }
        return out;
    }
}
