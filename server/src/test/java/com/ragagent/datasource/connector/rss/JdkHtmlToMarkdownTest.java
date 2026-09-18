package com.ragagent.datasource.connector.rss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * {@link JdkHtmlToMarkdown} 的**语料级**对等测试。
 *
 * <h2>期望值全部是 Go 实录</h2>
 * <p>把 {@code github.com/JohannesKaufmann/html-to-markdown/v2} 的
 * {@code htmltomd.ConvertString} 连同同一个输入喂进一个独立 Go 程序
 * （{@code /tmp/gochk-md}），输出抄进下面的断言。逐条：
 * <pre>
 *   "summary fallback"                                     -&gt; "summary fallback"
 *   "&lt;p&gt;hello &lt;b&gt;world&lt;/b&gt;&lt;/p&gt;"                        -&gt; "hello **world**"
 *   "&lt;p&gt;encoded &lt;em&gt;body&lt;/em&gt;&lt;/p&gt;"                      -&gt; "encoded *body*"
 *   "&lt;p&gt;One.&lt;/p&gt;&lt;p&gt;Two.&lt;/p&gt;&lt;p&gt;Three.&lt;/p&gt;"        -&gt; "One.\n\nTwo.\n\nThree."
 *   "&lt;h1&gt;Head&lt;/h1&gt;&lt;p&gt;para&lt;/p&gt;"                        -&gt; "# Head\n\npara"
 *   "&lt;ul&gt;&lt;li&gt;a&lt;/li&gt;&lt;li&gt;b&lt;/li&gt;&lt;/ul&gt;"              -&gt; "- a\n- b"
 *   "&lt;ol&gt;&lt;li&gt;a&lt;/li&gt;&lt;li&gt;b&lt;/li&gt;&lt;/ol&gt;"              -&gt; "1. a\n2. b"
 *   "&lt;ol start="3"&gt;&lt;li&gt;a&lt;/li&gt;&lt;/ol&gt;"                     -&gt; "3. a"
 *   "&lt;a href="http://x/y"&gt;link&lt;/a&gt;"                       -&gt; "[link](http://x/y)"
 *   "&lt;a&gt;nolink&lt;/a&gt;"                                        -&gt; "[nolink]()"
 *   "&lt;blockquote&gt;&lt;p&gt;quote&lt;/p&gt;&lt;/blockquote&gt;"            -&gt; "&gt; quote"
 *   "&lt;blockquote&gt;&lt;p&gt;a&lt;/p&gt;&lt;p&gt;b&lt;/p&gt;&lt;/blockquote&gt;"      -&gt; "&gt; a\n&gt; \n&gt; b"   ← 空行也带 "&gt; "
 *   "&lt;pre&gt;&lt;code&gt;code here&lt;/code&gt;&lt;/pre&gt;"              -&gt; "```\ncode here\n```"
 *   "&lt;p&gt;inline &lt;code&gt;x=1&lt;/code&gt; end&lt;/p&gt;"               -&gt; "inline `x=1` end"
 *   "&lt;hr&gt;"                                                  -&gt; "* * *"
 *   "&lt;img src="http://x/i.png" alt="alt text"&gt;"               -&gt; "![alt text](http://x/i.png)"
 *   "&lt;p&gt;a&lt;br&gt;b&lt;/p&gt;"                                       -&gt; "a  \nb"
 *   "&lt;script&gt;bad()&lt;/script&gt;&lt;p&gt;safe&lt;/p&gt;&lt;style&gt;p{}&lt;/style&gt;" -&gt; "safe"
 *   "&lt;div&gt;&lt;p&gt;nested&lt;/p&gt;&lt;/div&gt;"                        -&gt; "nested"
 *   "&lt;p&gt;  spaced  &lt;/p&gt;"                                     -&gt; "spaced"（调用方还会 TrimSpace 一次）
 *   "&lt;td&gt;cell&lt;/td&gt;"                                         -&gt; "cell"
 *   "&lt;table&gt;&lt;tr&gt;&lt;td&gt;c1&lt;/td&gt;&lt;td&gt;c2&lt;/td&gt;&lt;/tr&gt;&lt;/table&gt;"   -&gt; "c1c2"
 *   "&lt;p&gt;AT&amp;amp;T &amp;lt;b&amp;gt; &amp;nbsp; &amp;#169;&lt;/p&gt;"              -&gt; "AT&amp;T &amp;lt;b&amp;gt;   ©"  ← "&lt;" 会被转义成 "&amp;lt;"
 *   "&lt;p&gt;a * b _ c [d] # e \ f&lt;/p&gt;"                        -&gt; "a * b _ c \[d] # e \\ f"
 *   "&lt;p&gt;a &lt;span&gt;b&lt;/span&gt; c&lt;/p&gt;"                        -&gt; "a b c"
 * </pre>
 * <p>两条值得单说：{@code &lt;} / {@code &gt;} <b>会</b>被转义成 {@code &amp;lt;} / {@code &amp;gt;}
 * 而 {@code *} / {@code _} / {@code #} <b>不会</b>；{@code <td>} 被当未知标签丢掉后
 * 内容直接相连（{@code "c1c2"}，不是 {@code "c1|c2"}）。</p>
 *
 * <h2>刻意<b>不</b>对齐的边角（也在这里钉住，免得被误以为对齐了）</h2>
 * <ul>
 *   <li>嵌套列表：Go 是 {@code "- a\n  \n  - b"}，本实现只缩进不插空行；</li>
 *   <li>{@code ]} 与行首 {@code -}/{@code 1.} 的转义规则没有复刻；</li>
 *   <li>希腊/数学实体（{@code &alpha;} 之类）不在 {@link HtmlEntities} 表里，
 *       保持字面量而 Go 会解出来。</li>
 * </ul>
 */
class JdkHtmlToMarkdownTest {

    private final JdkHtmlToMarkdown converter = new JdkHtmlToMarkdown();

    private String md(String html) {
        return RssUtil.goTrim(converter.convert(html));
    }

    @Test
    void plainTextPassesThrough() {
        assertThat(md("summary fallback")).isEqualTo("summary fallback");
    }

    @Test
    void paragraphsAndInlineFormatting() {
        assertThat(md("<p>hello <b>world</b></p>")).isEqualTo("hello **world**");
        assertThat(md("<p>encoded <em>body</em></p>")).isEqualTo("encoded *body*");
        assertThat(md("<p>One.</p><p>Two.</p><p>Three.</p>"))
                .isEqualTo("One.\n\nTwo.\n\nThree.");
        assertThat(md("<div><p>nested</p></div>")).isEqualTo("nested");
        assertThat(md("<p>a <span>b</span> c</p>")).isEqualTo("a b c");
        assertThat(md("<p>  spaced  </p>")).isEqualTo("spaced");
    }

    @Test
    void headings() {
        assertThat(md("<h1>Head</h1><p>para</p>")).isEqualTo("# Head\n\npara");
        assertThat(md("<h2>Two</h2><h3>Three</h3>")).isEqualTo("## Two\n\n### Three");
    }

    @Test
    void lists() {
        assertThat(md("<ul><li>a</li><li>b</li></ul>")).isEqualTo("- a\n- b");
        assertThat(md("<ol><li>a</li><li>b</li></ol>")).isEqualTo("1. a\n2. b");
        assertThat(md("<ol start=\"3\"><li>a</li></ol>")).isEqualTo("3. a");
    }

    @Test
    void linksAndImages() {
        assertThat(md("<a href=\"http://x/y\">link</a>")).isEqualTo("[link](http://x/y)");
        // Go 实录：没有 href 也会包成 [text]()
        assertThat(md("<a>nolink</a>")).isEqualTo("[nolink]()");
        assertThat(md("<img src=\"http://x/i.png\" alt=\"alt text\">"))
                .isEqualTo("![alt text](http://x/i.png)");
        assertThat(md("<img src=\"http://x/i.png\">")).isEqualTo("![](http://x/i.png)");
    }

    @Test
    void blockquotes() {
        assertThat(md("<blockquote><p>quote</p></blockquote>")).isEqualTo("> quote");
        // Go 实录：空行也带 "> "（含尾随空格）
        assertThat(md("<blockquote><p>a</p><p>b</p></blockquote>")).isEqualTo("> a\n> \n> b");
    }

    @Test
    void codeAndPre() {
        assertThat(md("<pre><code>code here</code></pre>")).isEqualTo("```\ncode here\n```");
        assertThat(md("<p>inline <code>x=1</code> end</p>")).isEqualTo("inline `x=1` end");
        assertThat(md("<pre>raw &amp; text</pre>")).isEqualTo("```\nraw & text\n```");
    }

    @Test
    void horizontalRuleAndBreaks() {
        assertThat(md("<hr>")).isEqualTo("* * *");
        assertThat(md("<p>a<br>b</p>")).isEqualTo("a  \nb");
        assertThat(md("<p>line1<br/>line2</p>")).isEqualTo("line1  \nline2");
    }

    @Test
    void stripsScriptStyleAndComments() {
        assertThat(md("<script>bad()</script><p>safe</p><style>p{}</style>")).isEqualTo("safe");
        assertThat(md("<!-- note --><p>safe</p>")).isEqualTo("safe");
    }

    @Test
    void entityDecodingAndEscaping() {
        // Go 实录：&amp; -> &（不转义）、&lt;b&gt; -> &lt;b&gt;（< > 转义）、&nbsp; -> U+00A0、&#169; -> ©
        assertThat(md("<p>AT&amp;T &lt;b&gt; &nbsp; &#169;</p>"))
                .isEqualTo("AT&T &lt;b&gt; \u00A0 ©");
        assertThat(md("<p>a&nbsp;b</p>")).isEqualTo("a\u00A0b");
    }

    @Test
    void markdownEscapingMatchesGo() {
        // Go 实录：只有 \ [ < > 被转义，* _ # 不动
        assertThat(md("<p>a * b _ c [d] # e \\ f</p>"))
                .isEqualTo("a * b _ c \\[d] # e \\\\ f");
        assertThat(md("<p>quote \" and 'apos'</p>")).isEqualTo("quote \" and 'apos'");
    }

    @Test
    void unknownTagsAreInlineTransparent() {
        assertThat(md("<td>cell</td>")).isEqualTo("cell");
        assertThat(md("<table><tr><td>c1</td><td>c2</td></tr></table>")).isEqualTo("c1c2");
        assertThat(md("<font color=\"red\">x</font>")).isEqualTo("x");
    }

    @Test
    void whitespaceOnlyAndEmptyInputs() {
        assertThat(md("   ")).isEmpty();
        assertThat(md("<p></p>")).isEmpty();
        assertThat(md("<p>text</p>   ")).isEqualTo("text");
    }

    @Test
    void unicodeIsPreserved() {
        assertThat(md("<p>emoji 🚀 and 中文</p>")).isEqualTo("emoji 🚀 and 中文");
    }

    // ── 已知差异：这里刻意钉住当前行为，别误以为与 Go 对齐了 ──────────────

    @Test
    void nestedListsDifferFromGoAndArePinned() {
        // Go 实录是 "- a\n  \n  - b"（多一个空行）。本实现只缩进，**不**逐字节对齐。
        assertThat(md("<ul><li>a<ul><li>b</li></ul></li></ul>")).isEqualTo("- a\n  - b");
    }

    @Test
    void unsupportedGreekEntitiesStayLiteral() {
        // Go 的 html.UnescapeString 认识全部 HTML5 实体；HtmlEntities 表是有界的。
        // 差异方向是"少解码"，不是"丢内容"。
        assertThat(md("<p>&alpha;&sum;</p>")).isEqualTo("&alpha;&sum;");
        // 表里有的那些两边一致
        assertThat(md("<p>&hellip;&mdash;&laquo;x&raquo;</p>")).isEqualTo("…—«x»");
    }

    @Test
    void nullInputThrowsSoCallerFallsBack() {
        assertThatThrownBy(() -> converter.convert(null))
                .isInstanceOf(HtmlConversionException.class);
    }

    @Test
    void htmlToMarkdownFallbackMatchesGo() {
        // 对照 connector.go 的 htmlToMarkdown：空白输入 -> ""；
        // 转换失败或结果为空 -> TrimSpace(html)。
        assertThat(RssUtil.goTrim(converter.convert("   "))).isEmpty();
        // 一个"转换器总是抛错"的替身：走回落分支
        HtmlToMarkdown failing = html -> {
            throw new HtmlConversionException("boom");
        };
        assertThat(fallback(failing, "  <p>raw</p>  ")).isEqualTo("<p>raw</p>");
    }

    /** 复刻 {@code RssConnector.htmlToMarkdown} 的三条分支（私有方法，就地重写以便单测）。 */
    private static String fallback(HtmlToMarkdown converter, String html) {
        if (RssUtil.goTrim(html).isEmpty()) {
            return "";
        }
        try {
            String result = converter.convert(html);
            if (result == null || RssUtil.goTrim(result).isEmpty()) {
                return RssUtil.goTrim(html);
            }
            return RssUtil.goTrim(result);
        } catch (RuntimeException e) {
            return RssUtil.goTrim(html);
        }
    }
}
