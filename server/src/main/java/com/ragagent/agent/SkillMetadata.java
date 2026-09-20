package com.ragagent.agent;

/**
 * 技能元数据（对照 Go internal/agent/skills/skill.go L67-71 的 SkillMetadata；
 * 纯逻辑件只消费 Name/Description）。BasePath 随波 4.5/4.6 skills 批展开。
 */
public record SkillMetadata(String name, String description, String basePath) {

    public SkillMetadata {
        name = name == null ? "" : name;
        description = description == null ? "" : description;
        basePath = basePath == null ? "" : basePath;
    }

    /** 只有两项元数据的便捷构造。 */
    public static SkillMetadata of(String name, String description) {
        return new SkillMetadata(name, description, "");
    }
}
