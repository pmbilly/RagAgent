package com.ragagent.agent.tools;

import java.time.Duration;
import java.util.Map;

/**
 * 每次 shell 执行的调节旋钮（对照 Go {@code sandbox.ShellExecOptions}，
 * internal/sandbox/session_manager.go:687-706）。
 *
 * <p>install-only 的两个旗标选择安装器的工作目录 allowlist 与引导脚本。普通调用与
 * install 调用当前都以 root 执行。</p>
 */
public final class ShellExecOptions {

    /** 输出回调（对照 OnOutput；null 等价 Go 的 nil）。 */
    private final CommandOutputListener onOutput;
    private final String workDir;
    private final Duration timeout;
    private final Map<String, String> env;
    /**
     * 允许 installer 调用在 skills image root 内工作（对照 AllowSkillsRoot）。
     * 绝不能从模型可见的 shell_exec 设置——work_dir allowlist 是纯词法检查。
     */
    private final boolean allowSkillsRoot;
    /** 强制 root 并选择维护引导（对照 AsRoot）。 */
    private final boolean asRoot;

    public ShellExecOptions(CommandOutputListener onOutput, String workDir, Duration timeout,
            Map<String, String> env, boolean allowSkillsRoot, boolean asRoot) {
        this.onOutput = onOutput;
        this.workDir = workDir == null ? "" : workDir;
        this.timeout = timeout == null ? Duration.ZERO : timeout;
        this.env = env;
        this.allowSkillsRoot = allowSkillsRoot;
        this.asRoot = asRoot;
    }

    public static ShellExecOptions of(String workDir, Duration timeout, Map<String, String> env,
            CommandOutputListener output) {
        return new ShellExecOptions(output, workDir, timeout, env, false, false);
    }

    public CommandOutputListener onOutput() { return onOutput; }
    public String workDir() { return workDir; }
    public Duration timeout() { return timeout; }
    public Map<String, String> env() { return env; }
    public boolean allowSkillsRoot() { return allowSkillsRoot; }
    public boolean asRoot() { return asRoot; }
}
