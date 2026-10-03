package com.ragagent.event.payload;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 知识引用数据。
 *
 * <p>{@code references} 恒输出——null 输出 {@code "references":null}。
 * 为保持本包不依赖 retrieval 领域类型，元素以 Object 承载
 * （{@code retrieval.domain.SearchResult} 的列表）。</p>
 */
@JsonPropertyOrder({"references", "iteration"})
public class AgentReferencesData {

    /** null 也输出 null */
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
