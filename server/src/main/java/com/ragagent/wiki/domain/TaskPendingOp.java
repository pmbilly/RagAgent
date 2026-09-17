package com.ragagent.wiki.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.web.PgJsonTypeHandler;

/**
 * {@code task_pending_ops} 表实体（对照 Go {@code types.TaskPendingOp}，
 * internal/types/task_pending_op.go；表结构以
 * migrations/versioned/000041_task_queue_and_wiki_indexes.up.sql 第 1 段为准）。
 *
 * <p>通用持久化待办队列：把 ad-hoc 的 Redis 列表队列（{@code wiki:pending:<kbID>}）
 * 换成能抗重启、不会被 TTL 驱逐的行。<b>(TaskType, Scope, ScopeID)</b> 三元组是队列身份；
 * 消费方 {@code PeekBatch} 拉一批、按 DedupKey 去重、处理完 {@code DeleteByIDs}。</p>
 *
 * <p><b>GORM 隐式行为清单（约定 §3）</b>：</p>
 * <ol>
 *   <li><b>无软删除</b>：表里没有 {@code deleted_at}，行被消费即物理删除。</li>
 *   <li><b>自增主键</b>：{@code id BIGSERIAL PRIMARY KEY} → {@link IdType#AUTO}，
 *       插入后由数据库回填（Go 的 {@code autoIncrement} 同义）。</li>
 *   <li><b>服务端默认值</b>：{@code enqueued_at DEFAULT NOW()}、
 *       {@code fail_count DEFAULT 0}、{@code payload DEFAULT '{}'}；
 *       Java 侧插入时把 {@code enqueuedAt} 留 null 也可（DB 填），
 *       但为了让读回的对象时间准确，仓储在 insert 后重新 select 一次。</li>
 *   <li><b>排序</b>：{@code PeekBatch} 显式 {@code ORDER BY id ASC}（FIFO），
 *       由 {@code idx_task_pending_ops_scope (task_type, scope, scope_id, id)} 覆盖。</li>
 *   <li><b>jsonb 列</b>：{@code payload} 用 {@link PgJsonTypeHandler} 直接映射成
 *       {@link JsonNode}——Go 是 {@code json.RawMessage}，两者都是"原样存取的 JSON 文本"，
 *       差别只在 Java 侧解析度更深一层。写库走 {@code setObject(OTHER)} 让 PG 按列类型
 *       强转（见约定 §9）；H2 测试库把它当 VARCHAR 承载。</li>
 * </ol>
 */
@TableName(value = "task_pending_ops", autoResultMap = true)
public class TaskPendingOp {

    /** 对照 Go {@code ID}：PeekBatch 排序与 DeleteByIDs / IncrFailCount 的行键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对照 Go {@code TenantID}：从宿主对象镜像下来的租户作用域。 */
    @TableField("tenant_id")
    private Long tenantId;

    /** 对照 Go {@code TaskType}：{@code "wiki:ingest"} / {@code "wiki:finalize"}。 */
    @TableField("task_type")
    private String taskType = "";

    /** 对照 Go {@code Scope}：{@code "knowledge_base"} / {@code "knowledge"} / {@code "tenant"}。 */
    @TableField("scope")
    private String scope = "";

    /** 对照 Go {@code ScopeID}：scope 内的标识（scope="knowledge_base" 时是 kbID）。 */
    @TableField("scope_id")
    private String scopeId = "";

    /** 对照 Go {@code Op}：服务自定义的操作种类，如 wiki 的 {@code "ingest"} / {@code "retract"}。 */
    @TableField("op")
    private String op = "";

    /**
     * 对照 Go {@code DedupKey}：可选的服务自定义去重键。去重由<b>消费方</b>负责
     * （队列本身不强制唯一——同 DedupKey 的多行可以共存，由消费方决定谁赢）。
     */
    @TableField("dedup_key")
    private String dedupKey = "";

    /** 对照 Go {@code Payload}：JSON 序列化的 op 载荷，schema 由消费方定义，队列原样存。 */
    @TableField(value = "payload", typeHandler = PgJsonTypeHandler.class)
    private JsonNode payload;

    /** 对照 Go {@code FailCount}：批内重试计数，成功消费时不再需要（行被删除）。 */
    @TableField("fail_count")
    private Integer failCount = 0;

    /** 对照 Go {@code EnqueuedAt}：服务端入队时间。**不用于排序**（游标是 id）。 */
    @TableField("enqueued_at")
    private OffsetDateTime enqueuedAt;

    /**
     * 对照 Go {@code ClaimedAt}：并发认领标记。
     *
     * <p>NULL = 未认领；早于消费方的 stale 阈值 = 崩溃/被遗弃的认领，可被回收；
     * <b>新鲜的认领会阻塞它整个 dedup_key</b>，因此同一文档的多个 op 绝不会被拆到
     * 两个并发批次里。</p>
     */
    @TableField("claimed_at")
    private OffsetDateTime claimedAt;

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

    public String getOp() { return op; }
    public void setOp(String v) { op = v == null ? "" : v; }

    public String getDedupKey() { return dedupKey; }
    public void setDedupKey(String v) { dedupKey = v == null ? "" : v; }

    public JsonNode getPayload() { return payload; }
    public void setPayload(JsonNode v) { payload = v; }

    public Integer getFailCount() { return failCount; }
    public void setFailCount(Integer v) { failCount = v; }

    public OffsetDateTime getEnqueuedAt() { return enqueuedAt; }
    public void setEnqueuedAt(OffsetDateTime v) { enqueuedAt = v; }

    public OffsetDateTime getClaimedAt() { return claimedAt; }
    public void setClaimedAt(OffsetDateTime v) { claimedAt = v; }
}
