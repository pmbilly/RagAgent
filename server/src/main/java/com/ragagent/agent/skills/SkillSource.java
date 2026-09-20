package com.ragagent.agent.skills;

import java.util.List;

/**
 * skill 的来源（对照 Go internal/agent/skills/source.go，全文移植）。两个实现：
 * 测试用宿主 skill 目录（{@link Loader}），与管理员装进工作区 sandbox 配置快照
 * 镜像的 skill 投影（{@link TenantSkillSource}）。
 *
 * <p>五个方法就是 agent 请求的 Progressive Disclosure 层级：系统提示词的元数据、
 * SKILL.md 正文、单个资源文件、文件清单、脚本运行目录。</p>
 */
public interface SkillSource {

    List<Skill.SkillMetadata> discoverSkills() throws Exception;

    Skill loadSkillInstructions(String name) throws Exception;

    Skill.SkillFile loadSkillFile(String name, String relativePath) throws Exception;

    List<String> listSkillFiles(String name) throws Exception;

    String getSkillBasePath(String name) throws Exception;
}

/**
 * skill 已在 sandbox 镜像内的 source（对照 Go imageSkillSource）。它把两类 source
 * 在执行期分开：宿主 skill 在 WeKnora 机器上、要上传进沙箱；已安装 skill 已在那儿、
 * 原地执行。
 */
interface ImageSkillSource extends SkillSource {

    /** 返回一个脚本在沙箱内的绝对路径（对照 RemoteScriptPath）。 */
    String remoteScriptPath(String name, String relativePath) throws Exception;
}
