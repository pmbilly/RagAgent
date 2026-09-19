package com.ragagent.sandbox.mapper;

import java.time.OffsetDateTime;
import java.util.List;

import com.ragagent.sandbox.domain.TenantSandboxConfigEntity;
import com.ragagent.sandbox.domain.TenantSandboxConfigTypeHandler;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 对照 Go {@code repository.TenantSandboxConfigRepository}
 * （internal/application/repository/tenant_sandbox_config.go，152 行全文；ListAll 是
 * 房管扫描、不在请求路径上，本批无调用方 → 未翻译，接口面随 reaper 收口补）。
 *
 * <p>每次读写都按 tenant_id 划界：沙箱配置携带 provider 凭据，忘了 scope 的查询是
 * 跨工作区凭据泄漏而不只是 bug（Go 注释原文）。软删走显式 {@code deleted_at IS NULL}
 * （§9 约定，不用 @TableLogic）。自定义 SQL 的 config 列必须显式声明 typeHandler
 * （§9：注解 SQL 不套实体注解）。</p>
 */
@Mapper
public interface TenantSandboxConfigMapper {

    String COLS = "id, tenant_id, name, description, sandbox_type, config, "
            + "cordoned_at, created_at, updated_at, deleted_at";

    String CONFIG_TH = "com.ragagent.sandbox.domain.TenantSandboxConfigTypeHandler";

    @Insert("INSERT INTO tenant_sandbox_configs (id, tenant_id, name, description, "
            + "sandbox_type, config, cordoned_at, created_at, updated_at, deleted_at) "
            + "VALUES (#{e.id}, #{e.tenantId}, #{e.name}, #{e.description}, "
            + "#{e.sandboxType}, #{e.config,typeHandler=" + CONFIG_TH + "}, "
            + "#{e.cordonedAt}, #{now}, #{now}, NULL)")
    int create(@Param("e") TenantSandboxConfigEntity entity, @Param("now") OffsetDateTime now);

    /**
     * 对照 GetByID：配置不存在或属于别的工作区时返回 null（无错误），
     * 调用方可以直接渲染 404 而不必检查错误类型。
     */
    @Select("SELECT " + COLS + " FROM tenant_sandbox_configs "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND deleted_at IS NULL")
    @Results(id = "sandboxConfigRow", value = {
            @Result(column = "config", property = "config",
                    typeHandler = TenantSandboxConfigTypeHandler.class)
    })
    TenantSandboxConfigEntity getByID(@Param("tenantId") long tenantId, @Param("id") String id);

    /** 对照 ListByTenant：created_at ASC（GORM Find 空结果 → Java 侧空列表非 null）。 */
    @Select("SELECT " + COLS + " FROM tenant_sandbox_configs "
            + "WHERE tenant_id = #{tenantId} AND deleted_at IS NULL ORDER BY created_at ASC")
    @Results(value = {
            @Result(column = "config", property = "config",
                    typeHandler = TenantSandboxConfigTypeHandler.class)
    })
    List<TenantSandboxConfigEntity> listByTenant(@Param("tenantId") long tenantId);

    /**
     * 对照 Update：只写可变列（Go 的 Select 明确列出——实体上零值的 CordonedAt
     * 不能悄悄释放别人的租约）。updated_at 由 GORM 写 now 但**不回写内存对象**——
     * Java 同样只 SET、不动实体（PUT 响应里的 updated_at 保持读时值，Go 一致）。
     */
    @Update("UPDATE tenant_sandbox_configs SET name = #{e.name}, description = #{e.description}, "
            + "sandbox_type = #{e.sandboxType}, "
            + "config = #{e.config,typeHandler=" + CONFIG_TH + "}, updated_at = #{now} "
            + "WHERE tenant_id = #{e.tenantId} AND id = #{e.id}")
    int update(@Param("e") TenantSandboxConfigEntity entity, @Param("now") OffsetDateTime now);

    /** 对照 SoftDelete：GORM Delete 附带 deleted_at IS NULL 条件。 */
    @Update("UPDATE tenant_sandbox_configs SET deleted_at = #{now} "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND deleted_at IS NULL")
    int softDelete(@Param("tenantId") long tenantId, @Param("id") String id,
            @Param("now") OffsetDateTime now);

    /**
     * 对照 SetCordon：条件 CAS——拒覆盖仍在租约窗口内的 cordon，
     * 两个并发的身份变更请求不能互相擦肩。返回受影响行数；0 行 = 已有新鲜租约，
     * service 层据此抛 ErrSandboxConfigCordoned（Go 在仓储层抛，Java 的注解 mapper
     * 无异常钩子 → 判定收口在 service）。GORM 的 Model().Update 单列写也会自动刷
     * updated_at，故这里一并 SET。
     */
    @Update("UPDATE tenant_sandbox_configs SET cordoned_at = #{at}, updated_at = #{at} "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} "
            + "AND (cordoned_at IS NULL OR cordoned_at < #{leaseBoundary})")
    int setCordon(@Param("tenantId") long tenantId, @Param("id") String id,
            @Param("at") OffsetDateTime at, @Param("leaseBoundary") OffsetDateTime leaseBoundary);

    /** 对照 ClearCordon：GORM Update 自动刷 updated_at。 */
    @Update("UPDATE tenant_sandbox_configs SET cordoned_at = NULL, updated_at = #{now} "
            + "WHERE tenant_id = #{tenantId} AND id = #{id}")
    int clearCordon(@Param("tenantId") long tenantId, @Param("id") String id,
            @Param("now") OffsetDateTime now);
}
