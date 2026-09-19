package com.ragagent.sandbox.service;

import java.util.List;

import com.ragagent.sandbox.mapper.TenantSkillReadOnlyMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 对照 Go {@code sandboxConfigSkillStore} 接口（tenant_sandbox_config.go L216-230）。
 *
 * <p><b>子批 1 只真实接线 {@link #listSkillsByConfig}</b>（Update 的 in-flight 判定需要）；
 * 快照 ledger / 清理方法由子批 2 的 TenantSkillRepository 替换。在那之前它们是
 * <b>空行为实现</b>（空列表 / WARN no-op）而非抛错——这使 Delete 路径在"ledger 尚不存在"
 * 的世界里与 Go 的空 ledger 语义一致（正常删除 200），且 releaseSkillSnapshots 的
 * 分支形状与 Go 逐行对应，子批 2 只换实现不改调用方。</p>
 */
public interface SandboxConfigSkills {

    /** 按配置列出 skill 安装行（最小投影：id + status）。 */
    List<TenantSkillReadOnlyMapper.TenantSkillStatusRow> listSkillsByConfig(long tenantId, String configId);

    /** 对照 ListSnapshotsByConfig。子批 2 接线前恒为空 ledger。 */
    default List<Object> listSnapshotsByConfig(long tenantId, String configId) {
        return List.of();
    }

    /** 对照 MarkSnapshotState。子批 2 接线前 no-op。 */
    default void markSnapshotState(long tenantId, String id, String state, String snapshotId) {
        // 子批 2 接线
    }

    /** 对照 DeleteSkill（Delete 的 cleanup 步）。子批 2 接线前 no-op（行尚不可能存在）。 */
    default void deleteSkill(long tenantId, String configId, String skillId) {
        // 子批 2 接线
    }

    /** 对照 DeleteSnapshotRowsByConfig。子批 2 接线前 no-op。 */
    default void deleteSnapshotRowsByConfig(long tenantId, String configId) {
        // 子批 2 接线
    }

    /** 对照 DeleteUserEnvVarsByConfig。子批 2 接线前 no-op。 */
    default void deleteUserEnvVarsByConfig(long tenantId, String configId) {
        // 子批 2 接线
    }

    /** 只读 mapper 支撑的最小实现（configHasInFlightSkill 的数据来源）。 */
    @Component
    class ReadOnlyTenantSkillStore implements SandboxConfigSkills {

        private static final Logger log = LoggerFactory.getLogger(ReadOnlyTenantSkillStore.class);

        private final TenantSkillReadOnlyMapper mapper;

        public ReadOnlyTenantSkillStore(TenantSkillReadOnlyMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public List<TenantSkillReadOnlyMapper.TenantSkillStatusRow> listSkillsByConfig(
                long tenantId, String configId) {
            return mapper.listSkillsByConfig(tenantId, configId);
        }

        @Override
        public List<Object> listSnapshotsByConfig(long tenantId, String configId) {
            log.debug("[sandbox] snapshot ledger not wired yet (sub-batch 2); "
                    + "treating config {} as having no snapshots", configId);
            return List.of();
        }
    }
}
