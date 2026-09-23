package com.ragagent.sandbox.mapper;

import java.time.OffsetDateTime;
import java.util.List;

import com.ragagent.sandbox.domain.SkillEnvVarsTypeHandler;
import com.ragagent.sandbox.domain.TenantSkillCatalogEntity;
import com.ragagent.sandbox.domain.TenantSkillEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 对照 Go {@code repository.TenantSkillRepository} 的本批子集
 * （internal/application/repository/tenant_skill.go）+ catalog 存取
 * （同文件 L408 起）。快照台账方法（CreateSnapshotRow/MarkSnapshotState/
 * ListSnapshotsByConfig/DeleteSnapshotRowsByConfig）与 reaper 方法
 * （ListStaleInstalling）在安装/移除管线（波 4 接缝）里才被调用，本批不翻。
 *
 * <p>软删走显式 {@code deleted_at IS NULL}（§9 约定）；envs 列的 TypeHandler
 * 必须在 @Results 显式声明（§9：注解 SQL 不套实体注解）。UpdateSkill 刻意写
 * 可变列全集（Go 注释原文：让并发任务写入的状态不能被传入实体的零值悄悄抹掉；
 * envs 是唯一缺席的列——它是安装进度写入对之没有发言权的那一列，只有
 * UpdateSkillEnvs / UpdateSkillAdminState 写它）。</p>
 */
@Mapper
public interface TenantSkillMapper {

    String ENV_TH = "com.ragagent.sandbox.domain.SkillEnvVarsTypeHandler";

    String COLS = "id, tenant_id, sandbox_config_id, catalog_id, name, version, description, "
            + "instructions, bundle_ref, bundle_sha256, enabled, installed_snapshot_id, "
            + "install_session_id, install_message_id, envs, status, error, installing_since, "
            + "created_at, updated_at";

    @Results(id = "tenantSkillRow", value = {
            @Result(column = "envs", property = "envs", typeHandler = SkillEnvVarsTypeHandler.class)
    })
    @Select("SELECT " + COLS + " FROM tenant_skills "
            + "WHERE tenant_id = #{tenantId} AND sandbox_config_id = #{configId} "
            + "AND id = #{skillId} AND deleted_at IS NULL")
    TenantSkillEntity getSkill(@Param("tenantId") long tenantId,
            @Param("configId") String configId, @Param("skillId") String skillId);

    @Results(value = {
            @Result(column = "envs", property = "envs", typeHandler = SkillEnvVarsTypeHandler.class)
    })
    @Select("SELECT " + COLS + " FROM tenant_skills "
            + "WHERE tenant_id = #{tenantId} AND sandbox_config_id = #{configId} "
            + "AND name = #{name} AND deleted_at IS NULL")
    TenantSkillEntity getSkillByName(@Param("tenantId") long tenantId,
            @Param("configId") String configId, @Param("name") String name);

    @Results(value = {
            @Result(column = "envs", property = "envs", typeHandler = SkillEnvVarsTypeHandler.class)
    })
    @Select("SELECT " + COLS + " FROM tenant_skills "
            + "WHERE tenant_id = #{tenantId} AND sandbox_config_id = #{configId} "
            + "AND deleted_at IS NULL ORDER BY created_at ASC")
    List<TenantSkillEntity> listSkillsByConfig(@Param("tenantId") long tenantId,
            @Param("configId") String configId);

    @Results(value = {
            @Result(column = "envs", property = "envs", typeHandler = SkillEnvVarsTypeHandler.class)
    })
    @Select("SELECT " + COLS + " FROM tenant_skills "
            + "WHERE tenant_id = #{tenantId} AND deleted_at IS NULL ORDER BY created_at ASC")
    List<TenantSkillEntity> listSkillsByTenant(@Param("tenantId") long tenantId);

    /** 对照 ListSkillsByCatalog（pinInstallsToReplacedBundle 的读取面）。 */
    @Results(value = {
            @Result(column = "envs", property = "envs", typeHandler = SkillEnvVarsTypeHandler.class)
    })
    @Select("SELECT " + COLS + " FROM tenant_skills "
            + "WHERE tenant_id = #{tenantId} AND catalog_id = #{catalogId} "
            + "AND deleted_at IS NULL ORDER BY created_at ASC")
    List<TenantSkillEntity> listSkillsByCatalog(@Param("tenantId") long tenantId,
            @Param("catalogId") String catalogId);

    @Insert("INSERT INTO tenant_skills (id, tenant_id, sandbox_config_id, catalog_id, name, "
            + "version, description, instructions, bundle_ref, bundle_sha256, enabled, "
            + "installed_snapshot_id, install_session_id, install_message_id, envs, status, "
            + "error, installing_since, created_at, updated_at, deleted_at) "
            + "VALUES (#{e.id}, #{e.tenantId}, #{e.sandboxConfigId}, #{e.catalogId}, #{e.name}, "
            + "#{e.version}, #{e.description}, #{e.instructions}, #{e.bundleRef}, "
            + "#{e.bundleSha256}, #{e.enabled}, #{e.installedSnapshotId}, #{e.installSessionId}, "
            + "#{e.installMessageId}, #{e.envs,typeHandler=" + ENV_TH + "}, #{e.status}, "
            + "#{e.error}, #{e.installingSince}, #{now}, #{now}, NULL)")
    int createSkill(@Param("e") TenantSkillEntity e, @Param("now") OffsetDateTime now);

    /**
     * 对照 UpdateSkill：可变列全集 + updated_at=now（GORM 的 map Updates 语义）。
     * envs 刻意缺席（类注释）。
     */
    @Update("UPDATE tenant_skills SET name = #{e.name}, version = #{e.version}, "
            + "description = #{e.description}, instructions = #{e.instructions}, "
            + "bundle_ref = #{e.bundleRef}, bundle_sha256 = #{e.bundleSha256}, "
            + "enabled = #{e.enabled}, installed_snapshot_id = #{e.installedSnapshotId}, "
            + "install_session_id = #{e.installSessionId}, "
            + "install_message_id = #{e.installMessageId}, catalog_id = #{e.catalogId}, "
            + "status = #{e.status}, error = #{e.error}, "
            + "installing_since = #{e.installingSince}, updated_at = #{now} "
            + "WHERE tenant_id = #{e.tenantId} AND sandbox_config_id = #{e.sandboxConfigId} "
            + "AND id = #{e.id} AND deleted_at IS NULL")
    int updateSkill(@Param("e") TenantSkillEntity e, @Param("now") OffsetDateTime now);

    /** 对照 UpdateSkillAdminState：管理请求拥有的两列一起写（一条语句不可半途而废）。 */
    @Update("UPDATE tenant_skills SET enabled = #{enabled}, "
            + "envs = #{envs,typeHandler=" + ENV_TH + "}, updated_at = #{now} "
            + "WHERE tenant_id = #{tenantId} AND sandbox_config_id = #{configId} "
            + "AND id = #{skillId} AND deleted_at IS NULL")
    int updateSkillAdminState(@Param("tenantId") long tenantId,
            @Param("configId") String configId, @Param("skillId") String skillId,
            @Param("enabled") boolean enabled, @Param("envs") com.ragagent.sandbox.domain.SkillEnvVars envs,
            @Param("now") OffsetDateTime now);

    /**
     * 对照 UpdateSkillEnvs（repository tenant_skill.go）：install 管线的
     * recordEnvDeclaration 专用写（envs 是唯一 UpdateSkill 刻意缺席的列——类注释）。
     */
    @Update("UPDATE tenant_skills SET envs = #{envs,typeHandler=" + ENV_TH + "}, "
            + "updated_at = #{now} "
            + "WHERE tenant_id = #{tenantId} AND sandbox_config_id = #{configId} "
            + "AND id = #{skillId} AND deleted_at IS NULL")
    int updateSkillEnvs(@Param("tenantId") long tenantId,
            @Param("configId") String configId, @Param("skillId") String skillId,
            @Param("envs") com.ragagent.sandbox.domain.SkillEnvVars envs,
            @Param("now") OffsetDateTime now);

    /**
     * 对照 DeleteSkill 的第一段：软删 skill 行（第二段的用户值硬删见
     * {@link #deleteUserEnvVarsOfSkill}，两段由 service 层同一事务包裹——
     * Go 的注释原文见 service 侧）。skill 行不存在时返回 0、什么都不删。
     */
    @Update("UPDATE tenant_skills SET deleted_at = #{now} "
            + "WHERE tenant_id = #{tenantId} AND sandbox_config_id = #{configId} "
            + "AND id = #{skillId} AND deleted_at IS NULL")
    int softDeleteSkill(@Param("tenantId") long tenantId, @Param("configId") String configId,
            @Param("skillId") String skillId, @Param("now") OffsetDateTime now);

    // ── catalog（迁移 000090；同名唯一索引对活行生效） ─────────────────────

    String CATALOG_COLS = "id, tenant_id, name, version, description, instructions, "
            + "bundle_ref, bundle_sha256, created_at, updated_at";

    @Select("SELECT " + CATALOG_COLS + " FROM tenant_skill_catalog "
            + "WHERE tenant_id = #{tenantId} AND name = #{name} AND deleted_at IS NULL")
    TenantSkillCatalogEntity getCatalogByName(@Param("tenantId") long tenantId,
            @Param("name") String name);

    @Select("SELECT " + CATALOG_COLS + " FROM tenant_skill_catalog "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND deleted_at IS NULL")
    TenantSkillCatalogEntity getCatalogByID(@Param("tenantId") long tenantId,
            @Param("id") String id);

    /** 对照 ListCatalogsByTenant（releaseInstallBundle 的持有者核查面）。 */
    @Select("SELECT " + CATALOG_COLS + " FROM tenant_skill_catalog "
            + "WHERE tenant_id = #{tenantId} AND deleted_at IS NULL ORDER BY created_at ASC")
    List<TenantSkillCatalogEntity> listCatalogsByTenant(@Param("tenantId") long tenantId);

    @Insert("INSERT INTO tenant_skill_catalog (id, tenant_id, name, version, description, "
            + "instructions, bundle_ref, bundle_sha256, created_at, updated_at, deleted_at) "
            + "VALUES (#{e.id}, #{e.tenantId}, #{e.name}, #{e.version}, #{e.description}, "
            + "#{e.instructions}, #{e.bundleRef}, #{e.bundleSha256}, #{now}, #{now}, NULL)")
    int createCatalog(@Param("e") TenantSkillCatalogEntity e, @Param("now") OffsetDateTime now);

    @Update("UPDATE tenant_skill_catalog SET name = #{e.name}, version = #{e.version}, "
            + "description = #{e.description}, instructions = #{e.instructions}, "
            + "bundle_ref = #{e.bundleRef}, bundle_sha256 = #{e.bundleSha256}, "
            + "updated_at = #{now} "
            + "WHERE tenant_id = #{e.tenantId} AND id = #{e.id} AND deleted_at IS NULL")
    int updateCatalog(@Param("e") TenantSkillCatalogEntity e, @Param("now") OffsetDateTime now);

    /** 对照 DeleteCatalog：软删定义（deleted_at）；安装行不受影响。 */
    @Update("UPDATE tenant_skill_catalog SET deleted_at = #{now} "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND deleted_at IS NULL")
    int deleteCatalog(@Param("tenantId") long tenantId, @Param("id") String id,
            @Param("now") OffsetDateTime now);

    /** DeleteSkill 的第二段：硬删该 skill 名下的用户值（与软删同事务）。 */
    @Delete("DELETE FROM tenant_user_env_vars WHERE tenant_id = #{tenantId} AND skill_id = #{skillId}")
    int deleteUserEnvVarsOfSkill(@Param("tenantId") long tenantId, @Param("skillId") String skillId);

    // ── tenant_user_env_vars（迁移 000089；波 3 子批 4 的 /me/env-vars 面） ──
    //
    // H2 的 VALUE 是保留字，列名全程双引号（TestSchema 同款）。value 的加解密在
    // service 层显式做（对照 Go 的 BeforeSave/AfterFind 钩子），mapper 只管字节。

    String UENV_COLS = "id, tenant_id, principal_type, principal_id, sandbox_config_id, "
            + "skill_id, name, \"value\", created_at, updated_at";

    @Select("SELECT " + UENV_COLS + " FROM tenant_user_env_vars "
            + "WHERE tenant_id = #{tenantId} AND principal_type = #{principalType} "
            + "AND principal_id = #{principalId} AND sandbox_config_id = #{configId} "
            + "AND skill_id = #{skillId} ORDER BY name ASC")
    java.util.List<com.ragagent.sandbox.domain.TenantUserEnvVar> listUserEnvVars(
            @Param("tenantId") long tenantId, @Param("principalType") String principalType,
            @Param("principalId") String principalId, @Param("configId") String configId,
            @Param("skillId") String skillId);

    /** 对照 ListUserEnvVarsByConfig（ListMine 的一次性读面）：ORDER BY skill_id ASC, name ASC。 */
    @Select("SELECT " + UENV_COLS + " FROM tenant_user_env_vars "
            + "WHERE tenant_id = #{tenantId} AND principal_type = #{principalType} "
            + "AND principal_id = #{principalId} AND sandbox_config_id = #{configId} "
            + "ORDER BY skill_id ASC, name ASC")
    java.util.List<com.ragagent.sandbox.domain.TenantUserEnvVar> listUserEnvVarsByConfig(
            @Param("tenantId") long tenantId, @Param("principalType") String principalType,
            @Param("principalId") String principalId, @Param("configId") String configId);

    /** 对照 UpsertUserEnvVar 的 UPDATE 臂（唯一索引命中时只换 value/updated_at）。 */
    @Update("UPDATE tenant_user_env_vars SET \"value\" = #{value}, updated_at = #{now} "
            + "WHERE tenant_id = #{tenantId} AND principal_type = #{principalType} "
            + "AND principal_id = #{principalId} AND sandbox_config_id = #{configId} "
            + "AND skill_id = #{skillId} AND name = #{name}")
    int updateUserEnvVarValue(@Param("tenantId") long tenantId,
            @Param("principalType") String principalType, @Param("principalId") String principalId,
            @Param("configId") String configId, @Param("skillId") String skillId,
            @Param("name") String name, @Param("value") String value,
            @Param("now") OffsetDateTime now);

    /** 对照 UpsertUserEnvVar 的 INSERT 臂（UPDATE 未命中行时）。 */
    @Insert("INSERT INTO tenant_user_env_vars (id, tenant_id, principal_type, principal_id, "
            + "sandbox_config_id, skill_id, name, \"value\", created_at, updated_at) "
            + "VALUES (#{e.id}, #{e.tenantId}, #{e.principalType}, #{e.principalId}, "
            + "#{e.sandboxConfigId}, #{e.skillId}, #{e.name}, #{e.value}, #{now}, #{now})")
    int insertUserEnvVar(@Param("e") com.ragagent.sandbox.domain.TenantUserEnvVar e,
            @Param("now") OffsetDateTime now);

    // ── 快照台账 + reaper（W5 收尾批；消费点 = install/remove 管线与 reaper） ──

    String SNAPSHOT_COLS = "id, tenant_id, sandbox_config_id, skill_id, generation, "
            + "snapshot_id, state, superseded_at, created_at, updated_at";

    /** 对照 ListStaleInstalling（repository L261-273）：installing/removing 且心跳超时。 */
    @Results(value = {
            @Result(column = "envs", property = "envs", typeHandler = SkillEnvVarsTypeHandler.class)
    })
    @Select("SELECT " + COLS + " FROM tenant_skills "
            + "WHERE status IN ('installing', 'removing') "
            + "AND installing_since IS NOT NULL AND installing_since < #{cutoff} "
            + "AND deleted_at IS NULL")
    List<TenantSkillEntity> listStaleInstalling(@Param("cutoff") OffsetDateTime cutoff);

    /** 对照 updateSkillFields 的专用形态（reaper 状态机写 status/error/心跳/snapshot）。 */
    @Update("UPDATE tenant_skills SET status = #{status}, error = #{error}, "
            + "installing_since = #{installingSince}, installed_snapshot_id = #{snapshotId}, "
            + "updated_at = #{now} "
            + "WHERE tenant_id = #{tenantId} AND sandbox_config_id = #{configId} "
            + "AND id = #{skillId} AND deleted_at IS NULL")
    int updateReapState(@Param("tenantId") long tenantId, @Param("configId") String configId,
            @Param("skillId") String skillId, @Param("status") String status,
            @Param("error") String error, @Param("installingSince") OffsetDateTime installingSince,
            @Param("snapshotId") String snapshotId, @Param("now") OffsetDateTime now);

    /** 对照 CreateSnapshotRow：provider 工作前先记台账行。 */
    @Insert("INSERT INTO tenant_skill_snapshots (id, tenant_id, sandbox_config_id, skill_id, "
            + "generation, snapshot_id, parent_snapshot_id, planned_name, trigger, state, "
            + "created_at, updated_at) "
            + "VALUES (#{e.id}, #{e.tenantId}, #{e.sandboxConfigId}, #{e.skillId}, #{e.generation}, "
            + "#{e.snapshotId}, #{e.parentSnapshotId}, #{e.plannedName}, #{e.trigger}, #{e.state}, "
            + "#{now}, #{now})")
    int createSnapshotRow(@Param("e") com.ragagent.sandbox.domain.TenantSkillSnapshotEntity e,
            @Param("now") OffsetDateTime now);

    /**
     * 对照 MarkSnapshotState（repository L283-298）：台账状态迁移。snapshotId 仅在
     * 非空时覆盖；state=superseded 时落 superseded_at（Go 的 map 条件更新等价形）。
     */
    @Update("UPDATE tenant_skill_snapshots SET state = #{state}, updated_at = #{now}, "
            + "snapshot_id = CASE WHEN #{snapshotId} IS NOT NULL AND #{snapshotId} != '' "
            + "THEN #{snapshotId} ELSE snapshot_id END, "
            + "superseded_at = CASE WHEN #{state} = 'superseded' THEN #{now} ELSE superseded_at END "
            + "WHERE tenant_id = #{tenantId} AND id = #{id}")
    int markSnapshotState(@Param("tenantId") long tenantId, @Param("id") String id,
            @Param("state") String state, @Param("snapshotId") String snapshotId,
            @Param("now") OffsetDateTime now);

    /** 对照 ListSnapshotsByConfig：generation ASC 全链。 */
    @Select("SELECT " + SNAPSHOT_COLS + " FROM tenant_skill_snapshots "
            + "WHERE tenant_id = #{tenantId} AND sandbox_config_id = #{configId} "
            + "ORDER BY generation ASC")
    List<com.ragagent.sandbox.domain.TenantSkillSnapshotEntity> listSnapshotsByConfig(
            @Param("tenantId") long tenantId, @Param("configId") String configId);

    /** 对照 DeleteSnapshotRowsByConfig：整配置删除时清台账（普通切换永不调用）。 */
    @Update("DELETE FROM tenant_skill_snapshots "
            + "WHERE tenant_id = #{tenantId} AND sandbox_config_id = #{configId}")
    int deleteSnapshotRowsByConfig(@Param("tenantId") long tenantId,
            @Param("configId") String configId);

    /** 对照 DeleteUserEnvVar：返回受影响行数（0 = ErrEnvVarNotFound）。 */
    @Delete("DELETE FROM tenant_user_env_vars "
            + "WHERE tenant_id = #{tenantId} AND principal_type = #{principalType} "
            + "AND principal_id = #{principalId} AND sandbox_config_id = #{configId} "
            + "AND skill_id = #{skillId} AND name = #{name}")
    int deleteUserEnvVar(@Param("tenantId") long tenantId,
            @Param("principalType") String principalType, @Param("principalId") String principalId,
            @Param("configId") String configId, @Param("skillId") String skillId,
            @Param("name") String name);
}
