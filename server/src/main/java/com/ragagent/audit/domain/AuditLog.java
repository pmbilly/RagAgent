package com.ragagent.audit.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.web.PgJsonTypeHandler;

/**
 * audit_logs 表实体（列定义以迁移基线为准）。
 *
 * <p>表是<b>只追加</b>的：没有 UpdatedAt、没有软删除列。单调递增的 id 既是主键
 * 也是分页游标（最新在前 = {@code WHERE id < AfterID ORDER BY id DESC}）。</p>
 *
 * <h2>落库行为清单</h2>
 * <ol>
 *   <li><b>钩子</b>：无 BeforeCreate / AfterFind / BeforeUpdate。CreatedAt 由
 *       {@code AuditLogService.Log} 显式填充；Java 侧同样在 service 层赋值，
 *       <b>不</b>用 MetaObjectHandler。</li>
 *   <li><b>软删除</b>：无 {@code DeletedAt} 列 → 无 {@code @TableLogic}、无 {@code deleted_at} 条件。</li>
 *   <li><b>默认排序</b>：查询侧显式 {@code ORDER BY id DESC}（见
 *       {@code AuditLogRepository.list}），实体本身不带顺序。</li>
 *   <li><b>自增主键</b>：BIGSERIAL → {@code @TableId(type = IdType.AUTO)}；
 *       insert 时 id 为 null 会被 MyBatis-Plus 从列清单里省略，由库生成。</li>
 *   <li><b>索引</b>（以迁移为准，应用侧不改）：{@code idx_audit_logs_tenant_id_desc(tenant_id, id DESC)}、
 *       {@code idx_audit_logs_actor(actor_user_id)}、{@code idx_audit_logs_tenant_action(tenant_id, action)}、
 *       {@code idx_audit_logs_created_at(created_at)}、
 *       {@code idx_audit_logs_tenant_scope_desc(tenant_id, scope_type, scope_id, id DESC)}。</li>
 *   <li><b>自动时间戳</b>：无框架自动写；列有 DB 默认 {@code CURRENT_TIMESTAMP}，
 *       但 service 层总会显式赋值 CreatedAt，故 INSERT 恒带值。</li>
 *   <li><b>DEFAULT 列的零值语义</b>：
 *       <ul>
 *         <li>{@code details JSONB NOT NULL DEFAULT '{}'}：Java 侧
 *             {@code details == null} 时 MyBatis-Plus 的默认 insertStrategy（NOT_NULL）
 *             省略该列 → 库默认 {@code '{}'}。
 *             注意这与 Wiki 的 {@code page_metadata} 不同：那一列可空且写入时显式给 NULL，
 *             这里 NOT NULL 且省略。</li>
 *         <li>{@code outcome VARCHAR(16) NOT NULL DEFAULT 'success'}：service 层把 "" 归一为
 *             {@link AuditOutcome#SUCCESS} 后显式写入。</li>
 *         <li>其余 varchar 列 {@code NOT NULL DEFAULT ''}：字段默认 "" + 归一化 getter，
 *             写入时也不会是 null（详见各 getter）。</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <h2>JSON 形状</h2>
 * <p>15 个键<b>恒输出</b>（无一省略），字段顺序即声明序
 * （id, tenant_id, actor_user_id, actor_role, action, scope_type, scope_id,
 * target_type, target_id, target_user_id, request_path, request_method, outcome,
 * details, created_at）。它同时是三个端点的响应体元素，键名逐字固定，
 * 且 <b>null 值也照写</b>（Jackson 默认 Include.ALWAYS 正好等价）。</p>
 *
 * <p><b>没有任何 isXxx() 派生访问器</b>——避免便捷方法被 Jackson 当属性写进
 * jsonb、回读时炸 UnrecognizedPropertyException。</p>
 */
@TableName(value = "audit_logs", autoResultMap = true)
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private String actorUserId = "";
    private String actorRole = "";
    /** 命名空间点分动作名；见 {@link AuditAction}。列 NOT NULL（无 DB 默认）。 */
    private String action = "";
    /** 资源作用域类型（如 "knowledge_base"）；空串 = 无作用域。 */
    private String scopeType = "";
    private String scopeId = "";
    private String targetType = "";
    private String targetId = "";
    private String targetUserId = "";
    /** 路由模板优先（而非原始 URL）。 */
    private String requestPath = "";
    private String requestMethod = "";
    /** 见 {@link AuditOutcome}；空串在写入前由 service 归一为 "success"。 */
    private String outcome = "";

    /** jsonb 列；读路径经 {@link PgJsonTypeHandler} 规范化键序（模拟 PG jsonb 行为）。 */
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private JsonNode details;

    private OffsetDateTime createdAt;

    // ── 访问器（数值/字符串字段把 DB 的 NULL 一律读成 "" / 0） ──

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }

    /** 无租户行读成 0（列 NOT NULL）。 */
    public long getTenantId() { return tenantId == null ? 0L : tenantId; }
    public void setTenantId(Long v) { tenantId = v; }

    public String getActorUserId() { return actorUserId == null ? "" : actorUserId; }
    public void setActorUserId(String v) { actorUserId = v; }

    public String getActorRole() { return actorRole == null ? "" : actorRole; }
    public void setActorRole(String v) { actorRole = v; }

    public String getAction() { return action == null ? "" : action; }
    public void setAction(String v) { action = v; }

    public String getScopeType() { return scopeType == null ? "" : scopeType; }
    public void setScopeType(String v) { scopeType = v; }

    public String getScopeId() { return scopeId == null ? "" : scopeId; }
    public void setScopeId(String v) { scopeId = v; }

    public String getTargetType() { return targetType == null ? "" : targetType; }
    public void setTargetType(String v) { targetType = v; }

    public String getTargetId() { return targetId == null ? "" : targetId; }
    public void setTargetId(String v) { targetId = v; }

    public String getTargetUserId() { return targetUserId == null ? "" : targetUserId; }
    public void setTargetUserId(String v) { targetUserId = v; }

    public String getRequestPath() { return requestPath == null ? "" : requestPath; }
    public void setRequestPath(String v) { requestPath = v; }

    public String getRequestMethod() { return requestMethod == null ? "" : requestMethod; }
    public void setRequestMethod(String v) { requestMethod = v; }

    /** 列默认 'success'；DB NULL（理论不可达，列 NOT NULL）归一为 ""。 */
    public String getOutcome() { return outcome == null ? "" : outcome; }
    public void setOutcome(String v) { outcome = v; }

    public JsonNode getDetails() { return details; }
    public void setDetails(JsonNode v) { details = v; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { createdAt = v; }
}
