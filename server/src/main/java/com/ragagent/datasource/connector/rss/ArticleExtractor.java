package com.ragagent.datasource.connector.rss;

/**
 * <b>接缝（seam）</b>：文章页正文抽取（readability）。
 *
 * <h2>⚠️ 这是本模块最大的降级点——读这一节再读代码</h2>
 * <p>Go 的实现是 {@code codeberg.org/readeck/go-readability/v2}
 * （{@code readability.FromReader} + {@code article.RenderHTML} + {@code article.Title()}），
 * 它把文章页的导航、广告、页脚剥掉，只留下正文 HTML，再交给
 * {@link HtmlToMarkdown} 转 Markdown。</p>
 * <p><b>Java 侧没有等价物</b>，且本项目不允许为翻译新增依赖。所以这里做成接缝，
 * 默认实现是 {@link UnavailableArticleExtractor}——它<b>永远抛错</b>。
 * 于是 {@code resolveItem} 恒定走 Go 自己那条"全文抓取失败，回落 feed 内容"的分支
 * （{@code connector.go} 的
 * {@code logger.Warnf(ctx, "[RSS] full-text fetch failed for %s (using feed content): %v", …)}）。</p>
 *
 * <h2>降级后果（逐条）</h2>
 * <ol>
 *   <li><b>灌入知识库的是 feed 自带的摘要，不是文章全文</b>。
 *       RSS 的 {@code <description>} 常见只有一两句。检索质量会明显低于 Go 侧部署。</li>
 *   <li><b>标题回落</b>：Go 在 feed 条目没有 {@code <title>} 时会用文章页的
 *       {@code <title>}；Java 侧恒用 {@code "untitled"}（{@code firstNonEmpty(item.Title, "untitled")}）。</li>
 *   <li><b>网络开销没有省</b>：控制流与 Go 完全一致，所以<b>文章页仍然会被 GET 一次</b>、
 *       拿到正文后被丢弃。这是"保持控制流等价"的直接代价——
 *       见 {@link RssClient#extractArticle}。真要省掉这次抓取，得改
 *       {@code resolveItem} 的分支（那就不是逐行等价了）。</li>
 *   <li><b>指纹与 Go 不同</b>：{@code contentFingerprint} 算的是最终 Markdown，
 *       内容不同 → 指纹不同（这在本进程内自洽，但与 Go 写下的游标不通用）。</li>
 * </ol>
 * <p>三块的清单见 {@link HtmlToMarkdown} 与 {@link FeedParser} 的类注释，
 * 以及本模块的翻译报告。</p>
 *
 * <h2>接缝在这里，怎么恢复</h2>
 * <p>{@link RssConnector} 的构造器可注入任意实现；恢复全文抓取只需提供一个
 * "读 HTML → 抽正文 → 回 HTML 字符串 + 标题"的实现，
 * 连接器与 {@code resolveItem} 一行都不用改。返回的 HTML 会被
 * {@link HtmlToMarkdown} 转成 Markdown。</p>
 */
public interface ArticleExtractor {

    /**
     * 从已抓下来的文章页字节里抽出正文。
     *
     * @param body    文章页原始响应体（<b>已经由 {@link RssClient} 抓完并限长</b>）
     * @param pageUrl 文章页 URL（readability 用它解析相对链接）
     * @return 正文 HTML + 页面标题（标题可为 {@code null}/空）
     * @throws ArticleExtractionException 抽不出正文。<b>这会影响日志文案，但不影响控制流</b>
     *         ——调用方 {@code resolveItem} 一律回落到 feed 内容。
     */
    ExtractedArticle extract(byte[] body, String pageUrl);

    /**
     * 对照 {@code (contentHTML, article.Title())}。
     *
     * @param contentHtml 正文 HTML（会经 {@link HtmlToMarkdown} 转成 Markdown）
     * @param title       页面 {@code <title>}；空表示没抽到
     */
    record ExtractedArticle(String contentHtml, String title) {
    }
}
