package com.ragagent.sandbox.domain;

/**
 * 对照 Go {@code types} 的 skill 生命周期常量（internal/types/tenant_skill.go L11-40）。
 */
public final class SkillStatus {

    /** 对照 SkillStatusInstalling / Ready / Failed / Removing：removing 单列，让 reaper 能区分两种"没跑完"。 */
    public static final String INSTALLING = "installing";
    public static final String READY = "ready";
    public static final String FAILED = "failed";
    public static final String REMOVING = "removing";

    private SkillStatus() {
    }
}
