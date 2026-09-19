package com.ragagent.browserskill.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 对照 Go {@code browserskill.Status}（manager.go L46-60）：连接与当前会话任务状态。
 *
 * <p>键序 = Go struct 声明序（不是字母序）；omitempty 的 string 字段用
 * NON_NULL（Java 侧默认 null → 省略），布尔/int64 无 omitempty 恒输出——
 * {@code "enabled":false} 这类零值出现在 golden 里（bs-account-get.json），
 * 别加 NON_DEFAULT 把它们吞掉（波 3 子批 3 的教训）。</p>
 */
@JsonPropertyOrder({
        "action", "action_elapsed_ms", "page_url", "last_error", "stopping",
        "help_prompt", "idle", "needs_help", "enabled", "selected",
        "connected", "paused", "task_id"
})
@JsonIgnoreProperties(ignoreUnknown = true)
public class BrowserStatus {

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

    /** 对照 Status{Enabled: m.Enabled()} 的零值起点 */
    public static BrowserStatus disabled(boolean enabled) {
        BrowserStatus s = new BrowserStatus();
        s.enabled = enabled;
        return s;
    }
}
