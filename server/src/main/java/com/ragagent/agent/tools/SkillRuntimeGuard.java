package com.ragagent.agent.tools;

/**
 * skill 运行时失败识别与恢复指引（对照 Go {@code skill_runtime_guard.go}，文案逐字移植）。
 *
 * <p>skill 的依赖本应在安装完成时齐备。缺了的时候，chat 时模型拿到的报错没有方向：
 * uv 建的 venv 报 "No module named pip"，或者对 .venv 路径的一个光秃权限错误，都看不出
 * 哪条恢复路是对的。下面的指引在事后补上方向。</p>
 */
public final class SkillRuntimeGuard {

    /**
     * 这些命令带着 skill_name 跑，shell 环境会供应真实的镜像或暂存目录。
     * work_dir 保持 /workspace 缺省。
     */
    public static final String SKILL_PYTHON_PACKAGE_INSTALL_COMMAND =
            "uv pip install --python \"${WEKNORA_SKILL_DIR:?}/.venv/bin/python\" <package>";
    public static final String SKILL_PYTHON_PACKAGE_FALLBACK_COMMAND =
            "\"${WEKNORA_SKILL_DIR:?}/.venv/bin/python\" -m ensurepip --upgrade && "
                    + "\"${WEKNORA_SKILL_DIR:?}/.venv/bin/python\" -m pip install <package>";
    public static final String SKILL_PYTHON_VENV_CREATE_COMMAND =
            "python3 -m venv --without-pip \"${WEKNORA_SKILL_DIR:?}/.venv\"";
    public static final String SKILL_NODE_PACKAGE_INSTALL_COMMAND =
            "npm --prefix \"${WEKNORA_SKILL_DIR:?}\" install <package>";

    private SkillRuntimeGuard() {
    }

    /** 对照 missingSkillPackageGuidance（skillName 为空时用 <skill> 占位）。 */
    public static String missingSkillPackageGuidance(String skillName) {
        String skillArg = "skill_name=<skill>";
        if (skillName != null && !skillName.isEmpty()) {
            skillArg = "skill_name=" + goQuote(skillName);
        }
        return "Install missing packages with shell_exec(" + skillArg + ", command=...), leaving work_dir at /workspace. "
                + "That named call supplies $WEKNORA_SKILL_DIR, the actual installed or staged skill directory. "
                + "For Python, use `" + SKILL_PYTHON_PACKAGE_INSTALL_COMMAND + "`; uv does not need pip in the virtualenv. "
                + "If .venv is absent (as with staged host resources), first run `" + SKILL_PYTHON_VENV_CREATE_COMMAND + "`. "
                + "If uv is unavailable in a custom image, use `" + SKILL_PYTHON_PACKAGE_FALLBACK_COMMAND + "`. "
                + "For Node, use `" + SKILL_NODE_PACKAGE_INSTALL_COMMAND + "`. "
                + "Then rerun the original command with the same skill_name. "
                + "These changes live and die with this session; reinstall the skill if every session needs the package.";
    }

    /** 对照 isSkillVenvInstallFailure。 */
    public static boolean isSkillVenvInstallFailure(String stderr) {
        if (stderr == null || stderr.isEmpty()) {
            return false;
        }
        String lower = stderr.toLowerCase(java.util.Locale.ROOT);
        if (stderr.contains("No module named pip") || stderr.contains("No module named 'pip'")) {
            return true;
        }
        return lower.contains(".venv")
                && (isReadOnlyFilesystemFailure(lower) || isPermissionFailure(lower));
    }

    /** 对照 isReadOnlyFilesystemFailure。 */
    public static boolean isReadOnlyFilesystemFailure(String stderr) {
        String lower = stderr == null ? "" : stderr.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("read-only file system") || lower.contains("erofs")
                || lower.contains("read-only filesystem");
    }

    /** 对照 isPermissionFailure。 */
    public static boolean isPermissionFailure(String stderr) {
        String lower = stderr == null ? "" : stderr.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("permission denied") || lower.contains("operation not permitted")
                || lower.contains("eperm");
    }

    /** 对照 skillVenvFailureGuidance。 */
    public static String skillVenvFailureGuidance(String skillName, String stderr) {
        if (isReadOnlyFilesystemFailure(stderr)) {
            return "The skill virtualenv is on a read-only filesystem. Root, uv and pip cannot write through "
                    + "a read-only mount. Configure a writable skill environment before installing packages.";
        }
        if (isPermissionFailure(stderr)) {
            return "The skill virtualenv denied access. Check its permissions and mount restrictions first; "
                    + "changing package managers does not grant access. Once the environment is writable: "
                    + missingSkillPackageGuidance(skillName);
        }
        return missingSkillPackageGuidance(skillName);
    }

    /** Go strconv.Quote（skillName 都是普通标识符，双引号包裹 + 双反斜杠/引号转义足够）。 */
    static String goQuote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
