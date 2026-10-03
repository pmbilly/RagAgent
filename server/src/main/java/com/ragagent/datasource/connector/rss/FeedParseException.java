package com.ragagent.datasource.connector.rss;

/**
 * feed 解析失败。
 *
 * <p>用一个<b>独立的运行时异常</b>
 * 表达，而不是复用 {@link com.ragagent.datasource.ConnectorException}——
 * 后者带"这是连接器层错误"的语义、会被 service 层按类型分流，
 * 而解析失败只是内容问题。这里刻意不继承它，免得调用方误判类型。</p>
 *
 * <h2>文案约定</h2>
 * <p>{@code getMessage()} 是各实现自己写的。对"根元素不是 rss/rdf/feed"这一种，
 * 固定用 {@code "Failed to detect feed type"}；
 * 其余（XML 语法错误、编码错误、JSON Feed）是解析库原文，
 * 都是"parse feed/parse failed + 细节"的形状。</p>
 */
public class FeedParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** "根元素不是 rss/rdf/feed" 的固定文案。 */
    public static final String FAILED_TO_DETECT = "Failed to detect feed type";

    public FeedParseException(String message) {
        super(message);
    }

    public FeedParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
