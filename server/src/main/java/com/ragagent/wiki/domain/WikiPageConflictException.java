package com.ragagent.wiki.domain;

/**
 * 对照 Go {@code ErrWikiPageConflict}（repository/wiki_page.go:21）：
 * 乐观锁冲突——调用方持有的 version 与库中当前 version 不一致。
 */
public class WikiPageConflictException extends WikiException {

    public WikiPageConflictException() {
        super("wiki page version conflict");
    }
}
