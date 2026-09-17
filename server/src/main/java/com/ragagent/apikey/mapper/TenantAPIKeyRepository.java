package com.ragagent.apikey.mapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.ragagent.apikey.domain.APIKeyStringListTypeHandler;
import com.ragagent.apikey.domain.TenantAPIKey;
import com.ragagent.common.crypto.CryptoService;
import org.springframework.stereotype.Component;

/**
 * 租户 API Key 仓储（对照 Go internal/application/repository/tenant_api_key.go，
 * 整个文件）。
 *
 * <h2>GORM 钩子 → Java 代码的等效清单（约定 §3 要求显式列出）</h2>
 * <ol>
 *   <li><b>BeforeSave（Go L245-257）</b>：{@code utils.GetAESKey() != nil && k.APIKey != ""}
 *       时 {@code EncryptAESGCM} 后 {@code tx.Statement.SetColumn("api_key", encrypted)}，
 *       失败即中断写入。<br>
 *       → Java：{@link #create} 里 {@code encryptForStorage(key.getApiKey())}。
 *       <b>内存对象保持明文</b>（Go 的 SetColumn 不改 struct 字段；创建响应要回显明文）。</li>
 *   <li><b>AfterFind（Go L259-266）</b>：{@code DecryptStoredSecret}，失败**抛错**。<br>
 *       → Java：{@link #decryptRow}，在每条需要解密的读路径后调用。</li>
 *   <li><b>SkipHooks（Go L29）</b>：{@code GetAPIKeyByHash} 跳过 AfterFind
 *       ——**这条路径不解密**。{@code HasKeysWithPlaceholderHash} 同样 SkipHooks，
 *       但它只 Select("id")，本就与解密无关；{@code ListKeysWithPlaceholderHash}
 *       **不跳过**，调用方拿到的必须是明文才能重算摘要。三条路径的开关逐个对齐，
 *       见各方法注释。</li>
 *   <li><b>map + Updates（Go L64-74）</b>：Go 用 map 绕开 GORM 的零值省略，
 *       保证 name/full_access/knowledge_base_ids/capabilities/expires_at **无条件覆盖**
 *       （改成空值也真的写空）。Java 侧 UPDATE 语句逐列 SET，同一语义。</li>
 *   <li><b>RowsAffected == 0 → not found（Go L78-80 / L103-105 / L118-120）</b>：
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
     * 对照 {@code CreateAPIKey} + BeforeSave 钩子。
     * 加密失败**绝不放行明文**：直接抛（对照 Go 的 {@code fmt.Errorf("encrypt ...")}）。
     *
     * <p><b>主键回填</b>：GORM 的 {@code Create} 会把自增主键写回 struct
     * （PG 走 {@code RETURNING id}），调用方随后用 {@code key.ID} 建响应、
     * 做认证后的 last_used 节流。Java 侧的 INSERT 是批量标量参数，拿不到生成键，
     * 因此插入后按唯一键 {@code key_hash} 复查一次把 id 拷回内存对象——
     * 净效果与 GORM 一致（多一次主键命中的 SELECT）。</p>
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
     * 对照 {@code UpdateAPIKey}：先 UPDATE（租户 + scope_type 双重边界），
     * 零行 → not found；再按 {@code id + tenant_id + revoked_at IS NULL} 复查
     * （**复查条件不带 scope_type**，与 Go 的第二次 First 一致）。
     *
     * <p>注意：入参 {@code update} 只带可配置列——{@code tenant_id}/{@code scope_type}/
     * {@code key_hash}/{@code api_key} 都不参与更新（Go 的 map 里也没有它们）。</p>
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

    /** 对照 {@code RevokeAPIKey}：软撤销，零行 → not found。 */
    public void revoke(long tenantId, long id) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (mapper.revokeForTenant(id, tenantId, now, now) == 0) {
            throw new TenantAPIKeyNotFoundException();
        }
    }

    /** 对照 {@code RevokePlatformAPIKey}。 */
    public void revokePlatform(long id) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (mapper.revokePlatform(id, now, now) == 0) {
            throw new TenantAPIKeyNotFoundException();
        }
    }

    /** 对照 {@code UpdateAPIKeyHash}：迁移回填用，**不带租户边界**（Go 原样如此）。 */
    public void updateKeyHash(long id, String hash) {
        mapper.updateKeyHash(id, hash, OffsetDateTime.now(ZoneOffset.UTC));
    }

    /** 对照 {@code UpdateAPIKeyLastUsed}。 */
    public void updateLastUsed(long id, OffsetDateTime at) {
        mapper.updateLastUsed(id, at, OffsetDateTime.now(ZoneOffset.UTC));
    }

    // ── 读 ──

    /**
     * 对照 {@code GetAPIKeyByHash}：<b>SkipHooks → 不解密</b>。
     *
     * <p>语义上这是安全的：认证只需要 {@code key_hash} 命中，
     * 调用方（{@code AuthenticateAPIKey}）也只读 revoked_at/expires_at/full_access/
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

    /** 对照 {@code ListAPIKeys}：AfterFind → 逐行解密。 */
    public List<TenantAPIKey> listByTenant(long tenantId) {
        return decryptAll(mapper.listByTenant(tenantId));
    }

    /** 对照 {@code ListPlatformAPIKeys}。 */
    public List<TenantAPIKey> listPlatform() {
        return decryptAll(mapper.listPlatform());
    }

    /**
     * 对照 {@code HasKeysWithPlaceholderHash}：Go 用 {@code Scan(&id)} 后判
     * {@code id != 0}（零行时 id 保持 0）。
     */
    public boolean hasKeysWithPlaceholderHash() {
        Long id = mapper.selectFirstPlaceholderHashId(TenantAPIKeyMapper.PLACEHOLDER_HASH_PREFIX + "%");
        return id != null && id != 0L;
    }

    /**
     * 对照 {@code ListKeysWithPlaceholderHash}：**不 SkipHooks**，
     * 调用方拿到的是解密后的明文，用于重算真实 SHA-256。
     */
    public List<TenantAPIKey> listKeysWithPlaceholderHash() {
        return decryptAll(mapper.listByPlaceholderHash(TenantAPIKeyMapper.PLACEHOLDER_HASH_PREFIX + "%"));
    }

    // ── 加解密（对照 BeforeSave / AfterFind） ──

    /**
     * 对照 BeforeSave 的加密分支：{@code GetAESKey() != nil && APIKey != ""} 才加密；
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
     * 对照 AfterFind：严格解密——带 {@code enc:v1:} 前缀但密钥缺失/解密失败时**抛错**
     * （Go 的 {@code DecryptStoredSecret} 同）。历史上这条路径曾让整个列表接口 500，
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
