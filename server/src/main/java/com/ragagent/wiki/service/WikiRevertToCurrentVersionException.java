package com.ragagent.wiki.service;

import com.ragagent.wiki.domain.WikiException;

/**
 * 对照 Go {@code ErrWikiRevertToCurrentVersion}
 * （internal/application/service/wiki_page.go L280：
 * {@code errors.New("cannot revert to the current version")}）。
 *
 * <p>回滚目标就是页面当前所在版本——这是调用方的失误（通常是历史列表过期），
 * 不是服务端故障，因此 handler 应当映射成 <b>400</b> 而不是 500。</p>
 */
public class WikiRevertToCurrentVersionException extends WikiException {

    public WikiRevertToCurrentVersionException() {
        super("cannot revert to the current version");
    }
}
