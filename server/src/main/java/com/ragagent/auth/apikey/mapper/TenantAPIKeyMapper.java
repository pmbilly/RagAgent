package com.ragagent.auth.apikey.mapper;

import java.time.OffsetDateTime;
import java.util.List;

import com.ragagent.auth.apikey.domain.APIKeyRawJsonbTypeHandler;
import com.ragagent.auth.apikey.domain.APIKeyStringListTypeHandler;
import com.ragagent.auth.apikey.domain.TenantAPIKey;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * tenant_api_keys 仓储语句（对照 Go internal/application/repository/tenant_api_key.go，
 * L23-171）。
 *
 * <p>GORM 隐式行为清单（约定 §3）：</p>
 * <ul>
 *   <li>本表<b>无软删除列</b>：撤销是显式的 {@code revoked_at IS NULL} 条件
 *       （每一条 SQL 都显式写出，不依赖拦截器）。</li>
 *   <li><b>默认排序</b>：两个 List 方法都是 {@code ORDER BY created_at DESC}
 *       （Go 的 {@code Order("created_at DESC")}）。</li>
 *   <li><b>自动时间戳</b>：Go 交 GORM 写 created_at/updated_at；这里每条语句显式赋值。</li>
 *   <li><b>加密写入</b>：{@code api_key} 列由仓储层调用
 *       {@code CryptoService.encryptAESGCM} 后再传进来（对照 Go 的 BeforeSave 钩子）。</li>
 *   <li><b>解密读取</b>：{@code selectByHash} <b>不解密</b>（对照 Go 的
 *       {@code Session{SkipHooks: true}}）；其余读语句都要解密（AfterFind）。
 *       解密动作在 {@link TenantAPIKeyRepository} 里做，不在这里——
 *       这样"哪条读路径解密"与 Go 的钩子开关一一对应、肉眼可查。</li>
 *   <li><b>jsonb 列</b>：写路径用 {@link APIKeyRawJsonbTypeHandler}（参数是**已编码文本**，
 *       null 编码为字面量 {@code null}）；读路径用 {@link APIKeyStringListTypeHandler}。
 *       两套 handler 的分工见前者的类注释（NOT NULL 列不能写 SQL NULL）。</li>
 * </ul>
 */
@Mapper
public interface TenantAPIKeyMapper {

    /** 占位摘要前缀（迁移 000065 写入 {@code 'migrated-tenant-' || id}）。 */
    String PLACEHOLDER_HASH_PREFIX = "migrated-tenant-";

    /** 结果映射：jsonb 字符串数组列走 {@link APIKeyStringListTypeHandler}（读路径）。 */
    @Results(id = "tenantAPIKeyResult", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "scope_type", property = "scopeType"),
            @Result(column = "name", property = "name"),
            @Result(column = "key_hash", property = "keyHash"),
            @Result(column = "api_key", property = "apiKey"),
            @Result(column = "full_access", property = "fullAccess"),
            @Result(column = "knowledge_base_ids", property = "knowledgeBaseIds",
                    typeHandler = APIKeyStringListTypeHandler.class),
            @Result(column = "capabilities", property = "capabilities",
                    typeHandler = APIKeyStringListTypeHandler.class),
            @Result(column = "last_used_at", property = "lastUsedAt"),
            @Result(column = "expires_at", property = "expiresAt"),
            @Result(column = "revoked_at", property = "revokedAt"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt"),
    })
    /** 对照 {@code GetAPIKeyByHash}：唯一索引命中，零行 → null（调用方转"未找到"）。 */
    /** 对照 {@code GetAPIKeyByHash}：唯一索引命中，零行 → null（调用方转"未找到"）。 */
    @Select("SELECT * FROM tenant_api_keys WHERE key_hash = #{hash} LIMIT 1")
    TenantAPIKey selectByHash(@Param("hash") String hash);

    /**
     * 对照 {@code ListAPIKeys}：租户边界 + 未撤销 + created_at DESC。
     *
     * <p>⚠️ 每条读语句都必须显式 {@code @ResultMap("tenantAPIKeyResult")}——
     * MyBatis 的 {@code @Results(id=...)} **只作用于声明它的那个方法**，
     * 不写就会退化成自动映射，两个 jsonb 数组列会因为拿不到
     * {@link APIKeyStringListTypeHandler} 而读成 null
     * （表现为"更新后 capabilities 变空数组"这类隐蔽错）。</p>
     */
    @ResultMap("tenantAPIKeyResult")
    @Select("SELECT * FROM tenant_api_keys WHERE tenant_id = #{tenantId} AND revoked_at IS NULL "
            + "ORDER BY created_at DESC")
    List<TenantAPIKey> listByTenant(@Param("tenantId") long tenantId);

    /** 对照 {@code ListPlatformAPIKeys}：平台级 + 未撤销 + created_at DESC。 */
    @ResultMap("tenantAPIKeyResult")
    @Select("SELECT * FROM tenant_api_keys WHERE scope_type = 'platform' AND revoked_at IS NULL "
            + "ORDER BY created_at DESC")
    List<TenantAPIKey> listPlatform();

    /**
     * 对照 {@code UpdateAPIKey} 的复查读：条件比 update 宽（不带 scope_type），
     * 与 Go 的第二次 {@code First(...)} 完全一致。
     */
    @ResultMap("tenantAPIKeyResult")
    @Select("SELECT * FROM tenant_api_keys WHERE id = #{id} AND tenant_id = #{tenantId} "
            + "AND revoked_at IS NULL LIMIT 1")
    TenantAPIKey selectByIdForTenant(@Param("id") long id, @Param("tenantId") long tenantId);

    /**
     * 对照 {@code CreateAPIKey}。
     *
     * <p>{@code tenant_id} 传 null 即为平台级 Key（迁移 000071 已放开 NOT NULL，
     * CHECK 约束要求 platform 必须 tenant_id IS NULL 且 full_access = FALSE）。</p>
     */
    @Insert("INSERT INTO tenant_api_keys "
            + "(tenant_id, scope_type, name, key_hash, api_key, full_access, "
            + "knowledge_base_ids, capabilities, expires_at, created_at, updated_at) VALUES ("
            + "#{tenantId}, #{scopeType}, #{name}, #{keyHash}, #{apiKey}, #{fullAccess}, "
            + "#{knowledgeBaseIdsJson, typeHandler=com.ragagent.auth.apikey.domain.APIKeyRawJsonbTypeHandler}, "
            + "#{capabilitiesJson, typeHandler=com.ragagent.auth.apikey.domain.APIKeyRawJsonbTypeHandler}, "
            + "#{expiresAt}, #{createdAt}, #{updatedAt})")
    int insert(@Param("tenantId") Long tenantId,
               @Param("scopeType") String scopeType,
               @Param("name") String name,
               @Param("keyHash") String keyHash,
               @Param("apiKey") String apiKey,
               @Param("fullAccess") boolean fullAccess,
               @Param("knowledgeBaseIdsJson") String knowledgeBaseIdsJson,
               @Param("capabilitiesJson") String capabilitiesJson,
               @Param("expiresAt") OffsetDateTime expiresAt,
               @Param("createdAt") OffsetDateTime createdAt,
               @Param("updatedAt") OffsetDateTime updatedAt);

    /**
     * 对照 {@code UpdateAPIKey} 的 UPDATE：{@code tenant_id} 与 {@code scope_type}
     * **同时**参与条件，避免跨租户或误改平台级 Key。
     *
     * @return 受影响行数（0 = 目标不存在 / 不属于该租户 / 已撤销 / 是平台级）
     */
    @Update("UPDATE tenant_api_keys SET name = #{name}, full_access = #{fullAccess}, "
            + "knowledge_base_ids = #{knowledgeBaseIdsJson, typeHandler=com.ragagent.auth.apikey.domain.APIKeyRawJsonbTypeHandler}, "
            + "capabilities = #{capabilitiesJson, typeHandler=com.ragagent.auth.apikey.domain.APIKeyRawJsonbTypeHandler}, "
            + "expires_at = #{expiresAt}, updated_at = #{updatedAt} "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} AND scope_type = 'tenant' "
            + "AND revoked_at IS NULL")
    int updateForTenant(@Param("id") long id,
                        @Param("tenantId") long tenantId,
                        @Param("name") String name,
                        @Param("fullAccess") boolean fullAccess,
                        @Param("knowledgeBaseIdsJson") String knowledgeBaseIdsJson,
                        @Param("capabilitiesJson") String capabilitiesJson,
                        @Param("expiresAt") OffsetDateTime expiresAt,
                        @Param("updatedAt") OffsetDateTime updatedAt);

    /** 对照 {@code RevokeAPIKey}：租户边界 + 未撤销。 */
    @Update("UPDATE tenant_api_keys SET revoked_at = #{revokedAt}, updated_at = #{updatedAt} "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} AND revoked_at IS NULL")
    int revokeForTenant(@Param("id") long id,
                        @Param("tenantId") long tenantId,
                        @Param("revokedAt") OffsetDateTime revokedAt,
                        @Param("updatedAt") OffsetDateTime updatedAt);

    /** 对照 {@code RevokePlatformAPIKey}：只认平台级作用域，**不带租户条件**。 */
    @Update("UPDATE tenant_api_keys SET revoked_at = #{revokedAt}, updated_at = #{updatedAt} "
            + "WHERE id = #{id} AND scope_type = 'platform' AND revoked_at IS NULL")
    int revokePlatform(@Param("id") long id,
                       @Param("revokedAt") OffsetDateTime revokedAt,
                       @Param("updatedAt") OffsetDateTime updatedAt);

    /**
     * 对照 {@code UpdateAPIKeyHash}：**不带租户条件**（Go 原样如此），
     * 只要求未撤销。
     */
    @Update("UPDATE tenant_api_keys SET key_hash = #{hash}, updated_at = #{updatedAt} "
            + "WHERE id = #{id} AND revoked_at IS NULL")
    int updateKeyHash(@Param("id") long id,
                      @Param("hash") String hash,
                      @Param("updatedAt") OffsetDateTime updatedAt);

    /**
     * 对照 {@code HasKeysWithPlaceholderHash}：{@code SELECT id ... LIMIT 1}，
     * 零行 → null（Go 用 {@code id != 0} 判定）。
     */
    @Select("SELECT id FROM tenant_api_keys WHERE key_hash LIKE #{prefix} AND revoked_at IS NULL LIMIT 1")
    Long selectFirstPlaceholderHashId(@Param("prefix") String prefix);

    /** 对照 {@code ListKeysWithPlaceholderHash}：调用方拿到的是**解密后**的明文，用于重算摘要。 */
    @ResultMap("tenantAPIKeyResult")
    @Select("SELECT * FROM tenant_api_keys WHERE key_hash LIKE #{prefix} AND revoked_at IS NULL")
    List<TenantAPIKey> listByPlaceholderHash(@Param("prefix") String prefix);

    /** 对照 {@code UpdateAPIKeyLastUsed}：只按 id + 未撤销。 */
    @Update("UPDATE tenant_api_keys SET last_used_at = #{at}, updated_at = #{updatedAt} "
            + "WHERE id = #{id} AND revoked_at IS NULL")
    int updateLastUsed(@Param("id") long id,
                       @Param("at") OffsetDateTime at,
                       @Param("updatedAt") OffsetDateTime updatedAt);
}
