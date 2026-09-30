package com.ragagent.wiki.domain;

/**
 * 尝试原子删除的瞬间，文件夹里仍有活跃页面或子文件夹。
 */
public class WikiFolderNotEmptyException extends WikiException {

    public WikiFolderNotEmptyException() {
        super("wiki folder is not empty");
    }
}
