package com.ragagent.embed.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ragagent.agentm.mapper.JsonbRawStringTypeHandler;

/**
 * Web embed 渠道（对照 Go {@code types.EmbedChannel}，internal/types/embed_channel.go）。
 *
 * <h2>GORM 隐式行为清单（约定 §3）</h2>
 * <ol>
 *   <li><b>钩子 BeforeCreate</b>（Go L43-63）：ID 为空生成 UUID、AgentID 兜底
 *       builtin-quick-answer、rate limit 兜底 30/10000、widget/header 兜底——
 *       Java 侧在 service 层显式赋值（service.Create 全部先规范化再落库）。</li>
 *   <li><b>default:true 的零值列在 GORM Create 时被省略 → 落 DB 默认</b>（golden 钉死：
 *       create 请求带 enabled:false / show_suggested_questions:false，落库后仍为 true，
 *       响应也是 true）。Java 在 service.Create 里对这两个布尔做同样归一。</li>
 *   <li><b>软删除</b>：{@code DeletedAt gorm.DeletedAt}——按约定不用 @TableLogic，
 *       查询显式带 {@code deleted_at IS NULL}，删除显式 UPDATE。</li>
 *   <li><b>PublishToken / WebhookSecret 不进 JSON</b>（Go {@code json:"-"}）→
 *       响应体一律手工组 Map，实体本身不作响应。</li>
 *   <li><b>allowed_origins 是 jsonb</b>：raw JSON 文本直通（值可以是
 *       {@code "null"} 字面量——update 不带该键时 Go {@code json.Marshal(nil)="null"}
 *       整列覆写，读回 {@code null}，golden 钉死）。</li>
 * </ol>
 */
@TableName(value = "embed_channels", autoResultMap = true)
public class EmbedChannelEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private Long tenantId;

    private String agentId;

    private String name;

    private boolean enabled;

    @JsonIgnore
    private String publishToken;

    @TableField(value = "allowed_origins", typeHandler = JsonbRawStringTypeHandler.class)
    private String allowedOrigins;

    private String welcomeMessage;

    private int rateLimitPerMinute;

    private int rateLimitPerDay;

    private String primaryColor;

    private String pageTitle;

    private String headerTitleMode;

    private boolean showSuggestedQuestions;

    private boolean showThinking;

    private String widgetPosition;

    private boolean allowWebSearch;

    private boolean allowFileUpload;

    private String defaultLocale;

    @JsonIgnore
    private String webhookUrl;

    @JsonIgnore
    private String webhookSecret;

    private String launcherIcon;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    private OffsetDateTime deletedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getPublishToken() { return publishToken; }
    public void setPublishToken(String publishToken) { this.publishToken = publishToken; }
    public String getAllowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(String allowedOrigins) { this.allowedOrigins = allowedOrigins; }
    public String getWelcomeMessage() { return welcomeMessage; }
    public void setWelcomeMessage(String welcomeMessage) { this.welcomeMessage = welcomeMessage; }
    public int getRateLimitPerMinute() { return rateLimitPerMinute; }
    public void setRateLimitPerMinute(int rateLimitPerMinute) { this.rateLimitPerMinute = rateLimitPerMinute; }
    public int getRateLimitPerDay() { return rateLimitPerDay; }
    public void setRateLimitPerDay(int rateLimitPerDay) { this.rateLimitPerDay = rateLimitPerDay; }
    public String getPrimaryColor() { return primaryColor; }
    public void setPrimaryColor(String primaryColor) { this.primaryColor = primaryColor; }
    public String getPageTitle() { return pageTitle; }
    public void setPageTitle(String pageTitle) { this.pageTitle = pageTitle; }
    public String getHeaderTitleMode() { return headerTitleMode; }
    public void setHeaderTitleMode(String headerTitleMode) { this.headerTitleMode = headerTitleMode; }
    public boolean isShowSuggestedQuestions() { return showSuggestedQuestions; }
    public void setShowSuggestedQuestions(boolean showSuggestedQuestions) { this.showSuggestedQuestions = showSuggestedQuestions; }
    public boolean isShowThinking() { return showThinking; }
    public void setShowThinking(boolean showThinking) { this.showThinking = showThinking; }
    public String getWidgetPosition() { return widgetPosition; }
    public void setWidgetPosition(String widgetPosition) { this.widgetPosition = widgetPosition; }
    public boolean isAllowWebSearch() { return allowWebSearch; }
    public void setAllowWebSearch(boolean allowWebSearch) { this.allowWebSearch = allowWebSearch; }
    public boolean isAllowFileUpload() { return allowFileUpload; }
    public void setAllowFileUpload(boolean allowFileUpload) { this.allowFileUpload = allowFileUpload; }
    public String getDefaultLocale() { return defaultLocale; }
    public void setDefaultLocale(String defaultLocale) { this.defaultLocale = defaultLocale; }
    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }
    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
    public String getLauncherIcon() { return launcherIcon; }
    public void setLauncherIcon(String launcherIcon) { this.launcherIcon = launcherIcon; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime deletedAt) { this.deletedAt = deletedAt; }
}
