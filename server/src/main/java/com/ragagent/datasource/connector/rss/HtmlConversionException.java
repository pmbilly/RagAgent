package com.ragagent.datasource.connector.rss;

/**
 * HTML → Markdown 转换失败（对照 Go {@code htmltomd.ConvertString} 返回的 {@code err}）。
 *
 * <p>抛出它的效果与 Go 完全一致：{@code htmlToMarkdown} 回落到
 * {@code strings.TrimSpace(html)} 原文。</p>
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
