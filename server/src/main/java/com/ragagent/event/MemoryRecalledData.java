package com.ragagent.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 本轮注入的长期记忆（对照 Go {@code event.MemoryRecalledData}，internal/event/event_data.go:191-193）。
 *
 * <p>实录锚点：零值输出 {@code {"memories":null}}（无 omitempty）。
 * Go 的 {@code []types.UsedMemory} 同样为解耦作 interface{}。</p>
 */
@JsonPropertyOrder({"memories"})
public class MemoryRecalledData {

    /** 无 omitempty：null 恒输出 */
    @JsonProperty("memories")
    private Object memories;

    public MemoryRecalledData() {
    }

    public MemoryRecalledData(Object memories) {
        this.memories = memories;
    }

    public Object getMemories() {
        return memories;
    }

    public void setMemories(Object v) {
        this.memories = v;
    }
}
