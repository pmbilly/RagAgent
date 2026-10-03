package com.ragagent.datasource.connector.rss;

/**
 * 正文抽取失败（readability 层的几种失败：解析不出正文、没有可读内容、渲染失败）。
 *
 * <p>它在 {@code resolveItem} 里<b>只影响一行日志</b>——异常被吞掉、改用 feed 内容。
 * 之所以单列一个类型，是为了让"抓取失败"（{@link com.ragagent.datasource.ConnectorException}）
 * 与"抽取失败"在接缝上区分开：前者说明网络/SSRF 层该重试，后者说明是内容问题。</p>
 */
public class ArticleExtractionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ArticleExtractionException(String message) {
        super(message);
    }

    public ArticleExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
