package com.ragagent.sandbox.runtime;

/**
 * 对照 Go 哨兵 {@code sandbox.ErrUnsupportedSandboxType}（internal/sandbox/tenant_config.go L231）。
 * 它是哨兵，调用方按"坏输入"分类而不匹配消息文本——Java 侧用异常类 + instanceof 表达。
 * 消息由构造处拼出：{@code sandbox: unsupported sandbox type "<raw>"}。
 */
public class UnsupportedSandboxTypeException extends RuntimeException {
    public UnsupportedSandboxTypeException(String message) {
        super(message);
    }
}
