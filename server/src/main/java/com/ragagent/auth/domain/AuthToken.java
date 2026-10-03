package com.ragagent.auth.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * auth_tokens 表实体。
 *
 * 落库行为清单：
 * - 无软删除列 → 查询不加 deleted_at 条件
 * - is_revoked 默认 false；id 为 app 生成 UUID（varchar(36)）
 * - created_at/updated_at 由 DB DEFAULT 填充（应用侧显式赋值等价）
 */
@TableName("auth_tokens")
public class AuthToken {

    @TableId(type = IdType.INPUT)
    private String id;
    private String userId;
    /** JWT 原文（text 列） */
    private String token;
    /** access_token / refresh_token */
    private String tokenType;
    private OffsetDateTime expiresAt;
    private boolean isRevoked;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String v) { userId = v; }
    public String getToken() { return token; }
    public void setToken(String v) { token = v; }
    public String getTokenType() { return tokenType; }
    public void setTokenType(String v) { tokenType = v; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime v) { expiresAt = v; }
    public boolean isIsRevoked() { return isRevoked; }
    public void setIsRevoked(boolean v) { isRevoked = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { updatedAt = v; }
}
