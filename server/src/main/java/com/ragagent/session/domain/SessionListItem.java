package com.ragagent.session.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ragagent.common.web.PgJsonTypeHandler;

/**
 * 列表里的一行会话 = 会话字段 + IM 来源字段（对照 Go {@code types.SessionListItem}，
 * internal/types/session.go L227-235）。
 *
 * <p><b>为什么这里把字段平铺，而不是继承 {@link Session} 或用 {@code @JsonUnwrapped}</b>：
 * Go 用的是**结构体内嵌 + 外层同名字段遮蔽</b>——{@code SessionListItem} 自己又声明了一个
 * {@code IMPlatform}，把内嵌 {@code Session} 的那个（{@code gorm:"-"}，本来就没有列）盖掉。
 * Go 的 encoding/json 按"深度浅者胜"解析，所以 {@code im_platform} **只出现一次**，
 * 位置在外层声明处（即 {@code deleted_at} 之后）。</p>
 *
 * <p>Java 侧若用继承或 {@code @JsonUnwrapped}，两个 {@code imPlatform} 会撞成重复键，
 * 且键序由 Jackson 的内部规则决定、不受控。而这是**响应体**（GET /sessions 的
 * {@code items} 数组元素），键序＝字段声明序才受控——所以保持平铺；显式的键序注解已在
 * §14.9l S1 换锚时退役（声明序即线格式顺序，键名＝Java 字段名）。</p>
 */
@TableName(value = "sessions", autoResultMap = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionListItem {

    private String id;

    // Go 字符串零值语义：GORM 扫 NULL 列得 ""（恒输出的键不允许出 null）
    private String title = "";

    private String description = "";

    private Long tenantId;

    private String userId;

    /** 字段名不带 {@code is} 前缀——理由见 {@link Session} 上同名字段的注释（重复键 + MP lambda）。 */
    private boolean pinned;

    private OffsetDateTime pinnedAt;

    @TableField(value = "agent_config", typeHandler = PgJsonTypeHandler.class)
    private SessionLastRequestState lastRequestState;


    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    private OffsetDateTime deletedAt;

    // ── 以下六个来自 LEFT JOIN im_channel_sessions；Web 建的会话全为空 ──────────

    private String imPlatform = "";

    private String imChatId = "";

    private String imThreadId = "";

    private String imUserId = "";

    private String imAgentId = "";

    private String imChannelId = "";

    public SessionListItem() {
    }

    public String getId() {
        return id;
    }

    public void setId(String v) {
        this.id = v;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String v) {
        this.title = v == null ? "" : v;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String v) {
        this.description = v == null ? "" : v;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long v) {
        this.tenantId = v;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String v) {
        this.userId = v;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean v) {
        this.pinned = v;
    }

    public OffsetDateTime getPinnedAt() {
        return pinnedAt;
    }

    public void setPinnedAt(OffsetDateTime v) {
        this.pinnedAt = v;
    }

    public SessionLastRequestState getLastRequestState() {
        return lastRequestState;
    }

    public void setLastRequestState(SessionLastRequestState v) {
        this.lastRequestState = v;
    }


    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime v) {
        this.createdAt = v;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime v) {
        this.updatedAt = v;
    }

    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(OffsetDateTime v) {
        this.deletedAt = v;
    }

    public String getImPlatform() {
        return imPlatform;
    }

    public void setImPlatform(String v) {
        this.imPlatform = v == null ? "" : v;
    }

    public String getImChatId() {
        return imChatId;
    }

    public void setImChatId(String v) {
        this.imChatId = v == null ? "" : v;
    }

    public String getImThreadId() {
        return imThreadId;
    }

    public void setImThreadId(String v) {
        this.imThreadId = v == null ? "" : v;
    }

    public String getImUserId() {
        return imUserId;
    }

    public void setImUserId(String v) {
        this.imUserId = v == null ? "" : v;
    }

    public String getImAgentId() {
        return imAgentId;
    }

    public void setImAgentId(String v) {
        this.imAgentId = v == null ? "" : v;
    }

    public String getImChannelId() {
        return imChannelId;
    }

    public void setImChannelId(String v) {
        this.imChannelId = v == null ? "" : v;
    }
}
