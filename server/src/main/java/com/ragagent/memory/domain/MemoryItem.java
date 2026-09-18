package com.ragagent.memory.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.web.GoTimeDeserializer;
import com.ragagent.common.web.GoTimeSerializer;

/**
 * 一条被记住的陈述（对照 Go {@code types.MemoryItem}，internal/types/memory.go L288-326）。
 *
 * <p><b>这是真正的响应体</b>：handler 把本对象直接放进
 * {@code {"success":true,"data":…}}（列表是 {@code {"data":[…],"total":N}}），
 * 所以下面的键序是 **Go struct 声明序**，不是字母序（§9 的 JSON 键序规则）。</p>
 *
 * <h2>Go 实录（见 {@code MemoryEntityJsonTest}）</h2>
 * <pre>
 *   MemoryItem{} →
 *   {"id":"","tenant_id":0,"subject_id":"","kind":"","content":"","topic":"",
 *    "normalized_key":"","importance":0,"origin":"","status":"","source_session_id":"",
 *    "source_message_id":"","valid_from":"0001-01-01T00:00:00Z","invalid_at":null,
 *    "expires_at":null,"superseded_by":"","last_used_at":null,"use_count":0,
 *    "created_at":"0001-01-01T00:00:00Z","updated_at":"0001-01-01T00:00:00Z"}
 * </pre>
 * <p>三个要点：{@code replaces_id} **整个键消失**（omitempty + 空串）、
 * {@code superseded_by} **在**且为 {@code ""}（无 omitempty）、{@code inferred} 一个键都不出。</p>
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
 * <h2>⚠️ {@code replaces_id} 的"省略"与"落库"是两件事</h2>
 * <p>tag 是 {@code json:"replaces_id,omitempty"}——**响应里空串就省略键**；
 * 而 gorm tag 是 {@code not null;default:''}——**落库必须写 {@code ''}**。
 * 两者不冲突：{@code @JsonProperty} 只管 Jackson，落库走实体字段值。
 * Java 字段默认 {@code ""}，所以落库写空串、响应省略键，两处都对。</p>
 */
@TableName("memory_items")
@JsonPropertyOrder({
        "id", "tenant_id", "subject_id", "kind", "content", "topic", "normalized_key",
        "importance", "origin", "status", "source_session_id", "source_message_id",
        "valid_from", "invalid_at", "expires_at", "replaces_id", "superseded_by",
        "last_used_at", "use_count", "created_at", "updated_at"
})
public class MemoryItem {

    @TableId(value = "id", type = IdType.INPUT)
    @JsonProperty("id")
    private String id = "";

    @JsonProperty("tenant_id")
    private Long tenantId = 0L;

    @JsonProperty("subject_id")
    private String subjectId = "";

    @JsonProperty("kind")
    private String kind = "";

    @JsonProperty("content")
    private String content = "";

    /**
     * 陈述所"关于"的那个可读主题，按抽取模型给出的原文保存（"在用的数据库"）。
     *
     * <p>它与归一化 key 并存而不是被取代：它是最好的检索抓手——提问常常点出主题，
     * 而陈述本身只带值（"已经迁到 PostgreSQL"）。</p>
     */
    @JsonProperty("topic")
    private String topic = "";

    /**
     * 这条陈述所关于主题的归一化 key。与某个活跃条目同 key 的新条目会**取代**它——
     * 这就是"我用 MySQL"→"我迁到 Postgres"这类矛盾在**不经 LLM** 的读路径上被解决的方式。
     */
    @JsonProperty("normalized_key")
    private String normalizedKey = "";

    @JsonProperty("importance")
    private int importance;

    @JsonProperty("origin")
    private String origin = "";

    @JsonProperty("status")
    private String status = "";

    @JsonProperty("source_session_id")
    private String sourceSessionId = "";

    @JsonProperty("source_message_id")
    private String sourceMessageId = "";

    @JsonProperty("valid_from")
    @JsonSerialize(using = GoTimeSerializer.class)
    @JsonDeserialize(using = GoTimeDeserializer.class)
    private OffsetDateTime validFrom = GoTimeSerializer.GO_ZERO_DATE_TIME;

    @JsonProperty("invalid_at")
    @JsonSerialize(using = GoTimeSerializer.class)
    @JsonDeserialize(using = GoTimeDeserializer.class)
    private OffsetDateTime invalidAt;

    /**
     * 这条陈述什么时候开始不值得再被想起，用于"只在一段时间内成立"的事
     * （"这周把迁移做完"）。没有它，一个进行中的任务会永远留在上下文里。
     */
    @JsonProperty("expires_at")
    @JsonSerialize(using = GoTimeSerializer.class)
    @JsonDeserialize(using = GoTimeDeserializer.class)
    private OffsetDateTime expiresAt;

    /**
     * ⚠️ {@code omitempty}：空串时**整个键消失**。落库仍写 {@code ''}（见类注释）。
     */
    @JsonProperty("replaces_id")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String replacesId = "";

    /** 无 omitempty：未取代时输出 {@code ""}（不是 {@code null}）。 */
    @JsonProperty("superseded_by")
    private String supersededBy = "";

    @JsonProperty("last_used_at")
    @JsonSerialize(using = GoTimeSerializer.class)
    @JsonDeserialize(using = GoTimeDeserializer.class)
    private OffsetDateTime lastUsedAt;

    @JsonProperty("use_count")
    private int useCount;

    /**
     * 标记"这是系统推出来的、不是被告知的"。**仅运行期**：这件事的持久记录是
     * {@link MemoryKinds#STATUS_PENDING}，所以 Go 的 tag 是 {@code json:"-" gorm:"-"}——
     * 既不出响应，**也不落库**（{@code gorm:"-"} 才是关键，别只看到 {@code json:"-"}）。
     */
    @JsonIgnore
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private boolean inferred;

    @JsonProperty("created_at")
    @JsonSerialize(using = GoTimeSerializer.class)
    @JsonDeserialize(using = GoTimeDeserializer.class)
    private OffsetDateTime createdAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

    @JsonProperty("updated_at")
    @JsonSerialize(using = GoTimeSerializer.class)
    @JsonDeserialize(using = GoTimeDeserializer.class)
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
