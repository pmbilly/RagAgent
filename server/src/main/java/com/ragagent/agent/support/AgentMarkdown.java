package com.ragagent.agent.support;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * agent 用 HTML → Markdown 抽取。
 *
 * <h2>两个接缝（显式降级路径）</h2>
 * <ol>
 *   <li><b>readability</b>：无 Java 等价物 → 恒走显式
 *       "Readability 失败" 回退路径（body 或 main/article/[role=main]/.content/#content
 *       候选 + fallback 清理）。</li>
 *   <li><b>html-to-markdown v2</b>（commonmark+table 插件）：复用有界实现
 *       {@code datasource.connector.rss.JdkHtmlToMarkdown}
 *       （覆盖段落/标题/列表/链接/代码/图片，表格按未知标签透传内容）。输出差
 *       逐条见其类注释。</li>
 * </ol>
 *
 * <p>标题抽取、块清理、链接绝对化（{@code pageURL.ResolveReference}，剥非 http(s)）、
 * 空链接删除、{@code # title} 前缀等前/后处理逐条保留。</p>
 */
public final class AgentMarkdown {

    private AgentMarkdown() {
    }

    private static final com.ragagent.datasource.connector.rss.HtmlToMarkdown CONVERTER =
            new com.ragagent.datasource.connector.rss.JdkHtmlToMarkdown();

    /** 块级清理（extractedArticle=false 的 fallback 清理 + 恒清理集的并集）。 */
    private static final Pattern[] REMOVE_ALWAYS = {
            Pattern.compile("(?is)<script\\b[^>]*>.*?</script>"),
            Pattern.compile("(?is)<style\\b[^>]*>.*?</style>"),
            Pattern.compile("(?is)<noscript\\b[^>]*>.*?</noscript>"),
            Pattern.compile("(?is)<nav\\b[^>]*>.*?</nav>"),
            Pattern.compile("(?is)<footer\\b[^>]*>.*?</footer>"),
            Pattern.compile("(?is)<aside\\b[^>]*>.*?</aside>"),
            Pattern.compile("(?is)<iframe\\b[^>]*>.*?</iframe>"),
            Pattern.compile("(?is)<svg\\b[^>]*>.*?</svg>"),
            Pattern.compile("(?is)<form\\b[^>]*>.*?</form>"),
            // Readability 失败路径额外剥 header（提取成功时 header 已在
            // readability 输出里被处理，恒剥等价）
            Pattern.compile("(?is)<header\\b[^>]*>.*?</header>"),
    };

    /** HTML → Markdown 抽取入口。 */
    public static String htmlToMarkdown(String source, String rawUrl) {
        URI pageUrl;
        try {
            pageUrl = URI.create(rawUrl);
        } catch (IllegalArgumentException e) {
            throw new FetchException(FetchException.Code.INVALID_URL, false,
                    "HTML parse failed: " + e.getMessage());
        }

        String title = extractTitle(source);

        // readability 接缝：恒走回退路径（extractedArticle = false）
        String doc = removeBlocks(source, REMOVE_ALWAYS);

        // 链接/图片地址对最终 URL 取绝对；非 http(s) 剥属性
        doc = resolveLinks(doc, pageUrl);

        // main 候选（文档序第一个）：main / article / [role='main'] / .content / #content
        String main = extractFirstElement(doc, "main");
        if (main == null) {
            main = extractFirstElement(doc, "article");
        }
        if (main == null) {
            main = extractByAttr(doc, "role", "main");
        }
        if (main == null) {
            main = extractByAttr(doc, "class", "content");
        }
        if (main == null) {
            main = extractByAttr(doc, "id", "content");
        }
        if (main == null) {
            main = extractFirstElement(doc, "body");
        }
        if (main == null) {
            main = doc;
        }

        // 空文本链接删除
        main = main.replaceAll("(?is)<a\\b[^>]*>\\s*</a>", "");

        // 取内层 HTML：剥掉最外层标签
        String inner = innerHtml(main);

        String markdown;
        try {
            markdown = CONVERTER.convert(inner);
        } catch (RuntimeException e) {
            throw new FetchException(FetchException.Code.HTML_PARSE, false,
                    "Markdown conversion failed: " + e.getMessage());
        }
        markdown = markdown == null ? "" : markdown.trim();
        if (markdown.isEmpty()) {
            return "";
        }
        if (!title.isEmpty()) {
            markdown = "# " + title + "\n\n" + markdown;
        }
        return markdown;
    }

    /** <title> 去首尾空白后的文本（有界正则；实体解码覆盖常见 HTML 实体集）。 */
    static String extractTitle(String source) {
        Matcher m = Pattern.compile("(?is)<title\\b[^>]*>(.*?)</title>").matcher(source);
        if (!m.find()) {
            return "";
        }
        String raw = m.group(1);
        String text = Fetcher.HtmlStrip.stripTags(raw);
        return Fetcher.HtmlStrip.goTrimSpace(text);
    }

    private static String removeBlocks(String html, Pattern[] blocks) {
        String out = html;
        for (Pattern p : blocks) {
            out = p.matcher(out).replaceAll("");
        }
        return out;
    }

    private static final Pattern HREF = Pattern.compile(
            "(?is)(<a\\b[^>]*?\\shref\\s*=\\s*)([\"'])([^\"']*)\\2");
    private static final Pattern SRC = Pattern.compile(
            "(?is)(<img\\b[^>]*?\\ssrc\\s*=\\s*)([\"'])([^\"']*)\\2");

    /** 把 a[href]、img[src] 的相对地址改写为基于页面的绝对 URL。 */
    static String resolveLinks(String html, URI pageUrl) {
        html = resolveAttr(HREF, html, pageUrl);
        html = resolveAttr(SRC, html, pageUrl);
        return html;
    }

    private static String resolveAttr(Pattern p, String html, URI pageUrl) {
        StringBuffer out = new StringBuffer(html.length());
        Matcher m = p.matcher(html);
        while (m.find()) {
            String quote = m.group(2);
            String raw = Fetcher.HtmlStrip.goTrimSpace(m.group(3));
            String replacement = m.group();
            try {
                URI target = pageUrl.resolve(URI.create(raw));
                String scheme = target.getScheme() == null ? "" : target.getScheme();
                if (!scheme.equals("http") && !scheme.equals("https")) {
                    replacement = removeAttr(m.group(), p == HREF ? "href" : "src");
                } else {
                    replacement = m.group(1) + quote + target.toString() + quote;
                }
            } catch (RuntimeException e) {
                replacement = removeAttr(m.group(), p == HREF ? "href" : "src");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String removeAttr(String tag, String attr) {
        return tag.replaceAll("(?is)\\s" + attr + "\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", "");
    }

    /** 文档序第一个 <tag ...>...</tag> 的完整元素（按同名标签深度计数）。 */
    static String extractFirstElement(String html, String tag) {
        Matcher open = Pattern.compile("(?i)<" + tag + "(\\s[^>]*)?>").matcher(html);
        if (!open.find()) {
            return null;
        }
        int start = open.start();
        int depth = 1;
        int pos = open.end();
        Matcher next = Pattern.compile("(?i)<(/?)" + tag + "(\\s[^>]*)?>").matcher(html);
        next.region(pos, html.length());
        while (next.find()) {
            if (next.group(1).isEmpty()) {
                depth++;
            } else {
                depth--;
                if (depth == 0) {
                    return html.substring(start, next.end());
                }
            }
        }
        return null;
    }

    /** 属性选择候选（id/class=content、role=main）的文档序第一个元素。 */
    static String extractByAttr(String html, String attr, String value) {
        Matcher open = Pattern.compile("(?i)<(\\w+)(\\s[^>]*\\b" + attr
                + "\\s*=\\s*[\"']" + Pattern.quote(value) + "[\"'][^>]*)>").matcher(html);
        if (!open.find()) {
            return null;
        }
        String tag = open.group(1);
        int start = open.start();
        int depth = 1;
        int pos = open.end();
        Matcher next = Pattern.compile("(?i)<(/?)" + Pattern.quote(tag) + "(\\s[^>]*)?>")
                .matcher(html);
        next.region(pos, html.length());
        while (next.find()) {
            if (next.group(1).isEmpty()) {
                depth++;
            } else {
                depth--;
                if (depth == 0) {
                    return html.substring(start, next.end());
                }
            }
        }
        return null;
    }

    /** 最外层元素的内层 HTML（不含外层标签）。 */
    static String innerHtml(String element) {
        if (element == null) {
            return "";
        }
        int openEnd = element.indexOf('>');
        if (openEnd < 0) {
            return element;
        }
        int closeStart = element.lastIndexOf('<');
        if (closeStart <= openEnd) {
            return element.substring(openEnd + 1);
        }
        return element.substring(openEnd + 1, closeStart);
    }

    /** 供测试/诊断列出清理块（防误删 Pattern 列表）。 */
    static List<String> removalList() {
        List<String> names = new ArrayList<>();
        for (Pattern p : REMOVE_ALWAYS) {
            names.add(p.pattern().toLowerCase(Locale.ROOT));
        }
        return names;
    }
}
