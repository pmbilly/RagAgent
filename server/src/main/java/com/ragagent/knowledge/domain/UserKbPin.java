package com.ragagent.knowledge.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * user_kb_pins（迁移 000050）：per-(user,kb) 置顶。
 * 部分唯一索引 user_kb_pins_user_kb_key ON (user_id, knowledge_base_id)。
 */
@TableName("user_kb_pins")
public class UserKbPin {

    private String userId;
    private String knowledgeBaseId;
    private OffsetDateTime createdAt;

    public String getUserId() { return userId; }
    public void setUserId(String v) { userId = v; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { knowledgeBaseId = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { createdAt = v; }
}
