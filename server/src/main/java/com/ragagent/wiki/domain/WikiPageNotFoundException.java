package com.ragagent.wiki.domain;

/** 对照 Go {@code ErrWikiPageNotFound}（repository/wiki_page.go:18），message 逐字一致。 */
public class WikiPageNotFoundException extends WikiException {

    public WikiPageNotFoundException() {
        super("wiki page not found");
    }
}
