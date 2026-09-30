package com.ragagent.wiki.domain;

public class WikiPageNotFoundException extends WikiException {

    public WikiPageNotFoundException() {
        super("wiki page not found");
    }
}
