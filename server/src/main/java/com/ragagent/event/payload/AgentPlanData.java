package com.ragagent.event.payload;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Agent 计划事件数据（对照 Go {@code event.AgentPlanData}，internal/event/event_data.go:104-108）。
 *
 * <p>实录锚点：{@code plan} <b>无 omitempty</b>——nil slice 输出 {@code "plan":null}、
 * 空 slice 输出 {@code "plan":[]}，Java 侧 null List 即 null、空 List 即 []，别归一化。</p>
 */
@JsonPropertyOrder({"query", "plan", "duration_ms"})
public class AgentPlanData {

    @JsonProperty("query")
    private String query = "";

    /** 步骤描述；无 omitempty：null（Go nil）与 []（Go 空 slice）都按原样输出 */
    @JsonProperty("plan")
    private List<String> plan;

    /** Go omitempty */
    @JsonProperty("duration_ms")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private long durationMs;

    public AgentPlanData() {
    }

    public AgentPlanData(String query, List<String> plan, long durationMs) {
        this.query = QueryData.orEmpty(query);
        this.plan = plan;
        this.durationMs = durationMs;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String v) {
        this.query = QueryData.orEmpty(v);
    }

    public List<String> getPlan() {
        return plan;
    }

    public void setPlan(List<String> v) {
        this.plan = v;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long v) {
        this.durationMs = v;
    }
}
