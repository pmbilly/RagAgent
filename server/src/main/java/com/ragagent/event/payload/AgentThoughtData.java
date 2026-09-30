package com.ragagent.event.payload;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Agent 思考流式数据（对照 Go {@code event.AgentThoughtData}，internal/event/event_data.go:155-159）。
 * emit 点：think.go:287（主思考流）/ think.go:385（thinking tool 流），见包注释 emit 表 #1/#5。
 *
 * <p>三字段全无 omitempty：零值恒输出 {@code {"content":"","iteration":0,"done":false}}。
 * 同一 id 的分片在客户端重组（思考流整段共用一个 generateEventID("thinking")）。</p>
 */
@JsonPropertyOrder({"content", "iteration", "done"})
public class AgentThoughtData {

    @JsonProperty("content")
    private String content = "";

    @JsonProperty("iteration")
    private int iteration;

    @JsonProperty("done")
    private boolean done;

    public AgentThoughtData() {
    }

    public AgentThoughtData(String content, int iteration, boolean done) {
        this.content = QueryData.orEmpty(content);
        this.iteration = iteration;
        this.done = done;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String v) {
        this.content = QueryData.orEmpty(v);
    }

    public int getIteration() {
        return iteration;
    }

    public void setIteration(int v) {
        this.iteration = v;
    }

    public boolean isDone() {
        return done;
    }

    public void setDone(boolean v) {
        this.done = v;
    }
}
