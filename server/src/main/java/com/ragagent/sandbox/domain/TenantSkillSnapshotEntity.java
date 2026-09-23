package com.ragagent.sandbox.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * skill 快照台账行（对照 Go {@code types.TenantSkillSnapshotEntity} 与
 * repository/tenant_skill.go 的 embeddings…——同文件 snapshots 投影）。每次
 * provider 快照操作先落台账（state=creating…ready/superseded/failed）。
 */
@TableName("tenant_skill_snapshots")
public class TenantSkillSnapshotEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    private Long tenantId;
    private String sandboxConfigId;
    private String skillId;
    private int generation;
    private String snapshotId;
    /** 对照 Go ParentSnapshotID：本行 build 前的活指针（迁移 000086）。 */
    private String parentSnapshotId;
    /** 对照 Go PlannedName：provider 调用前先记的快照名（迁移 000088）——
     *  进程死在 commit 与回写之间时，遗弃的 provider 快照仍可按它对账回收。 */
    private String plannedName;
    private String state;
    private String trigger;
    private OffsetDateTime supersededAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getSandboxConfigId() { return sandboxConfigId; }
    public void setSandboxConfigId(String sandboxConfigId) { this.sandboxConfigId = sandboxConfigId; }
    public String getSkillId() { return skillId; }
    public void setSkillId(String skillId) { this.skillId = skillId; }
    public int getGeneration() { return generation; }
    public void setGeneration(int generation) { this.generation = generation; }
    public String getSnapshotId() { return snapshotId; }
    public void setSnapshotId(String snapshotId) { this.snapshotId = snapshotId; }
    public String getParentSnapshotId() { return parentSnapshotId; }
    public void setParentSnapshotId(String parentSnapshotId) { this.parentSnapshotId = parentSnapshotId; }
    public String getPlannedName() { return plannedName; }
    public void setPlannedName(String plannedName) { this.plannedName = plannedName; }
    public String getTrigger() { return trigger; }
    public void setTrigger(String trigger) { this.trigger = trigger; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public OffsetDateTime getSupersededAt() { return supersededAt; }
    public void setSupersededAt(OffsetDateTime supersededAt) { this.supersededAt = supersededAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
