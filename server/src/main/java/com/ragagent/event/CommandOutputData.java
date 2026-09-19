package com.ragagent.event;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.web.GoTimeSerializer;

/**
 * 有界命令输出（对照 Go {@code event.CommandOutputData}，internal/event/event_data.go:311-317）。
 * emit 点：agent/tools/shell_command_output.go:30，见包注释 emit 表 #22。
 *
 * <p>这是<b>累计尾量</b>（cumulative tail）而非增量：重连/回放不需要字节偏移，
 * 错过一次更新也不会弄坏显示的日志。event id 每次全新（uuid.NewString()），
 * 因为每帧都是完整状态、无需客户端重组。</p>
 *
 * <p>实录锚点：五字段全无 omitempty；{@code started_at} 是 Go 零值 {@code time.Time}
 * 时输出 {@code "0001-01-01T00:00:00Z"}、有值时 RFC3339Nano
 * （{@code "2026-09-18T10:30:00.123456789Z"}）——由 {@link GoTimeSerializer} 复刻
 * （字段必须持有零值而非 null，否则 Jackson 走 nullSerializer 丢掉 year-1 字面量）。</p>
 */
@JsonPropertyOrder({"tool_call_id", "command", "started_at", "output", "done"})
public class CommandOutputData {

    @JsonProperty("tool_call_id")
    private String toolCallId = "";

    @JsonProperty("command")
    private String command = "";

    /** 无 omitempty；零值输出 year-1 字面量（实录锚点） */
    @JsonProperty("started_at")
    @JsonSerialize(using = GoTimeSerializer.class)
    private OffsetDateTime startedAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

    @JsonProperty("output")
    private String output = "";

    /** 无 omitempty：false 恒输出 */
    @JsonProperty("done")
    private boolean done;

    public CommandOutputData() {
    }

    public CommandOutputData(String toolCallId, String command, OffsetDateTime startedAt,
                             String output, boolean done) {
        this.toolCallId = QueryData.orEmpty(toolCallId);
        this.command = QueryData.orEmpty(command);
        this.startedAt = startedAt == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : startedAt;
        this.output = QueryData.orEmpty(output);
        this.done = done;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String v) {
        this.toolCallId = QueryData.orEmpty(v);
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String v) {
        this.command = QueryData.orEmpty(v);
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(OffsetDateTime v) {
        this.startedAt = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String v) {
        this.output = QueryData.orEmpty(v);
    }

    public boolean isDone() {
        return done;
    }

    public void setDone(boolean v) {
        this.done = v;
    }
}
