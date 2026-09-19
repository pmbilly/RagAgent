package com.ragagent.browserskill.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 对照 Go {@code browserskill.AccountStatus}（authorization.go L157-162）：
 * 持久设备元数据 + 实时连接状态的合并视图，GET /api/v1/me/browser 的 data 形状。
 *
 * <p>Go 用 struct 嵌入（embedded Status）——JSON 是**扁平**的，字段顺序 =
 * extension_available → Status 全部字段（声明序）→ device(omitempty)。
 * Java 侧没有嵌入语法，这里平铺同序，由 {@link #from(BrowserStatus, boolean, DeviceRecord)}
 * 收口组装（golden bs-account-get*.json 钉住键序）。</p>
 */
@JsonPropertyOrder({
        "extension_available",
        "action", "action_elapsed_ms", "page_url", "last_error", "stopping",
        "help_prompt", "idle", "needs_help", "enabled", "selected",
        "connected", "paused", "task_id",
        "device"
})
public class AccountStatus {

    @JsonProperty("extension_available")
    private boolean extensionAvailable;

    @JsonProperty("action")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String action;

    @JsonProperty("action_elapsed_ms")
    private long actionElapsedMs;

    @JsonProperty("page_url")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String pageUrl;

    @JsonProperty("last_error")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String lastError;

    @JsonProperty("stopping")
    private boolean stopping;

    @JsonProperty("help_prompt")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String helpPrompt;

    @JsonProperty("idle")
    private boolean idle;

    @JsonProperty("needs_help")
    private boolean needsHelp;

    @JsonProperty("enabled")
    private boolean enabled;

    @JsonProperty("selected")
    private boolean selected;

    @JsonProperty("connected")
    private boolean connected;

    @JsonProperty("paused")
    private boolean paused;

    @JsonProperty("task_id")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String taskId;

    @JsonProperty("device")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private DeviceRecord device;

    public boolean isExtensionAvailable() { return extensionAvailable; }
    public void setExtensionAvailable(boolean extensionAvailable) { this.extensionAvailable = extensionAvailable; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public long getActionElapsedMs() { return actionElapsedMs; }
    public void setActionElapsedMs(long actionElapsedMs) { this.actionElapsedMs = actionElapsedMs; }
    public String getPageUrl() { return pageUrl; }
    public void setPageUrl(String pageUrl) { this.pageUrl = pageUrl; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public boolean isStopping() { return stopping; }
    public void setStopping(boolean stopping) { this.stopping = stopping; }
    public String getHelpPrompt() { return helpPrompt; }
    public void setHelpPrompt(String helpPrompt) { this.helpPrompt = helpPrompt; }
    public boolean isIdle() { return idle; }
    public void setIdle(boolean idle) { this.idle = idle; }
    public boolean isNeedsHelp() { return needsHelp; }
    public void setNeedsHelp(boolean needsHelp) { this.needsHelp = needsHelp; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isSelected() { return selected; }
    public void setSelected(boolean selected) { this.selected = selected; }
    public boolean isConnected() { return connected; }
    public void setConnected(boolean connected) { this.connected = connected; }
    public boolean isPaused() { return paused; }
    public void setPaused(boolean paused) { this.paused = paused; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public DeviceRecord getDevice() { return device; }
    public void setDevice(DeviceRecord device) { this.device = device; }

    /**
     * 对照 Go 的字面量构造 + 逐字段回填：
     * {@code AccountStatus{Status: Status{Enabled: m.Enabled()}, ExtensionAvailable: ...}}
     * 然后在有活跃设备时填 Device 与 Connected。
     */
    public static AccountStatus create(boolean enabled, boolean extensionAvailable) {
        AccountStatus a = new AccountStatus();
        a.enabled = enabled;
        a.extensionAvailable = extensionAvailable;
        return a;
    }

    /** 把实时 Status 的字段并进本视图（对照 result.Connected = status.Connected） */
    public void applyStatus(BrowserStatus status) {
        this.connected = status.isConnected();
    }
}
