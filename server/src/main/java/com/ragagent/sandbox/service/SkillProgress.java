package com.ragagent.sandbox.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 对照 Go {@code service.SkillProgress}（internal/application/service/
 * tenant_skill_progress.go L19-25）：安装/移除的瞬态进度。
 *
 * <p>刻意不持久化：durable 状态是 {@code tenant_skills.status}，一个活得比产生它的
 * 任务更久的百分比比没有百分比更糟（Go 注释原文）。经 Redis 键
 * {@code weknora-skill-install:<tenant>:<config>:<skill>}（TTL 30 分钟）存 + pub。</p>
 *
 * <p>JSON 键序 = Go struct 声明序（percent, stage, log, status）；log/status 带
 * omitempty。这批 JSON 是 Go/Java 两个实现共读的 Redis 契约（阶段 5 教训：字节级
 * 对齐不是洁癖）。</p>
 */
@JsonPropertyOrder({"percent", "stage", "log", "status"})
public final class SkillProgress {

    @JsonProperty("percent")
    public final int percent;
    @JsonProperty("stage")
    public final String stage;
    @JsonProperty("log")
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    public final String log;
    @JsonProperty("status")
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    public final String status;

    public SkillProgress(int percent, String stage, String log, String status) {
        this.percent = percent;
        this.stage = stage == null ? "" : stage;
        this.log = log == null ? "" : log;
        this.status = status == null ? "" : status;
    }

    /** 对照 Go 零值 {@code SkillProgress{}}。 */
    public static SkillProgress empty() {
        return new SkillProgress(0, "", "", "");
    }

    @JsonIgnore
    public boolean isEmpty() {
        return percent == 0 && stage.isEmpty();
    }
}
