package com.ragagent.agent.tools;

/**
 * 特权版执行面（对照 Go {@code SandboxInstallCommandExecutor}，
 * internal/agent/tools/shell_exec.go:185-192）。由 *sandbox.SessionBoundManager 经
 * sandbox.SessionInstallShellExecutor 满足。独立命名的类型是刻意的：特权只能被
 * 显式交出去，任何只实现 ExecShellCommand 的东西都冒充不了它。
 */
public interface SandboxInstallCommandExecutor {

    SandboxExecuteResult execShellCommandWithOptions(
            String sessionId,
            String command,
            ShellExecOptions opts) throws Exception;
}
