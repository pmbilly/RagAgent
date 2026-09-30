package com.ragagent.wiki.domain;

/**
 * 乐观锁冲突——调用方持有的 version 与库中当前 version 不一致。
 */
public class WikiPageConflictException extends WikiException {

    public WikiPageConflictException() {
        super("wiki page version conflict");
    }
}
