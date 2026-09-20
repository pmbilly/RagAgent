package com.ragagent.agent.tools;

import java.util.List;
import java.util.Map;

/**
 * skill 环境的窄工具面（对照 Go 侧工具持有的 {@code *skills.Manager} 与
 * {@code skills.SkillEnvResolver}，internal/agent/skills/{manager,env_resolver}.go）。
 *
 * <p>skills 包整包属波 4.6；工具层按 Go 的调用形状先立接口，装配由 4.6 的真
 * Manager 适配。解析值按调用方隔离（同一 skill 给不同 Principal 发不同的 key）。</p>
 *
 * <p>SkillEnvResolver 按 skill <b>名</b>而非 id 寻址：进入 manager 的每条路径都是
 * 名字寻址——shell_exec 收到的是模型写的名字。</p>
 */
public interface SkillEnvironment {

    /** SKILL.md 文件名（对照 skills.SkillFileName）。 */
    String SKILL_FILE_NAME = SkillResources.SKILL_FILE_NAME;

    /** 对照 Manager.IsEnabled。 */
    boolean isEnabled();

    /** 已发现 skill 的最小元数据（对照 GetAllMetadata；Level 1，只有名字与描述）。 */
    List<com.ragagent.agent.SkillMetadata> getAllMetadata();

    /** 读取 skill 全文（对照 LoadSkill 的 Level 2：name/description/instructions）。 */
    SkillDocument loadSkill(String skillName) throws Exception;

    /**
     * 会话 sandbox 内该 skill 的目录（对照 SandboxSkillDir）。
     * {@code installed=false} 表示 skill 未安装、只有包资源。
     */
    SkillDir sandboxSkillDir(String skillName);

    /** 列出 skill 的 bundled 文件相对路径（对照 ListSkillFiles）。 */
    List<String> listSkillFiles(String skillName) throws Exception;

    /** 读 skill 的一个 bundled 资源（对照 ReadSkillFile）。 */
    String readSkillFile(String skillName, String relativePath) throws Exception;

    /**
     * 为一次 shell 调用准备 skill 环境（对照 Manager.PrepareShellEnvironment）：
     * 返回替换后的命令与叠加后的 env。失败抛异常（等价 Go 的 error 通道）。
     */
    PreparedShell prepareShellEnvironment(
            String sessionId, String skillName, String command, Map<String, String> env) throws Exception;

    /** 对照 skills.Skill（工具面只用三个字段）。 */
    record SkillDocument(String name, String description, String instructions) {
    }

    /** 对照 SandboxSkillDir 的 (dir string, installed bool)。 */
    record SkillDir(String dir, boolean installed) {
    }

    /** 对照 PrepareShellEnvironment 的 (command string, env map)。 */
    record PreparedShell(String command, Map<String, String> env) {
    }

    /**
     * 一次执行拿到的环境（对照 ResolveEnv 的 (env map, missing []string)）：
     * env 是要注入的值；missing 是管理员与当前调用方都还没填的必填变量名。
     * 空 skillName 只取调用方的 config-wide 变量。
     */
    record SkillEnvResolution(Map<String, String> env, List<String> missing) {
    }

    /** 对照 skills.SkillEnvResolver（服务层实现，值按调用方隔离、绝不持久化）。 */
    interface SkillEnvResolver {
        SkillEnvResolution resolveEnv(String skillName) throws Exception;
    }

    /**
     * 解析值叠加到 env 上且<b>不顶替</b> env 已有的键（对照 skills.ApplyResolvedEnv）。
     * 导出成静态是刻意的：shell_exec 对可选 skill_name 参数做同样的叠加，不能与
     * manager 分叉。这是保留名保护的第二层——模型传的 env 优先于库存值。
     */
    static void applyResolvedEnv(Map<String, String> env, Map<String, String> resolved) {
        if (env == null || resolved == null) {
            return;
        }
        for (Map.Entry<String, String> e : resolved.entrySet()) {
            if (env.containsKey(e.getKey())) {
                continue;
            }
            env.put(e.getKey(), e.getValue());
        }
    }
}
