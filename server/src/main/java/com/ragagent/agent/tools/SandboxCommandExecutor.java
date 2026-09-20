package com.ragagent.agent.tools;

import java.time.Duration;
import java.util.Map;

/**
 * 会话感知 sandbox manager 的 shell 执行切片（对照 Go {@code SandboxCommandExecutor}，
 * internal/agent/tools/shell_exec.go:56-65）。生产实现是 *sandbox.SessionBoundManager；
 * shell_exec 绝不在 WeKnora 主机上跑命令。
 *
 * <p><b>Go ctx → 显式参数</b>：Go 的 ctx 里挂了命令输出回调（WithCommandOutput），
 * Java 改为显式 {@code output} 参数。</p>
 */
public interface SandboxCommandExecutor {

    /**
     * 在会话 sandbox 内执行一条 shell 命令（对照 ExecShellCommand）。
     *
     * @param output 逐块输出回调（可为 {@link CommandOutputListener#none()}）
     * @throws Exception 传输层失败（sandbox 不可达等）——等价 Go 的 error 返回通道
     */
    SandboxExecuteResult execShellCommand(
            String sessionId,
            String command,
            String workDir,
            Duration timeout,
            Map<String, String> env,
            CommandOutputListener output) throws Exception;
}
