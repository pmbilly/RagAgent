package com.ragagent.datasource.connector.rss;

/**
 * <b>接缝（seam）</b>：文章页正文抽取（readability）。
 *
 * <h2>⚠️ 这是本模块最大的降级点——读这一节再读代码</h2>
 * <p>目标语义是 {@code codeberg.org/readeck/go-readability/v2}
 * （正文抽取 + 渲染 + 标题）：
 * 把文章页的导航、广告、页脚剥掉，只留下正文 HTML，再交给
 * {@link HtmlToMarkdown} 转 Markdown。</p>
 * <p><b>当前构建没有等价实现</b>。所以这里做成接缝，
 * 默认实现是 {@link UnavailableArticleExtractor}——它<b>永远抛错</b>。
 * {@code resolveItem} 检测到抽取器不可用（{@code fullTextAvailable}）
 * 就<b>直接跳过文章页请求</b>、以 feed 内容定型：抓回的字节必被丢弃，白付一次外网调用。
 * 注入可用实现后
 * 行为自动回到"全文优先，失败回落 feed 内容"的完整语义。</p>
 *
 * <h2>降级后果（逐条）</h2>
 * <ol>
 *   <li><b>灌入知识库的是 feed 自带的摘要，不是文章全文</b>。
 *       RSS 的 {@code <description>} 常见只有一两句。检索质量会明显低于全文部署。</li>
 *   <li><b>标题回落</b>：feed 条目没有 {@code <title>} 时本可用文章页的
 *       {@code <title>}；降级时恒用 {@code "untitled"}（{@code firstNonEmpty(item.title(), "untitled")}）。</li>
 *   <li><b>网络开销反而省了</b>：抽取器不可用时 {@code resolveItem}
 *       直接跳过文章页请求（{@code fullTextAvailable} 判定）——
 *       见 {@link RssConnector} 的 {@code resolveItem}。
 *       注入可用抽取器后恢复完整语义（含鉴权头不泄漏的
 *       {@link RssClient#extractArticle} 契约）。</li>
 *   <li><b>指纹依赖内容</b>：{@code contentFingerprint} 算的是最终 Markdown，
 *       内容不同 → 指纹不同（换抽取实现会使既有游标指纹失配，第一次增量会重灌一轮）。</li>
 * </ol>
 * <p>三块的清单见 {@link HtmlToMarkdown} 与 {@link FeedParser} 的类注释。</p>
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
     *         ——调用方 {@code resolveItem} 一律回落到 feed 内容（默认实现不可用时
     *         resolveItem 直接跳过本次调用，见类注释）。
     */
    ExtractedArticle extract(byte[] body, String pageUrl);

    /**
     * 抽取结果。
     *
     * @param contentHtml 正文 HTML（会经 {@link HtmlToMarkdown} 转成 Markdown）
     * @param title       页面 {@code <title>}；空表示没抽到
     */
    record ExtractedArticle(String contentHtml, String title) {
    }
}
