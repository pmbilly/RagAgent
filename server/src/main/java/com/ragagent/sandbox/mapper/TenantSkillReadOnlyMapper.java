package com.ragagent.sandbox.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 波 3 子批 1 的最小 skills 只读投影：只服务
 * {@code TenantSandboxConfigService.configHasInFlightSkill} 的 in-flight 判定
 * （对照 Go 走 sandboxConfigSkillStore.ListSkillsByConfig）。skills 管理面
 * （快照 ledger、catalog、上传/删除）属子批 2，届时由真正的 TenantSkillRepository 替换。
 *
 * <p>软删过滤 {@code deleted_at IS NULL}；状态常量对照
 * {@code types.SkillStatus*}（installing/ready/failed/removing）。</p>
 */
@Mapper
public interface TenantSkillReadOnlyMapper {

    /** 最小投影行：in-flight 判定只看 id + status。 */
    record TenantSkillStatusRow(String id, String status) {
    }

    @Select("SELECT id, status FROM tenant_skills "
            + "WHERE tenant_id = #{tenantId} AND sandbox_config_id = #{configId} "
            + "AND deleted_at IS NULL")
    List<TenantSkillStatusRow> listSkillsByConfig(@Param("tenantId") long tenantId,
            @Param("configId") String configId);
}
