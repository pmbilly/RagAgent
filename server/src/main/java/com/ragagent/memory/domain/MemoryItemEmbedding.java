package com.ragagent.memory.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ragagent.common.web.ZeroTimeSerializer;

/**
 * 一条记忆的向量，单独一张表。
 *
 * <p>**刻意**与 {@code memory_items} 分开：记忆管理器、常驻块和容量控制在不停地列条目，
 * 它们没有一个愿意顺带拖着每行几 KB 的 float。只有真正给相似度打分的那段代码才读它。</p>
 *
 * <h2>JSON 形态（键名＝Java 字段名）</h2>
 * <pre>
 *   MemoryItemEmbedding{} →
 *   {"itemId":"","tenantId":0,"subjectId":"","modelId":"","dims":0,
 *    "createdAt":"0001-01-01T00:00:00Z","updatedAt":"0001-01-01T00:00:00Z"}
 * </pre>
 * <p>{@code sourceContent} / {@code sourceTopic} / {@code vector} 一个键都不出。</p>
 *
 * <h2>⚠️ 三个不出 JSON 的字段含义**不一样**</h2>
 * <ul>
 *   <li>{@code sourceContent} / {@code sourceTopic}：
 *       既不出响应也**不落库**，是纯粹的"输入快照"（防止慢的 embedding 调用
 *       覆盖更新的编辑），所以 Java 侧要 {@code @TableField(exist=false)}。</li>
 *   <li>{@code vector}：**不**出响应，
 *       但**要落库**（它是源真值）。Java 侧保留 {@code byte[]} 列。</li>
 * </ul>
 *
 * <h2>落库隐式行为清单（约定 §3）</h2>
 * <ol>
 *   <li><b>自动时间戳</b>：{@code created_at}/{@code updated_at} 走字段名约定；
 *       {@code UpsertItemEmbedding} 还会显式写 {@code now}（并在 created 为零值时补上）。</li>
 *   <li><b>钩子</b>：无。</li>
 *   <li><b>软删除</b>：无。</li>
 *   <li><b>默认排序</b>：无——本表的读法只有"按 item_id 取"与"按 scope 扫"。</li>
 *   <li><b>主键</b>：{@code item_id}（**不是 id**），所以 upsert 按 item_id 冲突。
 *       Java 实体必须把 {@code itemId} 标成 {@code @TableId}。</li>
 *   <li><b>索引</b>：{@code idx_mem_emb_scope (tenant_id, subject_id)}；
 *       PG 上另有迁移 000095 条件创建的 {@code idx_mem_emb_search}
 *       {@code (tenant_id, subject_id, model_id, dims)} 与 pgvector 的 {@code embedding halfvec} 列。</li>
 *   <li><b>DEFAULT 列</b>：{@code model_id}（{@code default:''}）、{@code dims}（{@code default:0}）
 *       带字面量 default tag → 落库时仍显式写入。</li>
 * </ol>
 *
 * <p><b>{@code embedding} 列刻意不映射</b>：pgvector 的 {@code halfvec} 只在 PG 上存在，
 * 且只通过裸 SQL 写它（见 {@code writeVectorColumn}）。MyBatis-Plus 若映射它，
 * 在 H2 上就没有对应的列，实体一 insert 就炸。</p>
 */
@TableName("memory_item_embeddings")
public class MemoryItemEmbedding {

    /** 输入快照——不出响应，**不落库**。 */
    @TableField(exist = false)
    @JsonIgnore
    private String sourceContent = "";

    /** 输入快照——不出响应，**不落库**。 */
    @TableField(exist = false)
    @JsonIgnore
    private String sourceTopic = "";

    @TableId(value = "item_id", type = IdType.INPUT)
    private String itemId = "";

    private Long tenantId = 0L;

    private String subjectId = "";

    /**
     * 这个向量是哪个模型产生的。不同模型的向量不可比，所以换模型必须让它们失效，
     * 而不是悄悄拿它们算出没意义的分数。
     */
    private String modelId = "";

    private int dims;

    /**
     * little-endian float32。用 JSON 存会大四倍且毫无好处：除了本包没人读它。
     *
     * <p>注意它只是不出 JSON——**仍然落库**（bytea 列）。</p>
     */
    @JsonIgnore
    private byte[] vector;

    private OffsetDateTime createdAt = ZeroTimeSerializer.ZERO_DATE_TIME;

    private OffsetDateTime updatedAt = ZeroTimeSerializer.ZERO_DATE_TIME;

    public String getSourceContent() { return sourceContent; }
    public void setSourceContent(String v) { sourceContent = v == null ? "" : v; }

    public String getSourceTopic() { return sourceTopic; }
    public void setSourceTopic(String v) { sourceTopic = v == null ? "" : v; }

    public String getItemId() { return itemId; }
    public void setItemId(String v) { itemId = v == null ? "" : v; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { tenantId = v == null ? 0L : v; }

    public String getSubjectId() { return subjectId; }
    public void setSubjectId(String v) { subjectId = v == null ? "" : v; }

    public String getModelId() { return modelId; }
    public void setModelId(String v) { modelId = v == null ? "" : v; }

    public int getDims() { return dims; }
    public void setDims(int v) { dims = v; }

    public byte[] getVector() { return vector; }
    public void setVector(byte[] v) { vector = v; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) {
        createdAt = v == null ? ZeroTimeSerializer.ZERO_DATE_TIME : v;
    }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) {
        updatedAt = v == null ? ZeroTimeSerializer.ZERO_DATE_TIME : v;
    }
}
