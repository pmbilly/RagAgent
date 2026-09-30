package com.ragagent.wiki.domain;

/**
 * wiki 领域异常基类。
 *
 * <p>用异常类型判别（而非解析错误文本）。各子类的 message 保持固定字面量，
 * 便于 handler 层与测试按类型和文本双口径断言。</p>
 */
public class WikiException extends RuntimeException {

    public WikiException(String message) {
        super(message);
    }

    public WikiException(String message, Throwable cause) {
        super(message, cause);
    }
}
