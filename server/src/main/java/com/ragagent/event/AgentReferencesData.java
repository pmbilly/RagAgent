package com.ragagent.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 知识引用数据（对照 Go {@code event.AgentReferencesData}，internal/event/event_data.go:183-186）。
 *
 * <p>实录锚点：{@code references} 无 omitempty——nil 输出 {@code "references":null}。
 * Go 的 {@code []*types.SearchResult} 为保持 event 包无 types 依赖而作 interface{}，
 * Java 同理（{@code retrieval.domain.SearchResult} 的列表）。</p>
 */
@JsonPropertyOrder({"references", "iteration"})
public class AgentReferencesData {

    /** 无 omitempty：null 恒输出 */
    @JsonProperty("references")
    private Object references;

    @JsonProperty("iteration")
    private int iteration;

    public AgentReferencesData() {
    }

    public AgentReferencesData(Object references, int iteration) {
        this.references = references;
        this.iteration = iteration;
    }

    public Object getReferences() {
        return references;
    }

    public void setReferences(Object v) {
        this.references = v;
    }

    public int getIteration() {
        return iteration;
    }

    public void setIteration(int v) {
        this.iteration = v;
    }
}
