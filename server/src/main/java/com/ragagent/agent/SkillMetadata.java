package com.ragagent.agent;

/**
 * 技能元数据（纯逻辑件，当前只消费 name/description；basePath 预留未消费）。
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
