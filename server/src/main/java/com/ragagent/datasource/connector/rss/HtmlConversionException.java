package com.ragagent.datasource.connector.rss;

/**
 * HTML → Markdown 转换失败。
 *
 * <p>抛出它的效果：{@code htmlToMarkdown} 回落到
 * 去空白后的 HTML 原文。</p>
 */
public class HtmlConversionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public HtmlConversionException(String message) {
        super(message);
    }

    public HtmlConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
