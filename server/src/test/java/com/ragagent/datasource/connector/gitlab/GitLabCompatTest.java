package com.ragagent.datasource.connector.gitlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import com.ragagent.common.text.Whitespace;

/**
 * {@link GitLabUrl} 的行为对照表。
 *
 * <h2>期望值怎么来的</h2>
 * <p>逐字符枚举（{@code 0x20..0x7E}）跑出来的转义标记串——<b>不是</b>照文档写的，
 * 也不是照直觉写的。下面那两张 95 位的标记串就是把每个可打印 ASCII 字符过一遍
 * {@link GitLabUrl#pathEscape} / {@link GitLabUrl#queryEscape} 后与原文比较得到的
 * （{@code E} = 被转义）。</p>
 *
 * <p>为什么值得这么较真：这些函数决定<b>出站 URL 的字节</b>，而 GitLab 对
 * {@code repository/files/<path>} 那一段是按自己的规则解码的。
 * 一处 {@code %2F} 与 {@code /} 的差别就是 404。</p>
 */
class GitLabCompatTest {

    /** 每个可打印 ASCII 字符经 {@link GitLabUrl#pathEscape} 后是否被转义（E = 被转义）。 */
    private static final String PATH_ESCAPE_ESCAPED =
            "EEEE.E.EEEE.E..E...........EE.EE...........................EEEE.E..........................EEE.";

    /** 每个可打印 ASCII 字符经 {@link GitLabUrl#queryEscape} 后是否被转义（E = 被转义）。 */
    private static final String QUERY_ESCAPE_ESCAPED =
            "EEEEEEEEEEEEE..E..........EEEEEEE..........................EEEE.E..........................EEE.";

    // ── PathEscape / QueryEscape 的全表 ─────────────────────────────────

    @Test
    void pathEscapeMatchesGoForPrintableAscii() {
        assertThat(PATH_ESCAPE_ESCAPED).hasSize(95);
        StringBuilder actual = new StringBuilder();
        for (char c = 0x20; c <= 0x7E; c++) {
            String s = String.valueOf(c);
            actual.append(GitLabUrl.pathEscape(s).equals(s) ? '.' : 'E');
        }
        assertThat(actual.toString()).isEqualTo(PATH_ESCAPE_ESCAPED);
    }

    @Test
    void queryEscapeMatchesGoForPrintableAscii() {
        assertThat(QUERY_ESCAPE_ESCAPED).hasSize(95);
        StringBuilder actual = new StringBuilder();
        for (char c = 0x20; c <= 0x7E; c++) {
            String s = String.valueOf(c);
            actual.append(GitLabUrl.queryEscape(s).equals(s) ? '.' : 'E');
        }
        assertThat(actual.toString()).isEqualTo(QUERY_ESCAPE_ESCAPED);
    }

    /** 逐条钉住两处最容易被想当然写错的地方。 */
    @Test
    void escapeHighlights() {
        // PathEscape 保留 $ & + : @（RFC 3986 §2.2 允许出现在 path segment 里），
        // 但 / ; , ? 必须转义
        assertThat(GitLabUrl.pathEscape("$&+;:@")).isEqualTo("$&+%3B:@");
        assertThat(GitLabUrl.pathEscape("a/b")).isEqualTo("a%2Fb");
        assertThat(GitLabUrl.pathEscape("a,b")).isEqualTo("a%2Cb");
        assertThat(GitLabUrl.pathEscape("a?b")).isEqualTo("a%3Fb");
        assertThat(GitLabUrl.pathEscape("a b")).isEqualTo("a%20b");
        assertThat(GitLabUrl.pathEscape("a~b.c_d-e")).isEqualTo("a~b.c_d-e");
        assertThat(GitLabUrl.pathEscape("中文")).isEqualTo("%E4%B8%AD%E6%96%87");

        // QueryEscape 把空格写成 '+'，reserved 全转义
        assertThat(GitLabUrl.queryEscape("a b")).isEqualTo("a+b");
        assertThat(GitLabUrl.queryEscape("a/b c")).isEqualTo("a%2Fb+c");
        assertThat(GitLabUrl.queryEscape("a&b=c")).isEqualTo("a%26b%3Dc");
    }

    // ── PathUnescape ────────────────────────────────────────────────────

    @Test
    void pathUnescapeMatchesGo() {
        assertThat(GitLabUrl.pathUnescape("a%2Fb")).isEqualTo("a/b");
        // PathUnescape 不把 '+' 当空格（那是 query 模式的规则）
        assertThat(GitLabUrl.pathUnescape("a+b")).isEqualTo("a+b");
        assertThat(GitLabUrl.pathUnescape("a%20b")).isEqualTo("a b");
        assertThat(GitLabUrl.pathUnescape("%E4%B8%AD")).isEqualTo("中");
        assertThat(GitLabUrl.pathUnescape("a%2F")).isEqualTo("a/");
        assertThat(GitLabUrl.pathUnescape("%2f")).isEqualTo("/");
        assertThat(GitLabUrl.pathUnescape("a~b")).isEqualTo("a~b");
        assertThat(GitLabUrl.pathUnescape("%7E")).isEqualTo("~");

        assertThatThrownBy(() -> GitLabUrl.pathUnescape("%zz"))
                .hasMessage("invalid URL escape \"%zz\"");
        assertThatThrownBy(() -> GitLabUrl.pathUnescape("%"))
                .hasMessage("invalid URL escape \"%\"");
        assertThatThrownBy(() -> GitLabUrl.pathUnescape("a%2"))
                .hasMessage("invalid URL escape \"%2\"");
        assertThatThrownBy(() -> GitLabUrl.pathUnescape("%GF"))
                .hasMessage("invalid URL escape \"%GF\"");
    }

    // ── Values.Encode ───────────────────────────────────────────────────

    /** 查询串编码：键排序、空格写 {@code '+'}。 */
    @Test
    void valuesEncodeMatchesGo() {
        assertThat(GitLabUrl.valuesEncode(Map.of(
                "ref", "main", "per_page", "100", "page", "1", "path", " a/b ")))
                .isEqualTo("page=1&path=+a%2Fb+&per_page=100&ref=main");
        assertThat(GitLabUrl.valuesEncode(Map.of("ref", "feature/x y", "per_page", "100", "page", "1")))
                .isEqualTo("page=1&per_page=100&ref=feature%2Fx+y");
        assertThat(GitLabUrl.valuesEncode(Map.of("ref", "main"))).isEqualTo("ref=main");
        assertThat(GitLabUrl.valuesEncode(Map.of("from", "a", "to", "b"))).isEqualTo("from=a&to=b");

        Map<String, String> ordered = new LinkedHashMap<>();
        ordered.put("ref", "main");
        ordered.put("per_page", "100");
        ordered.put("page", "1");
        assertThat(GitLabUrl.valuesEncode(ordered)).isEqualTo("page=1&per_page=100&ref=main");

        assertThat(GitLabUrl.valuesEncode(Map.of())).isEmpty();
        assertThat(GitLabUrl.valuesEncode(null)).isEmpty();
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

    // ── Whitespace（B45：GoStrings 收敛后的共享实现）───────────────────

    /**
     * {@link com.ragagent.common.text.Whitespace#trimSpace} 比 Java 的
     * {@code String.strip()} 多认 4 个字符（U+00A0 / U+2007 / U+202F / U+0085）。
     *
     * <p>差别落在"token 是不是空的"这个判定上——凭据常从网页复制，
     * 夹进 NBSP 完全可能，这条差异决定报的是"配置缺失"还是拿到一个 401。</p>
     */
    @Test
    void trimSpaceCoversGoWhitespace() {
        assertThat(Whitespace.trimSpace(null)).isEmpty();
        assertThat(Whitespace.trimSpace("  tok  ")).isEqualTo("tok");
        assertThat(Whitespace.trimSpace("\u00A0tok\u00A0")).isEqualTo("tok");
        assertThat(Whitespace.trimSpace("\u2007tok\u202F")).isEqualTo("tok");
        assertThat(Whitespace.trimSpace("\u0085tok\u3000")).isEqualTo("tok");
        assertThat(Whitespace.trimSpace("\u3000")).isEmpty();
        assertThat(Whitespace.trimSpace("\u00A0")).isEmpty();
        // Java 的 strip() 在这些字符上会放行，正是本工具存在的理由
        assertThat("\u00A0tok\u00A0".strip()).isNotEqualTo("tok");
    }
}
