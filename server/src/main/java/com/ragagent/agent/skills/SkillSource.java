package com.ragagent.agent.skills;

import java.util.List;

/**
 * skill 的来源。指令型裁剪后唯一生产
 * 实现是 {@link Loader}（宿主 skill 目录扫描）。
 *
 * <p>方法组就是 agent 请求的 Progressive Disclosure 层级：系统提示词的元数据、
 * SKILL.md 正文、单个资源文件、文件清单、原始基路径。</p>
 */
public interface SkillSource {

    List<Skill.SkillMetadata> discoverSkills() throws Exception;

    Skill loadSkillInstructions(String name) throws Exception;

    Skill.SkillFile loadSkillFile(String name, String relativePath) throws Exception;

    List<String> listSkillFiles(String name) throws Exception;

    String getSkillBasePath(String name) throws Exception;
}
