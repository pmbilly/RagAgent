package com.ragagent.memory.domain;

import com.ragagent.common.settings.MemoryKinds;
import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ragagent.common.web.GoTimeSerializer;

/**
 * 一条被记住的陈述（对照 Go {@code types.MemoryItem}，internal/types/memory.go L288-326）。
 *
 * <p><b>这是真正的响应体</b>：handler 直接把它作为响应（列表在
 * {@code {items, page, pageSize, total}} 的 {@code items} 里）。契约换锚
 * （§14.9k M1，2026-10-01）后 JSON 字段名＝Java 字段名（camelCase）、键序＝字段声明序——
 * 逐字节期望见 {@code MemoryEntityJsonTest}。</p>
 *
 * <pre>
 *   MemoryItem{} →
 *   {"id":"","tenantId":0,"subjectId":"","kind":"","content":"","topic":"",
 *    "normalizedKey":"","importance":0,"origin":"","status":"","sourceSessionId":"",
 *    "sourceMessageId":"","validFrom":"0001-01-01T00:00:00Z","invalidAt":null,
 *    "expiresAt":null,"replacesId":"","supersededBy":"","lastUsedAt":null,"useCount":0,
 *    "createdAt":"0001-01-01T00:00:00Z","updatedAt":"0001-01-01T00:00:00Z"}
 * </pre>
 * <p>两个要点：{@code replacesId} 与 {@code supersededBy} **都恒输出**（未取代时是空串）——
 * 旧 Go 的 {@code omitempty} 随契约 §1.6「禁止条件键」退役；{@code inferred} 一个键都不出。</p>
 *
 * <h2>GORM 隐式行为清单（约定 §3）</h2>
 * <ol>
 *   <li><b>自动时间戳</b>：{@code created_at}/{@code updated_at} 走 GORM 的字段名约定，
 *       INSERT 时显式写入。{@code valid_from} **不是**自动时间戳（名字不匹配约定），
 *       它的 {@code DEFAULT CURRENT_TIMESTAMP} 只是 DDL 兜底——Go 的 struct tag 里
 *       **没有** {@code default:}，所以 GORM 每次都显式写它；{@code CreateItem} 还会在
 *       它为零值时补 {@code time.Now()}。Java 侧照抄这两条。</li>
 *   <li><b>钩子</b>：无。id 由 {@code CreateItem} 在为空的生成。</li>
 *   <li><b>关联预加载</b>：无。</li>
 *   <li><b>软删除</b>：无——"忘记"就是**物理删**（{@code DeleteItem}）。</li>
 *   <li><b>默认排序</b>：仓库层显式写，共四种：
 *       {@code importance DESC, valid_from DESC}（活跃/常驻/存活列表）、
 *       {@code valid_from DESC, id DESC}（管理器分页，id 破平局是为了翻页确定）、
 *       {@code valid_from DESC}（按 key 查找、缺向量扫描）、
 *       {@code importance DESC, COALESCE(last_used_at, valid_from) DESC, valid_from DESC}
 *       （容量归档）。**没有**任何隐式排序。</li>
 *   <li><b>唯一索引/外键</b>：无唯一索引；{@code idx_memory_items_scope} /
 *       {@code idx_memory_items_key} / {@code idx_memory_replaces} 是普通索引。
 *       与 {@code memory_item_embeddings} 之间**没有**外键（删条目要手动删向量）。</li>
 *   <li><b>DEFAULT 列</b>：{@code topic} / {@code normalized_key} / {@code importance} /
 *       {@code origin} / {@code status} / {@code replaces_id} / {@code use_count}
 *       都带**字面量** {@code default:} tag → GORM 实测仍显式写入（{@code DefaultValueInterface}
 *       非 nil → 列进 INSERT 列表）。Java 侧因此一律显式赋值，字段默认值对齐 Go 零值。</li>
 * </ol>
 *
 * <h2>⚠️ {@code replacesId} 的"响应"与"落库"是两件事</h2>
 * <p>响应里恒输出（空串就是空串）；落库列 {@code replaces_id} 是
 * {@code not null;default:''}——**落库必须写 {@code ''}**。
 * 两者不冲突：Jackson 只管响应，落库走实体字段值。Java 字段默认 {@code ""}，两处都对。</p>
 */
@TableName("memory_items")
public class MemoryItem {

    @TableId(value = "id", type = IdType.INPUT)
    private String id = "";

    private Long tenantId = 0L;

    private String subjectId = "";

    private String kind = "";

    private String content = "";

    /**
     * 陈述所"关于"的那个可读主题，按抽取模型给出的原文保存（"在用的数据库"）。
     *
     * <p>它与归一化 key 并存而不是被取代：它是最好的检索抓手——提问常常点出主题，
     * 而陈述本身只带值（"已经迁到 PostgreSQL"）。</p>
     */
    private String topic = "";

    /**
     * 这条陈述所关于主题的归一化 key。与某个活跃条目同 key 的新条目会**取代**它——
     * 这就是"我用 MySQL"→"我迁到 Postgres"这类矛盾在**不经 LLM** 的读路径上被解决的方式。
     */
    private String normalizedKey = "";

    private int importance;

    private String origin = "";

    private String status = "";

    private String sourceSessionId = "";

    private String sourceMessageId = "";

    private OffsetDateTime validFrom = GoTimeSerializer.GO_ZERO_DATE_TIME;

    private OffsetDateTime invalidAt;

    /**
     * 这条陈述什么时候开始不值得再被想起，用于"只在一段时间内成立"的事
     * （"这周把迁移做完"）。没有它，一个进行中的任务会永远留在上下文里。
     */
    private OffsetDateTime expiresAt;

    /**
     * ⚠️ {@code omitempty}：空串时**整个键消失**。落库仍写 {@code ''}（见类注释）。
     */
    private String replacesId = "";

    /** 无 omitempty：未取代时输出 {@code ""}（不是 {@code null}）。 */
    private String supersededBy = "";

    private OffsetDateTime lastUsedAt;

    private int useCount;

    /**
     * 标记"这是系统推出来的、不是被告知的"。**仅运行期**：这件事的持久记录是
     * {@link MemoryKinds#STATUS_PENDING}，所以 Go 的 tag 是 {@code json:"-" gorm:"-"}——
     * 既不出响应，**也不落库**（{@code gorm:"-"} 才是关键，别只看到 {@code json:"-"}）。
     */
    @JsonIgnore
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private boolean inferred;

    private OffsetDateTime createdAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

    private OffsetDateTime updatedAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

    public String getId() { return id; }
    public void setId(String v) { id = v == null ? "" : v; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { tenantId = v == null ? 0L : v; }

    public String getSubjectId() { return subjectId; }
    public void setSubjectId(String v) { subjectId = v == null ? "" : v; }

    public String getKind() { return kind; }
    public void setKind(String v) { kind = v == null ? "" : v; }

    public String getContent() { return content; }
    public void setContent(String v) { content = v == null ? "" : v; }

    public String getTopic() { return topic; }
    public void setTopic(String v) { topic = v == null ? "" : v; }

    public String getNormalizedKey() { return normalizedKey; }
    public void setNormalizedKey(String v) { normalizedKey = v == null ? "" : v; }

    public int getImportance() { return importance; }
    public void setImportance(int v) { importance = v; }

    public String getOrigin() { return origin; }
    public void setOrigin(String v) { origin = v == null ? "" : v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { status = v == null ? "" : v; }

    public String getSourceSessionId() { return sourceSessionId; }
    public void setSourceSessionId(String v) { sourceSessionId = v == null ? "" : v; }

    public String getSourceMessageId() { return sourceMessageId; }
    public void setSourceMessageId(String v) { sourceMessageId = v == null ? "" : v; }

    public OffsetDateTime getValidFrom() { return validFrom; }
    public void setValidFrom(OffsetDateTime v) {
        validFrom = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }

    public OffsetDateTime getInvalidAt() { return invalidAt; }
    public void setInvalidAt(OffsetDateTime v) { invalidAt = v; }

    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime v) { expiresAt = v; }

    public String getReplacesId() { return replacesId; }
    public void setReplacesId(String v) { replacesId = v == null ? "" : v; }

    public String getSupersededBy() { return supersededBy; }
    public void setSupersededBy(String v) { supersededBy = v == null ? "" : v; }

    public OffsetDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(OffsetDateTime v) { lastUsedAt = v; }

    public int getUseCount() { return useCount; }
    public void setUseCount(int v) { useCount = v; }

    /**
     * {@code @JsonIgnore} 与 {@code gorm:"-"} 缺一不可：前者保证不进响应 JSON，
     * 后者（{@code @TableField(exist=false)}）保证不动 DB 列——
     * {@code memory_items} 表里**根本没有叫做 inferred 的列**。
     */
    @JsonIgnore
    public boolean isInferred() { return inferred; }
    public void setInferred(boolean v) { inferred = v; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) {
        createdAt = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) {
        updatedAt = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }
}
