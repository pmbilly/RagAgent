package com.ragagent.datasource.connector.gitlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * {@link GoUrl} 的行为对照表。
 *
 * <h2>期望值怎么来的</h2>
 * <p>逐字符枚举（{@code 0x20..0x7E}）跑出来的转义标记串——<b>不是</b>照文档写的，
 * 也不是照直觉写的。下面那两张 95 位的标记串就是把每个可打印 ASCII 字符过一遍
 * {@link GoUrl#pathEscape} / {@link GoUrl#queryEscape} 后与原文比较得到的
 * （{@code E} = 被转义）。</p>
 *
 * <p>为什么值得这么较真：这些函数决定<b>出站 URL 的字节</b>，而 GitLab 对
 * {@code repository/files/<path>} 那一段是按自己的规则解码的。
 * 一处 {@code %2F} 与 {@code /} 的差别就是 404。</p>
 */
class GoCompatTest {

    /** 每个可打印 ASCII 字符经 {@link GoUrl#pathEscape} 后是否被转义（E = 被转义）。 */
    private static final String PATH_ESCAPE_ESCAPED =
            "EEEE.E.EEEE.E..E...........EE.EE...........................EEEE.E..........................EEE.";

    /** 每个可打印 ASCII 字符经 {@link GoUrl#queryEscape} 后是否被转义（E = 被转义）。 */
    private static final String QUERY_ESCAPE_ESCAPED =
            "EEEEEEEEEEEEE..E..........EEEEEEE..........................EEEE.E..........................EEE.";

    // ── PathEscape / QueryEscape 的全表 ─────────────────────────────────

    @Test
    void pathEscapeMatchesGoForPrintableAscii() {
        assertThat(PATH_ESCAPE_ESCAPED).hasSize(95);
        StringBuilder actual = new StringBuilder();
        for (char c = 0x20; c <= 0x7E; c++) {
            String s = String.valueOf(c);
            actual.append(GoUrl.pathEscape(s).equals(s) ? '.' : 'E');
        }
        assertThat(actual.toString()).isEqualTo(PATH_ESCAPE_ESCAPED);
    }

    @Test
    void queryEscapeMatchesGoForPrintableAscii() {
        assertThat(QUERY_ESCAPE_ESCAPED).hasSize(95);
        StringBuilder actual = new StringBuilder();
        for (char c = 0x20; c <= 0x7E; c++) {
            String s = String.valueOf(c);
            actual.append(GoUrl.queryEscape(s).equals(s) ? '.' : 'E');
        }
        assertThat(actual.toString()).isEqualTo(QUERY_ESCAPE_ESCAPED);
    }

    /** 逐条钉住两处最容易被想当然写错的地方。 */
    @Test
    void escapeHighlights() {
        // PathEscape 保留 $ & + : @（RFC 3986 §2.2 允许出现在 path segment 里），
        // 但 / ; , ? 必须转义
        assertThat(GoUrl.pathEscape("$&+;:@")).isEqualTo("$&+%3B:@");
        assertThat(GoUrl.pathEscape("a/b")).isEqualTo("a%2Fb");
        assertThat(GoUrl.pathEscape("a,b")).isEqualTo("a%2Cb");
        assertThat(GoUrl.pathEscape("a?b")).isEqualTo("a%3Fb");
        assertThat(GoUrl.pathEscape("a b")).isEqualTo("a%20b");
        assertThat(GoUrl.pathEscape("a~b.c_d-e")).isEqualTo("a~b.c_d-e");
        assertThat(GoUrl.pathEscape("中文")).isEqualTo("%E4%B8%AD%E6%96%87");

        // QueryEscape 把空格写成 '+'，reserved 全转义
        assertThat(GoUrl.queryEscape("a b")).isEqualTo("a+b");
        assertThat(GoUrl.queryEscape("a/b c")).isEqualTo("a%2Fb+c");
        assertThat(GoUrl.queryEscape("a&b=c")).isEqualTo("a%26b%3Dc");
    }

    // ── PathUnescape ────────────────────────────────────────────────────

    @Test
    void pathUnescapeMatchesGo() {
        assertThat(GoUrl.pathUnescape("a%2Fb")).isEqualTo("a/b");
        // PathUnescape 不把 '+' 当空格（那是 query 模式的规则）
        assertThat(GoUrl.pathUnescape("a+b")).isEqualTo("a+b");
        assertThat(GoUrl.pathUnescape("a%20b")).isEqualTo("a b");
        assertThat(GoUrl.pathUnescape("%E4%B8%AD")).isEqualTo("中");
        assertThat(GoUrl.pathUnescape("a%2F")).isEqualTo("a/");
        assertThat(GoUrl.pathUnescape("%2f")).isEqualTo("/");
        assertThat(GoUrl.pathUnescape("a~b")).isEqualTo("a~b");
        assertThat(GoUrl.pathUnescape("%7E")).isEqualTo("~");

        assertThatThrownBy(() -> GoUrl.pathUnescape("%zz"))
                .hasMessage("invalid URL escape \"%zz\"");
        assertThatThrownBy(() -> GoUrl.pathUnescape("%"))
                .hasMessage("invalid URL escape \"%\"");
        assertThatThrownBy(() -> GoUrl.pathUnescape("a%2"))
                .hasMessage("invalid URL escape \"%2\"");
        assertThatThrownBy(() -> GoUrl.pathUnescape("%GF"))
                .hasMessage("invalid URL escape \"%GF\"");
    }

    // ── Values.Encode ───────────────────────────────────────────────────

    /** 查询串编码：键排序、空格写 {@code '+'}。 */
    @Test
    void valuesEncodeMatchesGo() {
        assertThat(GoUrl.valuesEncode(Map.of(
                "ref", "main", "per_page", "100", "page", "1", "path", " a/b ")))
                .isEqualTo("page=1&path=+a%2Fb+&per_page=100&ref=main");
        assertThat(GoUrl.valuesEncode(Map.of("ref", "feature/x y", "per_page", "100", "page", "1")))
                .isEqualTo("page=1&per_page=100&ref=feature%2Fx+y");
        assertThat(GoUrl.valuesEncode(Map.of("ref", "main"))).isEqualTo("ref=main");
        assertThat(GoUrl.valuesEncode(Map.of("from", "a", "to", "b"))).isEqualTo("from=a&to=b");

        Map<String, String> ordered = new LinkedHashMap<>();
        ordered.put("ref", "main");
        ordered.put("per_page", "100");
        ordered.put("page", "1");
        assertThat(GoUrl.valuesEncode(ordered)).isEqualTo("page=1&per_page=100&ref=main");

        assertThat(GoUrl.valuesEncode(Map.of())).isEmpty();
        assertThat(GoUrl.valuesEncode(null)).isEmpty();
    }

    // ── base64（B44：GoBase64 退役后只留行为面）──────────────

    /** GitLab 的 base64 每 60 字符换行——解码必须跳过 \n/\r（Java 原生 JDK 解码器路径）。 */
    @Test
    void base64SkipsLineBreaks() {
        byte[] hello = GitLabClient.decodeBase64Content("SGVsbG8sIEdpdExhYiE=");
        assertThat(new String(hello, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("Hello, GitLab!");
        assertThat(GitLabClient.decodeBase64Content("ab\ncd")).hasSize(3);
        assertThat(GitLabClient.decodeBase64Content("ab\r\ncd")).hasSize(3);
        assertThat(GitLabClient.decodeBase64Content("")).isEmpty();
        assertThatThrownBy(() -> GitLabClient.decodeBase64Content("!!!!"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── GoStrings ───────────────────────────────────────────────────────

    /**
     * {@link GoStrings#trimSpace} 比 Java 的 {@code String.strip()} 多认
     * 4 个字符（U+00A0 / U+2007 / U+202F / U+0085）。
     *
     * <p>差别落在"token 是不是空的"这个判定上——凭据常从网页复制，
     * 夹进 NBSP 完全可能，这条差异决定报的是"配置缺失"还是拿到一个 401。</p>
     */
    @Test
    void trimSpaceCoversGoWhitespace() {
        assertThat(GoStrings.trimSpace(null)).isEmpty();
        assertThat(GoStrings.trimSpace("  tok  ")).isEqualTo("tok");
        assertThat(GoStrings.trimSpace("\u00A0tok\u00A0")).isEqualTo("tok");
        assertThat(GoStrings.trimSpace("\u2007tok\u202F")).isEqualTo("tok");
        assertThat(GoStrings.trimSpace("\u0085tok\u3000")).isEqualTo("tok");
        assertThat(GoStrings.trimSpace("\u3000")).isEmpty();
        assertThat(GoStrings.trimSpace("\u00A0")).isEmpty();
        // Java 的 strip() 在这些字符上会放行，正是本工具存在的理由
        assertThat("\u00A0tok\u00A0".strip()).isNotEqualTo("tok");
    }
}
