package com.ragagent.memory.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 某个用户**已合并生效**的记忆状态（对照 Go {@code types.MemorySettings}，
 * internal/types/memory.go:975-990）。
 *
 * <p>UI 直接渲染这个，而不是自己去合并"工作区设置 × 用户设置"——
 * 这样"我的记忆为什么是关的"只有一个答案。</p>
 *
 * <p>六个字段都**没有 omitempty**，恒输出（{@code GET /memory/settings} 的响应体）。</p>
 */
@JsonPropertyOrder({
        "workspace_enabled", "user_enabled", "effective", "write_mode", "item_count", "max_items"
})
public class MemorySettings {

    /** 工作区上的管理员开关。 */
    @JsonProperty("workspace_enabled")
    private boolean workspaceEnabled;

    /**
     * 调用方自己的退出开关。工作区开关关着时它没有意义，
     * 但仍然如实上报——这样管理员把工作区重新打开时，这个开关还在原位。
     */
    @JsonProperty("user_enabled")
    private boolean userEnabled;

    /** 实际生效值：{@code workspace_enabled && user_enabled}。 */
    @JsonProperty("effective")
    private boolean effective;

    /** 工作区的写入模式。 */
    @JsonProperty("write_mode")
    private String writeMode = "";

    /** 调用方当前有多少条活跃记忆。 */
    @JsonProperty("item_count")
    private int itemCount;

    /** 容量上限，超过后排名最低的会被归档。 */
    @JsonProperty("max_items")
    private int maxItems;

    public boolean isWorkspaceEnabled() { return workspaceEnabled; }
    public void setWorkspaceEnabled(boolean v) { workspaceEnabled = v; }

    public boolean isUserEnabled() { return userEnabled; }
    public void setUserEnabled(boolean v) { userEnabled = v; }

    public boolean isEffective() { return effective; }
    public void setEffective(boolean v) { effective = v; }

    public String getWriteMode() { return writeMode; }
    public void setWriteMode(String v) { writeMode = v == null ? "" : v; }

    public int getItemCount() { return itemCount; }
    public void setItemCount(int v) { itemCount = v; }

    public int getMaxItems() { return maxItems; }
    public void setMaxItems(int v) { maxItems = v; }
}
