package com.ragagent.wiki.domain;

/**
 * 同一父目录下已存在同名活跃文件夹。
 */
public class WikiFolderConflictException extends WikiException {

    public WikiFolderConflictException() {
        super("wiki folder name conflict");
    }
}
