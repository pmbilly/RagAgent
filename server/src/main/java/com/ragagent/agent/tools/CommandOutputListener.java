package com.ragagent.agent.tools;

/**
 * shell 命令输出回调（对照 Go sandbox.WithCommandOutput 挂进 ctx 的
 * {@code func(stream string, chunk []byte)}）。stream 取值 "stdout"/"stderr"。
 *
 * <p>Go 把回调塞进 context（WithCommandOutput），Java 无 ctx——改为
 * {@link SandboxCommandExecutor} 的显式参数，由执行器实现方负责逐块回调
 * （shell_exec 用它喂 {@link ShellCommandOutput} 的预览 emit）。</p>
 */
@FunctionalInterface
public interface CommandOutputListener {

    void onOutput(String stream, byte[] chunk);

    /** no-op 句柄（对照 Go 的"ctx 里没有回调"分支）。 */
    static CommandOutputListener none() {
        return (stream, chunk) -> {
        };
    }
}
