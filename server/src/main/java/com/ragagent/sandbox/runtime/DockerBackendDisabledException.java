package com.ragagent.sandbox.runtime;

/**
 * 对照 Go 哨兵 {@code sandbox.ErrDockerBackendDisabled}（internal/sandbox/docker_enabled.go L26）：
 * Docker 沙箱配置在保存/探测/解析时进程未 opt-in 就返回它。
 * 消息逐字节对应 Go 原文（会进 400 响应）。
 */
public class DockerBackendDisabledException extends RuntimeException {
    public DockerBackendDisabledException() {
        super("sandbox: docker backend is disabled; enable it in System Settings "
                + "or set WEKNORA_SANDBOX_DOCKER_ENABLED=true");
    }
}
