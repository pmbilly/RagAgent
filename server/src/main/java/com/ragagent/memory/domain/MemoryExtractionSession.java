package com.ragagent.memory.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ragagent.common.web.GoTimeSerializer;

/**
 * 每个会话的蒸馏进度——一行**有索引的小记录**，而不是塞进主体上那个不断长大的 JSON 文档。
 * 已完成的游标能防止历史被反复抽取。
 *
 * <h2>JSON 形态</h2>
 * <pre>
 *   MemoryExtractionSession{}        → {"revision":0,"cursor":{"at":"0001-01-01T00:00:00Z","id":""}}
 *   MemoryExtractionSession{Rev:3, Cursor:{...}} → {"revision":3,"cursor":{"at":"…","id":"m1"}}
 * </pre>
 * <p>除 {@code revision} 与 {@code cursor} 之外**每一个字段**都是 {@code json:"-"}。
 * 注意 {@code cursor} 在 DB 里展开成两列 {@code cursor_at}/{@code cursor_id}，
 * **JSON 里是一个嵌套对象**。
 * Java 侧因此用两个平列字段承载列，再由 {@link #getCursor()} / {@link #setCursor}
 * 拼出/拆开那个嵌套对象。</p>
 *
 * <h2>⚠️ 复合主键</h2>
 * <p>表的主键是 {@code (tenant_id, subject_id, session_id)}，<b>没有 id 列</b>。
 * MyBatis-Plus 只能有一个 {@code @TableId}，这里把 {@code sessionId} 标上
 * （语义上最接近"业务键"），但**仓储层从不使用 {@code selectById}/{@code updateById}**
 * ——所有读写都写在显式 SQL 里（{@code forUpdateClause} 那套事务语义也要求如此）。</p>
 *
 * <h2>落库隐式行为清单（约定 §3）</h2>
 * <ol>
 *   <li><b>自动时间戳</b>：只有 {@code updated_at}（不出 JSON）。
 *       INSERT 与每次 UPDATE 都显式写 {@code now}，Java 侧一律显式赋值。</li>
 *   <li><b>钩子</b>：无。</li>
 *   <li><b>关联预加载</b>：无。</li>
 *   <li><b>软删除</b>：无。</li>
 *   <li><b>默认排序</b>：{@code updated_at ASC, session_id ASC}（{@code ClaimPendingSessions}
 *       取批），仓库层显式写。</li>
 *   <li><b>唯一/主键</b>：复合主键如上；索引
 *       {@code idx_memory_extraction_pending (tenant_id, subject_id, pending, updated_at, session_id)}
 *       与 000094 的迁移逐字一致。</li>
 *   <li><b>DEFAULT 列</b>：{@code revision}/{@code pending}/{@code failure_count}/
 *       {@code failure_code}/{@code cursor_id}/{@code failed_from_id}/{@code failed_to_id}
 *       带字面量 default tag → 落库时仍显式写入。<b>这意味着 Java 实体里这七个字段
 *       不能是 null</b>（{@code failure_code} 等字符串默认 {@code ""}，
 *       {@code cursor_id} 等默认 {@code ""}），否则 MyBatis-Plus 会省略该列、
 *       在 NOT NULL 列上落到 DB 默认值——本次恰好一致，但读回的值形态会不同。</li>
 * </ol>
 */
@TableName("memory_extraction_sessions")
public class MemoryExtractionSession {

    /* ── 复合主键的三列：两个是 json:"-"，session_id 也是 json:"-" ─────────── */

    /** 复合主键之一，{@code json:"-"}。 */
    @JsonIgnore
    private Long tenantId = 0L;

    /** 复合主键之一，{@code json:"-"}。 */
    @JsonIgnore
    private String subjectId = "";

    /** 复合主键之一，{@code json:"-"}。语义上最接近业务键，故由它承载 {@code @TableId}。 */
    @com.baomidou.mybatisplus.annotation.TableId(value = "session_id",
            type = com.baomidou.mybatisplus.annotation.IdType.INPUT)
    @JsonIgnore
    private String sessionId = "";

    private long revision;

    /** {@code cursor_at} + {@code cursor_id} 两列，JSON 里是嵌套对象（见类注释）。 */
    @JsonIgnore
    private OffsetDateTime cursorAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

    @JsonIgnore
    private String cursorId = "";

    /** {@code json:"-"}：暴露给 Java 抽取 worker 的便利视图，**没有**对应响应键。 */
    @JsonIgnore
    private boolean pending;

    @JsonIgnore
    private int failureCount;

    @JsonIgnore
    private String failureCode = "";

    @JsonIgnore
    private OffsetDateTime failedFromAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

    @JsonIgnore
    private String failedFromId = "";

    @JsonIgnore
    private OffsetDateTime failedToAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

    @JsonIgnore
    private String failedToId = "";

    @JsonIgnore
    private OffsetDateTime failedAt;

    @JsonIgnore
    private OffsetDateTime updatedAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

    // ── 嵌套游标（JSON 键 cursor / failed_from / failed_to） ────────────────

    /**
     * JSON 里的 {@code cursor} 键（嵌套对象）。
     *
     * <p>它以 {@code getCursor()} 的形式序列化——这是**必要的**派生访问器，
     * JSON 契约要求这个键存在。
     * 反方向（{@code setCursor}）供 Jackson 与测试使用，落库仍走两个平列字段。</p>
     */
    public MemoryMessageCursor getCursor() {
        return new MemoryMessageCursor(cursorAt, cursorId);
    }

    public void setCursor(MemoryMessageCursor cursor) {
        this.cursorAt = cursor == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : cursor.getAt();
        this.cursorId = cursor == null ? "" : cursor.getId();
    }

    // ── 访问器 ─────────────────────────────────────────────────────────────

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { tenantId = v == null ? 0L : v; }

    public String getSubjectId() { return subjectId; }
    public void setSubjectId(String v) { subjectId = v == null ? "" : v; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String v) { sessionId = v == null ? "" : v; }

    public long getRevision() { return revision; }
    public void setRevision(long v) { revision = v; }

    public OffsetDateTime getCursorAt() { return cursorAt; }
    public void setCursorAt(OffsetDateTime v) {
        cursorAt = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }

    public String getCursorId() { return cursorId; }
    public void setCursorId(String v) { cursorId = v == null ? "" : v; }

    public boolean isPending() { return pending; }
    public void setPending(boolean v) { pending = v; }

    public int getFailureCount() { return failureCount; }
    public void setFailureCount(int v) { failureCount = v; }

    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String v) { failureCode = v == null ? "" : v; }

    public OffsetDateTime getFailedFromAt() { return failedFromAt; }
    public void setFailedFromAt(OffsetDateTime v) {
        failedFromAt = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }

    public String getFailedFromId() { return failedFromId; }
    public void setFailedFromId(String v) { failedFromId = v == null ? "" : v; }

    public OffsetDateTime getFailedToAt() { return failedToAt; }
    public void setFailedToAt(OffsetDateTime v) {
        failedToAt = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }

    public String getFailedToId() { return failedToId; }
    public void setFailedToId(String v) { failedToId = v == null ? "" : v; }

    public OffsetDateTime getFailedAt() { return failedAt; }
    public void setFailedAt(OffsetDateTime v) { failedAt = v; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) {
        updatedAt = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }

    /**
     * {@code failedFrom} 与 {@code cursor} 是否指向同一条消息（at 与 id 都相等）。
     *
     * <p>名字不带 {@code get}/{@code is} 前缀，所以 Jackson 不会把它当属性（§7.5 第 2 条）。</p>
     */
    @JsonIgnore
    public boolean failedFromEqualsCursor() {
        return failedFromAt.toInstant().equals(cursorAt.toInstant()) && failedFromId.equals(cursorId);
    }
}
