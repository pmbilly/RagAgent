package com.ragagent.memory.domain;

/**
 * {@code withSubject} 在 {@code memory_subjects} 里找不到本 scope 的那一行时抛出。
 *
 * <p>对照 Go：{@code withSubject} 里 {@code First(&subject)} 返回
 * {@code gorm.ErrRecordNotFound}，被原样向上抛——**不是** {@code nil, nil}。
 * 所以 Java 侧不能悄悄当成"没有主体"继续，否则会在一行不存在的基础上做写入。
 * 消息用 gorm 的原话 {@code "record not found"}，与 Go 的日志措辞一致。</p>
 *
 * <p>线上基本不可达：所有写路径都以 {@code EnsureSubject} 开头。</p>
 */
public class MemorySubjectMissingException extends RuntimeException {

    public static final String MESSAGE = "record not found";

    public MemorySubjectMissingException() {
        super(MESSAGE);
    }
}
