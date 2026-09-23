package com.ragagent.sandbox.runtime;

/**
 * 对照 Go {@code sandbox.NewDisabledManager}（internal/sandbox/manager.go L276-281）
 * 与 {@code disabledSandbox}（L202-216）：拒绝一切执行请求的 no-op 管理器。
 *
 * <p>两个消费面：①进程默认管理器（Go container.go {@code newSandboxManager}
 * 恒返回 Disabled——"Every executable backend now comes from a named workspace
 * configuration resolved at request time"）；②{@code Resolve} 对空 configID /
 * 全局默认 / disabled 类型的回落。执行错误对照 {@code ErrSandboxDisabled}
 * （sandbox.go L100）："sandbox is disabled"。</p>
 */
public final class DisabledSandboxManager implements SandboxManager {

    @Override
    public SandboxManager.ExecuteResult execute(SandboxManager.ExecuteConfig config) {
        throw new SandboxException(SandboxException.Kind.SANDBOX_DISABLED, "sandbox is disabled");
    }

    @Override
    public void cleanup() {
        // 对照 disabledSandbox.Cleanup：no-op
    }

    @Override
    public Sandbox getSandbox() {
        return new Sandbox() {
            @Override
            public SandboxManager.ExecuteResult execute(SandboxManager.ExecuteConfig config) {
                throw new SandboxException(
                        SandboxException.Kind.SANDBOX_DISABLED, "sandbox is disabled");
            }

            @Override
            public void cleanup() {
            }

            @Override
            public String type() {
                return SandboxTypes.TYPE_DISABLED;
            }

            @Override
            public boolean isAvailable() {
                return false;
            }
        };
    }

    @Override
    public String getType() {
        return SandboxTypes.TYPE_DISABLED;
    }
}
