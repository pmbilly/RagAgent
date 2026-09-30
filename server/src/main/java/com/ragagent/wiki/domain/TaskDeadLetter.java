package com.ragagent.wiki.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.web.PgJsonTypeHandler;

/**
 * {@code task_dead_letters} 表实体（表结构以迁移 000041 第 2 段为准）。
 *
 * <p>重试预算耗尽的任务的永久档案。<b>两个写入方</b>：</p>
 * <ol>
 *   <li>队列消费者的死信中间件——任务重试次数达到上限时插一行，<b>覆盖所有任务类型</b>；</li>
 *   <li>服务层重试处理器——批内重试计数超过服务定义的 cap 时直接插入，
 *       wiki ingest 是当前唯一实例。</li>
 * </ol>
 * <p>没有 TTL：行保留到人工清理。运维按 (Scope, ScopeID) 或 TaskType 查询。</p>
 *
 * <p><b>落库行为约定</b>：{@code id} 自增；{@code failed_at DEFAULT NOW()}
 * 服务端填；{@code payload} 是 jsonb（同 {@link TaskPendingOp}，用
 * {@link PgJsonTypeHandler} 承载）；{@code related_id} / {@code last_error} 默认空串；
 * 无软删除、无钩子。</p>
 */
@TableName(value = "task_dead_letters", autoResultMap = true)
public class TaskDeadLetter {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 从原始任务载荷镜像（尽力而为，解不出时落 0） */
    @TableField("tenant_id")
    private Long tenantId;

    @TableField("task_type")
    private String taskType = "";

    @TableField("scope")
    private String scope = "";

    @TableField("scope_id")
    private String scopeId = "";

    /**
     * 可选的次级标识。wiki ingest 放 knowledge_id，
     * 让逐文档的失败聚拢到源文档周围。
     */
    @TableField("related_id")
    private String relatedId = "";

    @TableField(value = "payload", typeHandler = PgJsonTypeHandler.class)
    private JsonNode payload;

    @TableField("last_error")
    private String lastError = "";

    @TableField("fail_count")
    private Integer failCount = 0;

    @TableField("failed_at")
    private OffsetDateTime failedAt;

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { tenantId = v; }

    public String getTaskType() { return taskType; }
    public void setTaskType(String v) { taskType = v == null ? "" : v; }

    public String getScope() { return scope; }
    public void setScope(String v) { scope = v == null ? "" : v; }

    public String getScopeId() { return scopeId; }
    public void setScopeId(String v) { scopeId = v == null ? "" : v; }

    public String getRelatedId() { return relatedId; }
    public void setRelatedId(String v) { relatedId = v == null ? "" : v; }

    public JsonNode getPayload() { return payload; }
    public void setPayload(JsonNode v) { payload = v; }

    public String getLastError() { return lastError; }
    public void setLastError(String v) { lastError = v == null ? "" : v; }

    public Integer getFailCount() { return failCount; }
    public void setFailCount(Integer v) { failCount = v; }

    public OffsetDateTime getFailedAt() { return failedAt; }
    public void setFailedAt(OffsetDateTime v) { failedAt = v; }
}
