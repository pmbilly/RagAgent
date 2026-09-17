package com.ragagent.wiki.domain;

/** 对照 Go {@code ErrWikiFolderNotFound}（repository/wiki_page.go:638）。 */
public class WikiFolderNotFoundException extends WikiException {

    public WikiFolderNotFoundException() {
        super("wiki folder not found");
    }
}
