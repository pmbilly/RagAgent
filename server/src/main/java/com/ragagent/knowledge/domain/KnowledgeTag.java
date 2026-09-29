package com.ragagent.knowledge.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * knowledge_tags 行。
 * <p><b>刻意不作响应体</b>：知识详情里的 tags 数组由 service 组装成 ObjectNode
 * sort_order/created_at/updated_at），见 KnowledgeService.attachTags。
 * 因此本实体不挂 @JsonProperty——若后续要直接序列化，必须补 @JsonPropertyOrder
 * 并加 JsonContractRoundTripTest 条目（本仓约定）。</p>
 * 仓储行为契约：
 * - 软删除 deleted_at → 查询侧显式 isNull（本项目不用 @TableLogic）
 * - seq_id 由 DB 序列供给（PG）；H2 测试播种时显式给值
 */
@TableName("knowledge_tags")
public class KnowledgeTag {

    @TableId(type = IdType.INPUT)
    private String id;
    private Long seqId;
    private Long tenantId;
    private String knowledgeBaseId;
    private String name;
    private String color;
    private Integer sortOrder;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime deletedAt;

    public String getId() { return id; }
    public void setId(String v) { id = v; }
    public Long getSeqId() { return seqId; }
    public void setSeqId(Long v) { seqId = v; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { tenantId = v; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { knowledgeBaseId = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getColor() { return color; }
    public void setColor(String v) { color = v; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer v) { sortOrder = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { updatedAt = v; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime v) { deletedAt = v; }

    /** 查询投影辅助，无业务含义（不落库） */
    @TableField(exist = false)
    @JsonIgnore
    private String knowledgeId;

    public String getKnowledgeId() { return knowledgeId; }
    public void setKnowledgeId(String v) { knowledgeId = v; }
}
