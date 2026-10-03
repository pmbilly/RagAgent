package com.ragagent.memory.domain;

/**
 * {@code withSubject} 在 {@code memory_subjects} 里找不到本 scope 的那一行时抛出。
 *
 * <p>查不到时**抛异常**而不是悄悄当成"没有主体"继续——否则会在一行不存在的基础上做写入。
 * 消息是 {@code "record not found"}。</p>
 *
 * <p>线上基本不可达：所有写路径都以 {@code ensureSubject} 开头。</p>
 */
public class MemorySubjectMissingException extends RuntimeException {

    public static final String MESSAGE = "record not found";

    public MemorySubjectMissingException() {
        super(MESSAGE);
    }
}
