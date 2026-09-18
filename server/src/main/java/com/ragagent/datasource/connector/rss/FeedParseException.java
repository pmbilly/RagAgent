package com.ragagent.datasource.connector.rss;

/**
 * feed 解析失败（对照 Go 里 {@code gofeed.Parser.Parse} 返回的那个 {@code error}）。
 *
 * <p>Go 侧它只是一个裸 {@code error}，会被 {@code fmt.Errorf("parse feed %s: %w", url, err)}
 * 或 {@code "parse failed: " + err.Error()} 拼进文案。Java 侧用一个<b>独立的运行时异常</b>
 * 表达，而不是复用 {@link com.ragagent.datasource.ConnectorException}——
 * 后者带"这是连接器层错误"的语义、会被 service 层按类型分流，
 * 而解析失败在 Go 里只是一个字符串。这里刻意不继承它，免得调用方误判类型。</p>
 *
 * <h2>与 Go 的文案差异</h2>
 * <p>{@code getMessage()} 是各实现自己写的。对"根元素不是 rss/rdf/feed"这一种，
 * Java 侧照抄 Go 的原文 {@code "Failed to detect feed type"}；
 * 其余（XML 语法错误、编码错误、JSON Feed）两边是各自的解析库原文，
 * <b>文案不同但都是"parse feed/parse failed + 细节"的形状</b>。</p>
 */
public class FeedParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 对照 gofeed 的 {@code errors.New("Failed to detect feed type")}。 */
    public static final String FAILED_TO_DETECT = "Failed to detect feed type";

    public FeedParseException(String message) {
        super(message);
    }

    public FeedParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
