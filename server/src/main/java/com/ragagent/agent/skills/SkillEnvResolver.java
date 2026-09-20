package com.ragagent.agent.skills;

import java.util.List;
import java.util.Map;

/**
 * 产出一次执行拿到的环境（对照 Go internal/agent/skills/env_resolver.go，全文移植）。
 * 与 SkillSource 分开是因为值按调用方隔离：同一 skill、同一镜像，给不同 Principal
 * 发不同的 key。实现住在 service 层，它能到 repository。
 *
 * <p>按 skill <b>名</b>而非 id 寻址：进入 manager 的每条路径都是名字寻址——
 * shell_exec 收到的是模型写的名字，行 id 是 installed-skill source 的实现细节。</p>
 */
public interface SkillEnvResolver {

    /** 一次解析的结果：env 是要注入的值；missing 是谁都没填的必填变量名。 */
    record Resolution(Map<String, String> env, List<String> missing) {
    }

    /**
     * 返回要注入的值与任何管理员和当前调用方都没填的必填变量名（对照 ResolveEnv）。
     * 空 skillName 只取调用方的 config-wide 变量。调用方身份在服务层上下文里取，
     * 绝不从参数来。
     */
    Resolution resolveEnv(String skillName) throws Exception;

    /**
     * 执行被拒：必填变量在两层都没有值（对照 MissingSkillEnvError）。类型化是为了
     * 让 agent 循环转述一句人能行动的话，而不是脚本本会产出的 KeyError 或 401。
     */
    final class MissingSkillEnvError extends RuntimeException {
        private final String skillName;
        private final List<String> names;

        public MissingSkillEnvError(String skillName, List<String> names) {
            super(format(skillName, names));
            this.skillName = skillName;
            this.names = names;
        }

        static String format(String skillName, List<String> names) {
            // 英文，与全库其他错误一致：agent 把它转述给用户并译成对方语言
            return "skill " + TenantSkillSource.GoQuote.quote(skillName)
                    + " needs the environment variable(s) " + String.join(", ", names)
                    + ", which nobody has set yet. "
                    + "Ask the user for them, then run the skill through shell_exec with "
                    + "skill_name=" + TenantSkillSource.GoQuote.quote(skillName)
                    + " and the values in env — they are stored for that user "
                    + "afterwards. They can also be set under Settings → Sandbox secrets.";
        }

        public String getSkillName() {
            return skillName;
        }

        public List<String> getNames() {
            return names;
        }
    }

    /**
     * 把 resolved 叠加到 env 上且<b>不顶替</b> env 已有的键（对照 ApplyResolvedEnv）。
     * shell_exec 对它的可选 skill_name 参数做同样叠加，不能与 manager 分叉。
     * 这是保留名保护的第二层：写入期黑名单是第一层，但黑名单存在前写入的值仍在库里，
     * 让它落到 WEKNORA_SKILL_OUTPUT_DIR 会把本轮产物悄悄改道到无人 drain 的目录。
     * 跳过已有键使这在任何存储值下都不可能。
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

    /** env 名：skill 产物的输出目录（manager.go 常量，env_resolver 相关引用）。 */
    String ARTIFACT_OUTPUT_ENV_VAR = "WEKNORA_SKILL_OUTPUT_DIR";
    /** env 名：本轮用户上传文件的还原目录。 */
    String SESSION_INPUT_ENV_VAR = "WEKNORA_SESSION_INPUT_DIR";
    /** env 名：根产物输出目录（/workspace/output），脚本可自发现历史产物。 */
    String ARTIFACT_HISTORY_ENV_VAR = "WEKNORA_SKILL_HISTORY_ROOT";
    /** env 名：脚本在沙箱镜像内的自己的目录。 */
    String SKILL_DIR_ENV_VAR = "WEKNORA_SKILL_DIR";
    /** PYTHONPATH 恒不注入但保留在黑名单（库存值会遮蔽 venv 包）。 */
    String PYTHON_PATH_ENV_VAR = "PYTHONPATH";
    /** NODE_PATH 携带 skill 自己的 node_modules。 */
    String NODE_PATH_ENV_VAR = "NODE_PATH";

    /**
     * 把 skill 自己的 node_modules 放到 NODE_PATH 上，在调用方已有值之后
     * （对照 applySkillNodePath）。Python 刻意没有等价物：依赖经 skill 自己的
     * virtualenv 解释器可达（shell 包装把它放到 PATH 首位，自带 site-packages）。
     */
    static void applySkillNodePath(Map<String, String> env, String skillDir) {
        if (env == null || skillDir == null || skillDir.isEmpty()) {
            return;
        }
        appendPathEnv(env, NODE_PATH_ENV_VAR, com.ragagent.agent.tools.SandboxPaths.join(skillDir, "node_modules"));
    }

    private static void appendPathEnv(Map<String, String> env, String key, String dir) {
        String existing = env.get(key);
        if (existing != null && !existing.strip().isEmpty()) {
            env.put(key, existing + ":" + dir);
            return;
        }
        env.put(key, dir);
    }
}
