package com.ragagent.memory.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ragagent.common.web.GoTimeSerializer;

/**
 * 这个人的回答有多经常取材于某个文档
 * （对照 Go {@code types.MemoryDocAffinity}，internal/types/memory.go L784-795）。
 *
 * <p>这是我们唯一一个**不用问用户任何东西**就能拿到的个人检索信号，
 * 而且它刻意只是一个普通计数器、不是一张图：上一次尝试建了一张锚点表，
 * 四个消费方全都把它过滤掉了，所以现在的规矩是"这张表要不跟读它的代码一起上线，要不就别建"。</p>
 *
 * <h2>GORM 隐式行为清单（约定 §3）</h2>
 * <ol>
 *   <li><b>自动时间戳</b>：{@code created_at}/{@code updated_at} 走字段名约定；
 *       {@code last_used_at} 由 {@code BumpDocAffinity} 的 INSERT 显式写 {@code now}。</li>
 *   <li><b>钩子</b>：无。</li>
 *   <li><b>关联预加载</b>：无。</li>
 *   <li><b>软删除</b>：无。</li>
 *   <li><b>默认排序</b>：{@code hits DESC, last_used_at DESC}（TopDocAffinity /
 *       ListFamiliarDocs），仓库层显式写。</li>
 *   <li><b>唯一索引</b>：{@code idx_mem_affinity_scope (tenant_id, subject_id, knowledge_id)}
 *       ——模型 tag 与迁移都声明了。</li>
 *   <li><b>DEFAULT 列</b>：{@code knowledge_base_id}/{@code title}（{@code default:''}）、
 *       {@code hits}（{@code default:0}）带字面量 default tag → GORM 实测仍显式写入。</li>
 * </ol>
 *
 * <p>本类型**不是**响应体（handler 回的是 {@link MemoryDocView}），但键按 Go struct 声明序。</p>
 */
@TableName("memory_doc_affinity")
public class MemoryDocAffinity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id = "";

    private Long tenantId = 0L;

    private String subjectId = "";

    private String knowledgeId = "";

    private String knowledgeBaseId = "";

    private String title = "";

    private int hits;

    private OffsetDateTime lastUsedAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

    private OffsetDateTime createdAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

    private OffsetDateTime updatedAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

    public String getId() { return id; }
    public void setId(String v) { id = v == null ? "" : v; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { tenantId = v == null ? 0L : v; }

    public String getSubjectId() { return subjectId; }
    public void setSubjectId(String v) { subjectId = v == null ? "" : v; }

    public String getKnowledgeId() { return knowledgeId; }
    public void setKnowledgeId(String v) { knowledgeId = v == null ? "" : v; }

    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { knowledgeBaseId = v == null ? "" : v; }

    public String getTitle() { return title; }
    public void setTitle(String v) { title = v == null ? "" : v; }

    public int getHits() { return hits; }
    public void setHits(int v) { hits = v; }

    public OffsetDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(OffsetDateTime v) {
        lastUsedAt = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) {
        createdAt = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) {
        updatedAt = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }
}
