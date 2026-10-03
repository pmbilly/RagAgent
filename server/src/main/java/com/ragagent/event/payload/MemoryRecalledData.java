package com.ragagent.event.payload;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 本轮注入的长期记忆。
 *
 * <p>零值输出 {@code {"memories":null}}（恒输出）。
 * 为与本包外领域类型解耦，元素以 Object 承载。</p>
 */
@JsonPropertyOrder({"memories"})
public class MemoryRecalledData {

    /** null 也输出 null */
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
