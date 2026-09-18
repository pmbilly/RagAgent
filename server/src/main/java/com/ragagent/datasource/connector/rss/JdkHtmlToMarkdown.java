package com.ragagent.datasource.connector.rss;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link HtmlToMarkdown} 的<b>有界</b>默认实现：只用 JDK 手写一个标签级转换器。
 *
 * <h2>覆盖范围（对齐 html-to-markdown 的常见行为）</h2>
 * <table border="1">
 *   <caption>支持的标签与输出形状（期望值 = Go 实录，见 {@code JdkHtmlToMarkdownTest}）</caption>
 *   <tr><th>HTML</th><th>Markdown</th></tr>
 *   <tr><td>{@code <p>}</td><td>段落之间空行；首个段落前不补</td></tr>
 *   <tr><td>{@code <br>} / {@code <br/>}</td><td>{@code "  \n"}（两个空格 + 换行）</td></tr>
 *   <tr><td>{@code <h1>..<h6>}</td><td>{@code "# "} … {@code "###### "}</td></tr>
 *   <tr><td>{@code <strong>} / {@code <b>}</td><td>{@code **x**}</td></tr>
 *   <tr><td>{@code <em>} / {@code <i>}</td><td>{@code *x*}</td></tr>
 *   <tr><td>{@code <a href>}</td><td>{@code [text](href)}（无 href 时 {@code [text]()}，照抄 Go）</td></tr>
 *   <tr><td>{@code <ul>/<ol>/<li>}</td><td>{@code "- x"} / {@code "1. x"}（{@code <ol start="3">} → {@code "3. x"}）</td></tr>
 *   <tr><td>{@code <code>} / {@code <pre>}</td><td>{@code `x`} / {@code "```\nx\n```"}</td></tr>
 *   <tr><td>{@code <blockquote>}</td><td>每行前缀 {@code "> "}（空行也是 {@code "> "}，照抄 Go）</td></tr>
 *   <tr><td>{@code <hr>}</td><td>{@code "* * *"}</td></tr>
 *   <tr><td>{@code <img src alt>}</td><td>{@code ![alt](src)}</td></tr>
 *   <tr><td>{@code <script>/<style>/<!--…-->}</td><td>整段丢弃</td></tr>
 * </table>
 *
 * <h2>⚠️ 刻意<b>不</b>覆盖的部分（逐条列出，别当成 bug）</h2>
 * <ol>
 *   <li><b>表格</b>：Go 把 {@code <table><tr><td>c1</td><td>c2</td></tr></table>} 输出成
 *       {@code "c1c2"}（不插分隔符）。本实现把 {@code td/th/tr/table} 当<b>未知标签</b>处理
 *       （标签丢掉、内容保留），结果同样是 {@code "c1c2"}——碰巧一致，但不要依赖它做真表格。</li>
 *   <li><b>嵌套列表</b>：Go 输出 {@code "- a\n  \n  - b"}。本实现只按列表深度缩进两格、
 *       不写那段空行——形状接近但<b>不逐字节一致</b>。</li>
 *   <li><b>Markdown 转义只做四个字符</b>：{@code \ → \\}、{@code [ → \[}、
 *       {@code < → &lt;}、{@code > → &gt;}。Go 的 html-to-markdown 也不转义
 *       {@code * _ #}（实测确认），但对 {@code ]}、行首的 {@code -}/{@code 1.} 等
 *       有更细的规则，本实现不管。</li>
 *   <li><b>实体表是有界的</b>：见 {@link HtmlEntities}——{@code &alpha;} 这类
 *       希腊/数学实体在 Java 侧保持字面量。</li>
 *   <li><b>属性解析是宽松的</b>：只认 {@code name="v"} / {@code name='v'} / {@code name=v}；
 *       无值属性（{@code <input disabled>}）会被忽略。</li>
 *   <li><b>未知标签一律"行内透明"</b>（{@code span} / {@code font} / {@code td} …）：
 *       标签丢掉、内容保留。与 Go 对 {@code <span>} 的行为一致。</li>
 *   <li><b>编码</b>：输入按"已经是解码后的字符串"处理（Go 侧 html-to-markdown 会看
 *       {@code <meta charset>}）。连接器拿到的是 feed/文章页解码后的正文，所以实务上无差别。</li>
 * </ol>
 *
 * <h2>失败表达</h2>
 * <p>本实现只在输入为 {@code null} 时抛 {@link HtmlConversionException}
 * （其余情况都尽力产出文本）。即使抛了，调用方也会回落到 {@code TrimSpace(html)} 原文
 * ——与 Go 的 {@code htmlToMarkdown} 完全一致。</p>
 */
public final class JdkHtmlToMarkdown implements HtmlToMarkdown {

    /** 先整段剥掉的块：{@code <script>}、{@code <style>} 与注释。 */
    private static final Pattern STRIP_BLOCKS = Pattern.compile(
            "(?is)<script\\b[^>]*>.*?</script\\s*>"
                    + "|<style\\b[^>]*>.*?</style\\s*>"
                    + "|<!--.*?-->");

    /** 属性：{@code name="v"} / {@code name='v'} / {@code name=v}。 */
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))");

    /** 内部标记：只用于栈配对，不会出现在输出里。 */
    private static final String M_HEADING = "#H";
    private static final String M_QUOTE = "#Q";
    private static final String M_PRE = "#P";
    private static final String M_INLINE_CODE = "`";
    private static final String M_STRONG = "**";
    private static final String M_EM = "*";

    private final StringBuilder sb = new StringBuilder();
    private final Deque<ListState> lists = new ArrayDeque<>();
    private final Deque<String> closers = new ArrayDeque<>();

    private int blockquoteDepth;
    private int preDepth;
    private int pendingBreaks;
    private String pendingMarker;
    /** 是否正好处在"一行的开头"（决定要不要吞掉文本里的前导空格）。 */
    private boolean atLineStart = true;
    /** 上一个写出的字符是不是空格（避免连续空格）。 */
    private boolean lastWasSpace = true;

    @Override
    public String convert(String html) {
        if (html == null) {
            throw new HtmlConversionException("html is null");
        }
        reset();
        String cleaned = STRIP_BLOCKS.matcher(html).replaceAll("");
        tokenize(cleaned);
        return sb.toString();
    }

    // ── 分词（手写状态机，不依赖任何 HTML 库） ─────────────────────────────

    private void tokenize(String html) {
        int i = 0;
        int n = html.length();
        while (i < n) {
            if (html.charAt(i) != '<') {
                int next = html.indexOf('<', i);
                int end = next < 0 ? n : next;
                writeText(HtmlEntities.decode(html.substring(i, end)));
                i = end;
                continue;
            }
            int close = findTagEnd(html, i);
            if (close < 0) {
                writeText(HtmlEntities.decode(html.substring(i)));
                break;
            }
            handleTag(html.substring(i + 1, close));
            i = close + 1;
        }
    }

    /** 找到当前标签的 {@code >}，跳过引号里的内容。 */
    private static int findTagEnd(String html, int start) {
        char quote = 0;
        for (int i = start + 1; i < html.length(); i++) {
            char c = html.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    private void handleTag(String rawTag) {
        String tag = rawTag.trim();
        if (tag.isEmpty() || tag.startsWith("!") || tag.startsWith("?")) {
            return;
        }
        boolean closing = tag.charAt(0) == '/';
        if (closing) {
            tag = tag.substring(1).trim();
        }
        boolean selfClosing = tag.endsWith("/");
        if (selfClosing) {
            tag = tag.substring(0, tag.length() - 1).trim();
        }
        int sp = indexOfWhitespace(tag);
        String name = (sp < 0 ? tag : tag.substring(0, sp)).toLowerCase(Locale.ROOT);
        String attrs = sp < 0 ? "" : tag.substring(sp);
        if (closing) {
            closeTag(name);
        } else {
            openTag(name, attrs);
            if (selfClosing && !isVoid(name)) {
                closeTag(name);
            }
        }
    }

    /** {@code <hr/>}、{@code <br/>} 这类没有"内容"的标签：不收标签对。 */
    private static boolean isVoid(String name) {
        return "br".equals(name) || "hr".equals(name) || "img".equals(name);
    }

    // ── 开标签 ────────────────────────────────────────────────────────────

    private void openTag(String name, String attrs) {
        switch (name) {
            case "p", "div", "section", "article", "header", "footer", "main", "aside",
                 "figure", "figcaption", "dl", "dd", "dt", "form", "fieldset", "address" ->
                    blockBreak(2);
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                blockBreak(2);
                ensurePending();
                sb.append("#".repeat(name.charAt(1) - '0')).append(' ');
                contentWritten();
                closers.push(M_HEADING);
            }
            case "br" -> lineBreak();
            case "hr" -> {
                blockBreak(2);
                ensurePending();
                sb.append("* * *");
                contentWritten();
                blockBreak(2);
            }
            case "ul", "ol" -> {
                if (!lists.isEmpty()) {
                    blockBreak(1);
                }
                int start = 1;
                if ("ol".equals(name)) {
                    String v = attribute(attrs, "start");
                    if (v != null && !v.trim().isEmpty()) {
                        try {
                            start = Integer.parseInt(v.trim());
                        } catch (NumberFormatException ignored) {
                            start = 1;
                        }
                    }
                }
                lists.push(new ListState("ol".equals(name), start));
            }
            case "li" -> {
                blockBreak(1);
                pendingMarker = marker();
            }
            case "blockquote" -> {
                blockBreak(2);
                ensurePending();
                blockquoteDepth++;
                closers.push(M_QUOTE);
            }
            case "pre" -> {
                blockBreak(2);
                ensurePending();
                sb.append("```\n");
                atLineStart = true;
                lastWasSpace = true;
                preDepth++;
                closers.push(M_PRE);
            }
            case "code" -> {
                if (preDepth == 0) {
                    ensurePending();
                    sb.append('`');
                    contentWritten();
                    closers.push(M_INLINE_CODE);
                }
            }
            case "strong", "b" -> {
                ensurePending();
                sb.append("**");
                contentWritten();
                closers.push(M_STRONG);
            }
            case "em", "i" -> {
                ensurePending();
                sb.append('*');
                contentWritten();
                closers.push(M_EM);
            }
            case "a" -> {
                String href = attribute(attrs, "href");
                ensurePending();
                sb.append('[');
                contentWritten();
                closers.push("](" + HtmlEntities.decode(href == null ? "" : href) + ")");
            }
            case "img" -> {
                String src = attribute(attrs, "src");
                String alt = attribute(attrs, "alt");
                ensurePending();
                sb.append("![").append(HtmlEntities.decode(alt == null ? "" : alt)).append("](")
                        .append(HtmlEntities.decode(src == null ? "" : src)).append(')');
                contentWritten();
            }
            default -> {
                // 未知标签：行内透明（丢掉标签、保留内容）。
            }
        }
    }

    // ── 闭标签 ────────────────────────────────────────────────────────────

    private void closeTag(String name) {
        switch (name) {
            case "p", "div", "section", "article", "header", "footer", "main", "aside",
                 "figure", "figcaption", "dl", "dd", "dt", "form", "fieldset", "address" ->
                    blockBreak(2);
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                popCloser(M_HEADING);
                blockBreak(2);
            }
            case "li" -> blockBreak(1);
            case "ul", "ol" -> {
                if (!lists.isEmpty()) {
                    lists.pop();
                }
                blockBreak(2);
            }
            case "blockquote" -> {
                popCloser(M_QUOTE);
                blockquoteDepth = Math.max(0, blockquoteDepth - 1);
                blockBreak(2);
            }
            case "pre" -> {
                popCloser(M_PRE);
                preDepth = Math.max(0, preDepth - 1);
                trimTrailingSpacesIncludingNewlines();
                sb.append("\n```");
                contentWritten();
                blockBreak(2);
            }
            case "code" -> {
                if (popCloser(M_INLINE_CODE) != null) {
                    trimTrailingSpacesIncludingNewlines();
                    sb.append('`');
                    contentWritten();
                }
            }
            case "strong", "b" -> appendCloser(M_STRONG);
            case "em", "i" -> appendCloser(M_EM);
            case "a" -> {
                String closer = popCloser(v -> v.startsWith("]("));
                if (closer != null) {
                    trimTrailingSpacesIncludingNewlines();
                    sb.append(closer);
                    contentWritten();
                }
            }
            default -> {
                // 未知标签的闭标签：忽略。
            }
        }
    }

    private void appendCloser(String marker) {
        if (popCloser(marker) != null) {
            trimTrailingSpacesIncludingNewlines();
            sb.append(marker);
            contentWritten();
        }
    }

    // ── 输出原语 ──────────────────────────────────────────────────────────

    private String marker() {
        String indent = "  ".repeat(Math.max(0, lists.size() - 1));
        ListState top = lists.peek();
        if (top == null) {
            return indent + "- ";
        }
        return top.ordered ? indent + top.next() + ". " : indent + "- ";
    }

    private String linePrefix() {
        return blockquoteDepth <= 0 ? "" : "> ".repeat(blockquoteDepth);
    }

    private void blockBreak(int count) {
        if (sb.length() == 0) {
            return;
        }
        if (count > pendingBreaks) {
            pendingBreaks = count;
        }
    }

    private void lineBreak() {
        ensurePending();
        trimTrailingSpacesIncludingNewlines();
        sb.append("  \n").append(linePrefix());
        atLineStart = true;
        lastWasSpace = true;
    }

    /** 把攒下的"空行"与"列表符号"落成实际字符。 */
    private void ensurePending() {
        // 行首的引用前缀：blockquote 内即使没有换行请求也要补上，
        // 否则"<blockquote><p>x</p></blockquote>"的第一个字就漏了 "> "。
        if (atLineStart && blockquoteDepth > 0
                && (sb.length() == 0 || sb.charAt(sb.length() - 1) == '\n')) {
            sb.append(linePrefix());
        }
        if (pendingBreaks > 0) {
            if (sb.length() == 0) {
                pendingBreaks = 0;
                atLineStart = true;
            } else {
                trimTrailingSpacesIncludingNewlines();
                for (int i = 0; i < pendingBreaks; i++) {
                    sb.append('\n').append(linePrefix());
                }
                pendingBreaks = 0;
                atLineStart = true;
            }
        }
        if (pendingMarker != null) {
            if (!atLineStart) {
                trimTrailingSpacesIncludingNewlines();
                sb.append('\n').append(linePrefix());
            } else if (sb.length() == 0 || sb.charAt(sb.length() - 1) == '\n') {
                sb.append(linePrefix());
            }
            sb.append(pendingMarker);
            pendingMarker = null;
            contentWritten();
        }
    }

    private void writeText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (preDepth > 0) {
            ensurePending();
            sb.append(text);
            contentWritten();
            return;
        }
        String collapsed = collapse(text);
        if (collapsed.isEmpty()) {
            return;
        }
        ensurePending();
        for (int i = 0; i < collapsed.length(); i++) {
            char c = collapsed.charAt(i);
            if (c == ' ') {
                if (atLineStart || lastWasSpace) {
                    continue;
                }
                sb.append(' ');
                lastWasSpace = true;
            } else {
                sb.append(escape(c));
                atLineStart = false;
                lastWasSpace = false;
            }
        }
    }

    private void contentWritten() {
        atLineStart = false;
        lastWasSpace = false;
    }

    /** HTML 的空白折叠：{@code [ \t\n\r\f\v]} 连续出现算一个空格（U+00A0 不折，照抄浏览器/Go）。 */
    private static String collapse(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean inSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0B) {
                if (!inSpace) {
                    out.append(' ');
                    inSpace = true;
                }
            } else {
                out.append(c);
                inSpace = false;
            }
        }
        return out.toString();
    }

    /** 与 Go 的 html-to-markdown 对齐的四个转义（实测确认其余字符<em>不</em>转义）。 */
    private static String escape(char c) {
        return switch (c) {
            case '\\' -> "\\\\";
            case '[' -> "\\[";
            case '<' -> "&lt;";
            case '>' -> "&gt;";
            default -> String.valueOf(c);
        };
    }

    private void trimTrailingSpacesIncludingNewlines() {
        while (sb.length() > 0) {
            char c = sb.charAt(sb.length() - 1);
            if (c == ' ' || c == '\n') {
                sb.setLength(sb.length() - 1);
            } else {
                break;
            }
        }
    }

    /** 从栈里取出最近的 {@code expected}（必要时先弹出压在它上面的东西）。 */
    private String popCloser(String expected) {
        return popCloser(expected::equals);
    }

    private String popCloser(Predicate<String> test) {
        if (closers.stream().noneMatch(test)) {
            return null;
        }
        while (!closers.isEmpty()) {
            String v = closers.pop();
            if (test.test(v)) {
                return v;
            }
        }
        return null;
    }

    private void reset() {
        sb.setLength(0);
        lists.clear();
        closers.clear();
        blockquoteDepth = 0;
        preDepth = 0;
        pendingBreaks = 0;
        pendingMarker = null;
        atLineStart = true;
        lastWasSpace = true;
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static String attribute(String attrs, String name) {
        if (attrs == null || attrs.isEmpty()) {
            return null;
        }
        Matcher m = ATTRIBUTE.matcher(attrs);
        while (m.find()) {
            if (name.equalsIgnoreCase(m.group(1))) {
                String v = m.group(2) != null ? m.group(2)
                        : m.group(3) != null ? m.group(3) : m.group(4);
                return v == null ? "" : v;
            }
        }
        return null;
    }

    /** 一层列表的状态（对照 Go 的 {@code <ol start>} 支持）。 */
    private static final class ListState {
        private final boolean ordered;
        private int next;

        ListState(boolean ordered, int start) {
            this.ordered = ordered;
            this.next = start;
        }

        int next() {
            return next++;
        }
    }
}
