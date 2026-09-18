package com.ragagent.datasource.connector.rss;

/**
 * <b>接缝（seam）</b>：HTML → Markdown。
 *
 * <h2>Go 侧是什么</h2>
 * <p>{@code github.com/JohannesKaufmann/html-to-markdown/v2} 的
 * {@code htmltomd.ConvertString(html)}——一个完整的 HTML 解析 + CommonMark 渲染器，
 * 支持表格、嵌套列表、图片、代码块、脚注、GFM 扩展等。</p>
 *
 * <h2>Java 侧：{@link JdkHtmlToMarkdown}（有界实现）</h2>
 * <p>没有等价依赖，所以用 JDK 手写一个<b>有界的</b>转换器，覆盖
 * {@code <p> <br> <h1..h6> <strong>/<b> <em>/<i> <a> <ul>/<ol>/<li> <code>/<pre>
 * <blockquote> <hr> <img>}，并做实体解码、剥掉 {@code <script>/<style>}。
 * 覆盖不到的标签与边角在 {@link JdkHtmlToMarkdown} 的类注释里逐条列出。</p>
 *
 * <h2>失败必须回落，不能吞内容</h2>
 * <p>调用点照抄 Go 的 {@code htmlToMarkdown}：转换抛错<b>或</b>结果 TrimSpace 后为空，
 * 就返回 {@code strings.TrimSpace(html)} 原文——绝不静默丢内容。
 * 所以实现"抛异常"是合法的失败表达，不是错误处理不当。</p>
 */
public interface HtmlToMarkdown {

    /**
     * 把一段 HTML 转成 Markdown。
     *
     * @param html 已经是"解码后"的 HTML（feed/文章页给的原文）
     * @return Markdown 文本；<b>允许返回 {@code null} 或空串</b>，调用方会回落到原文
     * @throws HtmlConversionException 无法转换
     */
    String convert(String html);
}
