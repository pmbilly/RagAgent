package com.ragagent.wiki.domain;

public class WikiFolderNotFoundException extends WikiException {

    public WikiFolderNotFoundException() {
        super("wiki folder not found");
    }
}
