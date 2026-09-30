package com.ragagent.memory.domain;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.ragagent.common.web.GoTimeSerializer;

/**
 * 一个记忆空间：**一个工作区里的一个主体**
 * （对照 Go {@code types.MemorySubject}，internal/types/memory.go L191-232）。
 * scope 一律从请求上下文推导，绝不来自客户端传的 id。
 *
 * <h2>GORM 隐式行为清单（约定 §3）</h2>
 * <ol>
 *   <li><b>自动时间戳</b>：{@code created_at}/{@code updated_at} 走 GORM 的
 *       {@code CreatedAt}/{@code UpdatedAt} 字段名约定（AutoCreateTime / AutoUpdateTime），
 *       所以 Go 在 INSERT 时**显式**写入当前时间，DB 的 {@code DEFAULT CURRENT_TIMESTAMP}
 *       实际上永远用不上。Java 侧在 {@code MemoryRepository.ensureSubject} 里显式赋值——
 *       <b>不</b>依赖 DDL 默认值，与 Go 的落库字节一致。</li>
 *   <li><b>钩子</b>：无 BeforeCreate/AfterFind 之类的钩子（id 由仓储层显式生成）。</li>
 *   <li><b>关联预加载</b>：无（本表没有任何关联）。</li>
 *   <li><b>软删除</b>：无（本表没有 deleted_at 列，删除都是物理删）。</li>
 *   <li><b>默认排序</b>：无——读路径恒是"按 (tenant_id, subject_id) 取唯一一行"。</li>
 *   <li><b>唯一索引</b>：{@code idx_memory_subjects_scope (tenant_id, subject_id)}。
 *       Go 把它**同时**声明在模型 tag 上（{@code uniqueIndex:idx_memory_subjects_scope}）
 *       而不只是在迁移里，这样 {@code EnsureSubject} 的 upsert 在任何自动迁移出来的库上
 *       都有约束可以打。Java 侧同样依赖这条约束（{@code ON CONFLICT ... DO NOTHING}）。</li>
 *   <li><b>DEFAULT 列</b>：{@code enabled}（{@code default:true} 的 struct tag）、
 *       {@code item_count}（{@code default:0}）、{@code block_text}（迁移里的
 *       {@code DEFAULT ''}）。<b>GORM 实测对带字面量 default tag 的字段仍会显式写入</b>
 *       （{@code DefaultValueInterface} 非 nil → 列进 INSERT 列表），
 *       所以 Java 侧也一律显式赋值，不依赖 DDL 默认值。
 *       ⚠️ {@code extraction_state} / {@code pending_sessions} 是 jsonb，
 *       恒写入一段 JSON（**从不** NULL），故策略取 {@code ALWAYS}（§9 的 DEFAULT 列坑）。</li>
 * </ol>
 *
 * <h2>JSON 形态</h2>
 * <p>本类型**不是** HTTP 响应体（handler 只回 MemorySettings / MemoryItem / 各种 View），
 * 但它是 Go struct，键序按声明序、语义按 json tag 抄。</p>
 * <ul>
 *   <li>{@code extraction_state} 的 tag 是 {@code json:"-"} → 一个键都不出；</li>
 *   <li>{@code pending_sessions} 无 omitempty → nil 输出 {@code null}
 *       （而**落库**时 nil 写 {@code []}，两件事不冲突，见类型处理器）；</li>
 *   <li>三个 {@code *time.Time} 指针字段输出 {@code null}；</li>
 *   <li>{@code created_at}/{@code updated_at} 是值类型，零值输出
 *       {@code "0001-01-01T00:00:00Z"}（Go 实录，见 {@code MemoryEntityJsonTest}）。</li>
 * </ul>
 */
@TableName(value = "memory_subjects", autoResultMap = true)
@JsonPropertyOrder({
        "id", "tenant_id", "subject_id", "enabled", "block_text", "block_updated_at",
        "item_count", "last_extracted_at", "extract_cursor", "pending_sessions",
        "extract_scheduled_at", "consolidated_at", "forced_consolidated_at",
        "created_at", "updated_at"
})
public class MemorySubject {

    @TableId(value = "id", type = IdType.INPUT)
    @JsonProperty("id")
    private String id = "";

    @JsonProperty("tenant_id")
    private Long tenantId = 0L;

    /** 对照 Go {@code Principal.StorageID()}："web_user:<uuid>" / "im_user:wecom:<ch>:<u>"。 */
    @JsonProperty("subject_id")
    private String subjectId = "";

    /**
     * 每用户自己的开关。工作区的开关在 {@code tenants.memory_config} 上，**优先于**它。
     *
     * <p>字段默认值取 Go 的**零值 {@code false}**（不是 DDL 的 {@code DEFAULT true}）——
     * Go 实录里 {@code MemorySubject{}} 输出的是 {@code "enabled":false}。
     * {@code EnsureSubject} 会显式置 true，所以线上几乎见不到 false 的新行。</p>
     */
    @JsonProperty("enabled")
    private boolean enabled;

    /**
     * 渲染好的 profile/preference 常驻块。写的时候重算，读的时候原样用，
     * 于是读路径永远只是一次主键查询。
     */
    @JsonProperty("block_text")
    private String blockText = "";

    @JsonProperty("block_updated_at")
    private OffsetDateTime blockUpdatedAt;

    @JsonProperty("item_count")
    private int itemCount;

    @JsonProperty("last_extracted_at")
    private OffsetDateTime lastExtractedAt;

    /**
     * 遗留下的主体级水位线，只为"新初始化的会话游标"保留升级边界。
     * 新 worker **从不**推进它；每个会话有自己的进度行。
     */
    @JsonProperty("extract_cursor")
    private OffsetDateTime extractCursor;

    /**
     * tag 是 {@code json:"-"}：**一个键都不出**（对照 Go 的 {@code json:"-"}）。
     * 它不是"派生访问器"，只是不出响应。
     */
    @TableField(value = "extraction_state", typeHandler = MemoryExtractionStateTypeHandler.class,
            insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    @JsonIgnore
    private MemoryExtractionState extractionState = new MemoryExtractionState();

    /**
     * 遗留队列：下一次 enqueue 或 claim 时一次性导入到有索引的进度行里。
     * 新 worker 不会让它变长。
     *
     * <p>无 omitempty：nil 在响应里是 {@code null}，但**落库**写 {@code []}
     * （Go 的 {@code Value()} 对 nil 返回 {@code json.Marshal([]string{})}）。</p>
     *
     * <p>⚠️ 所以字段默认值是 {@code null}（对齐 Go 零值的 JSON），
     * <b>不是</b>空列表——{@code EnsureSubject} 与 {@code saveExtractionState}
     * 这两条落库路径各自负责把 null 归一成空列表，别指望字段默认值兜。
     * 反过来，读库回来的 {@code []} 会变成**非 null 的空列表**（Go 的
     * {@code json.Unmarshal("[]")} 也会 MakeSlice），于是响应是 {@code []}——
     * "新建对象是 null、读回来的行是 []"两种形态并存，与 Go 一致。</p>
     */
    @TableField(value = "pending_sessions", typeHandler = MemoryStringListTypeHandler.class,
            insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    @JsonProperty("pending_sessions")
    private List<String> pendingSessions;

    /** 标记"蒸馏任务已在路上"，让并发的多轮只排一个任务而不是每轮一个。 */
    @JsonProperty("extract_scheduled_at")
    private OffsetDateTime extractScheduledAt;

    /**
     * 这个主体的记忆上次被**整体**审阅是什么时候（而不是一轮一轮地看）。
     * 蒸馏只看得到最新的对话，所以"三周里五轮说了同一件事的五种说法"
     * 只有这里才注意得到。
     */
    @JsonProperty("consolidated_at")
    private OffsetDateTime consolidatedAt;

    /**
     * 本人上一次主动要求审阅的时间。**刻意**与 {@link #consolidatedAt} 分开计时：
     * 每日任务刚跑过不能成为拒绝"按按钮的人"的理由，所以两者不能共用一个时间戳。
     */
    @JsonProperty("forced_consolidated_at")
    private OffsetDateTime forcedConsolidatedAt;

    @JsonProperty("created_at")
    private OffsetDateTime createdAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

    @JsonProperty("updated_at")
    private OffsetDateTime updatedAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

    public String getId() { return id; }
    public void setId(String v) { id = v == null ? "" : v; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { tenantId = v == null ? 0L : v; }

    public String getSubjectId() { return subjectId; }
    public void setSubjectId(String v) { subjectId = v == null ? "" : v; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }

    public String getBlockText() { return blockText; }
    public void setBlockText(String v) { blockText = v == null ? "" : v; }

    public OffsetDateTime getBlockUpdatedAt() { return blockUpdatedAt; }
    public void setBlockUpdatedAt(OffsetDateTime v) { blockUpdatedAt = v; }

    public int getItemCount() { return itemCount; }
    public void setItemCount(int v) { itemCount = v; }

    public OffsetDateTime getLastExtractedAt() { return lastExtractedAt; }
    public void setLastExtractedAt(OffsetDateTime v) { lastExtractedAt = v; }

    public OffsetDateTime getExtractCursor() { return extractCursor; }
    public void setExtractCursor(OffsetDateTime v) { extractCursor = v; }

    public MemoryExtractionState getExtractionState() { return extractionState; }
    public void setExtractionState(MemoryExtractionState v) {
        extractionState = v == null ? new MemoryExtractionState() : v;
    }

    public List<String> getPendingSessions() { return pendingSessions; }
    public void setPendingSessions(List<String> v) {
        pendingSessions = v == null ? null : new ArrayList<>(v);
    }

    public OffsetDateTime getExtractScheduledAt() { return extractScheduledAt; }
    public void setExtractScheduledAt(OffsetDateTime v) { extractScheduledAt = v; }

    public OffsetDateTime getConsolidatedAt() { return consolidatedAt; }
    public void setConsolidatedAt(OffsetDateTime v) { consolidatedAt = v; }

    public OffsetDateTime getForcedConsolidatedAt() { return forcedConsolidatedAt; }
    public void setForcedConsolidatedAt(OffsetDateTime v) { forcedConsolidatedAt = v; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) {
        createdAt = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) {
        updatedAt = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }

    /**
     * 浅拷贝，供 {@code EnqueuePendingSession} 取"更新前的快照"用。
     *
     * <p>Go 里那句是 {@code snapshot = *subject}——**结构体按值复制**，
     * 所以之后对 {@code subject} 的字段赋值（清空 pending 队列、写
     * {@code extract_scheduled_at}）都不会影响快照。Java 的对象是引用，
     * 不显式复制就会把修改后的状态当成"更新前"返回给调用方。
     * {@code pendingSessions} 也换成一个新的列表，理由同上。</p>
     *
     * <p>名字刻意不带 {@code get}/{@code is} 前缀（§7.5 第 2 条）。</p>
     */
    public MemorySubject copy() {
        MemorySubject out = new MemorySubject();
        out.id = id;
        out.tenantId = tenantId;
        out.subjectId = subjectId;
        out.enabled = enabled;
        out.blockText = blockText;
        out.blockUpdatedAt = blockUpdatedAt;
        out.itemCount = itemCount;
        out.lastExtractedAt = lastExtractedAt;
        out.extractCursor = extractCursor;
        out.extractionState = extractionState;
        out.pendingSessions = pendingSessions == null ? null : new ArrayList<>(pendingSessions);
        out.extractScheduledAt = extractScheduledAt;
        out.consolidatedAt = consolidatedAt;
        out.forcedConsolidatedAt = forcedConsolidatedAt;
        out.createdAt = createdAt;
        out.updatedAt = updatedAt;
        return out;
    }
}
