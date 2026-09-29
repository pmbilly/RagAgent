package com.ragagent.knowledge.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * user_kb_pins：per-(user,knowledge base) 置顶。
 * 主键 (tenant_id, user_id, knowledge_base_id)；索引
 * idx_user_kb_pins_user_tenant_pinned_at (tenant_id, user_id, pinned_at DESC)。
 */
@TableName("user_kb_pins")
public class UserKbPin {

    private String userId;
    private String knowledgeBaseId;
    private OffsetDateTime pinnedAt;

    public String getUserId() { return userId; }
    public void setUserId(String v) { userId = v; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { knowledgeBaseId = v; }
    public OffsetDateTime getPinnedAt() { return pinnedAt; }
    public void setPinnedAt(OffsetDateTime v) { pinnedAt = v; }
}
