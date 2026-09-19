package com.ragagent.sandbox.runtime;

/**
 * 对照 Go 哨兵 {@code sandbox.ErrSandboxConfigIncomplete}（internal/sandbox/config_required.go L29）：
 * 具名配置因必填字段为空而无法构建可用客户端。调用方（controller 的 sentinel→400 分类）
 * 映射为 400。完整消息形如
 * {@code sandbox: config is missing required fields: <provider> backend requires <fields>}。
 */
public class SandboxConfigIncompleteException extends RuntimeException {
    public SandboxConfigIncompleteException(String message) {
        super(message);
    }
}
