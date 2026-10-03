package com.ragagent.event.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 最终答案流式数据。
 * 7 个 emit 点（ThinkPhase / ObservePhase / FinalizePhase，见包注释 emit 表）——
 * 最高危的 SSE 时序事件：同一 id 的分片在客户端重组（扣留键 = 类型 + NUL + 事件 id）。
 *
 * <p>零值输出 {@code {"content":"","done":false}}；{@code is_fallback}
 * 为 true 才输出（无知识库命中的兜底回答标记）。</p>
 */
@JsonPropertyOrder({"content", "done", "is_fallback"})
public class AgentFinalAnswerData {

    @JsonProperty("content")
    private String content = "";

    /** false 恒输出（Done:true 是收尾标记） */
    @JsonProperty("done")
    private boolean done;

    /** 兜底回答（无知识库命中）标记；false 省略 */
    @JsonProperty("is_fallback")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean isFallback;

    public AgentFinalAnswerData() {
    }

    public AgentFinalAnswerData(String content, boolean done, boolean isFallback) {
        this.content = QueryData.orEmpty(content);
        this.done = done;
        this.isFallback = isFallback;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String v) {
        this.content = QueryData.orEmpty(v);
    }

    public boolean isDone() {
        return done;
    }

    public void setDone(boolean v) {
        this.done = v;
    }

    /** getter 也标注同名列：否则 Jackson 会把 isFallback() 拆成多余的 "fallback" 属性（实测踩过） */
    @JsonProperty("is_fallback")
    public boolean isFallback() {
        return isFallback;
    }

    @JsonProperty("is_fallback")
    public void setIsFallback(boolean v) {
        this.isFallback = v;
    }
}
