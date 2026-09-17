package com.ragagent.wiki.domain;

/**
 * 对照 Go {@code ErrWikiFolderNotEmpty}（repository/wiki_page.go:646）：
 * 尝试原子删除的瞬间，文件夹里仍有活跃页面或子文件夹。
 */
public class WikiFolderNotEmptyException extends WikiException {

    public WikiFolderNotEmptyException() {
        super("wiki folder is not empty");
    }
}
