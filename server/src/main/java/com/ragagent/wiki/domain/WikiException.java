package com.ragagent.wiki.domain;

/**
 * wiki 领域异常基类（对照 Go internal/application/repository/wiki_page.go 里的
 * 五个 sentinel error：ErrWikiPageNotFound / ErrWikiPageConflict /
 * ErrWikiFolderNotFound / ErrWikiFolderConflict / ErrWikiFolderNotEmpty）。
 *
 * <p>Go 用 {@code var ErrX = errors.New("...")} + {@code errors.Is} 做判别；
 * Java 侧改为异常类型判别。各子类的 message 与 Go 的 error 文本<b>逐字相同</b>，
 * 便于 handler 层对照。</p>
 */
public class WikiException extends RuntimeException {

    public WikiException(String message) {
        super(message);
    }

    public WikiException(String message, Throwable cause) {
        super(message, cause);
    }
}
