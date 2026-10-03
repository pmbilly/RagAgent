package com.ragagent.datasource.connector.rss;

import java.util.Map;

/**
 * HTML 实体表（语义参照 {@code golang.org/x/net/html.UnescapeString} 的<b>子集</b>）。
 *
 * <h2>两个用途</h2>
 * <ol>
 *   <li>{@link #decode(String)}：给 {@link JdkHtmlToMarkdown} 用——它拿到的是<b>裸 HTML</b>
 *       （不经 DOM），文本节点与属性值里的 {@code &amp;} / {@code &nbsp;} / {@code &#169;}
 *       都要自己解。</li>
 *   <li>{@link #makeXmlSafe(String)}：给 {@link JdkXmlFeedParser} 用——JDK 的 DOM 解析器是
 *       <b>严格</b>的，{@code &nbsp;} 这种未在 DTD 里声明的实体是<b>致命错误</b>；
 *       而参照解析器 gofeed 的 goxpp 是宽松的 pull parser，连裸 {@code &} 都放行。
 *       这个预处理把"未声明的命名实体"改写成数字实体，把裸 {@code &} 转义，
 *       让严格解析器能读下去。</li>
 * </ol>
 *
 * <h2>⚠️ 已知差异：命名实体表是有界的</h2>
 * <p>参照实现认识 <b>HTML5 全部 2000+ 个命名实体</b>；
 * 本表只收最常见的那批。差异的表现是：{@code &alpha;} / {@code &sum;} 这类
 * <b>数学/希腊/箭头</b>实体在这里会<b>原样保留字面量</b>（{@code "&alpha;"}），
 * 参照实现会解成 {@code "α"}。</p>
 * <p>为什么不做全表：一张 2000 行的表塞进这个模块，维护成本远大于它带来的收益，
 * 而 feed 正文里出现希腊字母实体的概率极低（真出现时是"少解码"而不是"丢内容"）。
 * 这条差异<b>不影响控制流</b>——都只是把同一段文本换个写法。</p>
 *
 * <h2>未知实体行为一致</h2>
 * <p>对表里没有的名字，本实现原样返回（与参照实现的 {@code UnescapeString("&foo;")} 行为一致）
 * ——分叉只发生在"参照认识、本表没有"的那部分。</p>
 */
final class HtmlEntities {

    /** 名字 → 码点。覆盖 Latin-1、常见标点、常见符号。 */
    private static final Map<String, Integer> NAMED = Map.ofEntries(
            Map.entry("amp", 0x26), Map.entry("lt", 0x3C), Map.entry("gt", 0x3E),
            Map.entry("quot", 0x22), Map.entry("apos", 0x27),
            Map.entry("nbsp", 0xA0), Map.entry("iexcl", 0xA1), Map.entry("cent", 0xA2),
            Map.entry("pound", 0xA3), Map.entry("curren", 0xA4), Map.entry("yen", 0xA5),
            Map.entry("brvbar", 0xA6), Map.entry("sect", 0xA7), Map.entry("uml", 0xA8),
            Map.entry("copy", 0xA9), Map.entry("ordf", 0xAA), Map.entry("laquo", 0xAB),
            Map.entry("not", 0xAC), Map.entry("shy", 0xAD), Map.entry("reg", 0xAE),
            Map.entry("macr", 0xAF), Map.entry("deg", 0xB0), Map.entry("plusmn", 0xB1),
            Map.entry("sup2", 0xB2), Map.entry("sup3", 0xB3), Map.entry("acute", 0xB4),
            Map.entry("micro", 0xB5), Map.entry("para", 0xB6), Map.entry("middot", 0xB7),
            Map.entry("cedil", 0xB8), Map.entry("sup1", 0xB9), Map.entry("ordm", 0xBA),
            Map.entry("raquo", 0xBB), Map.entry("frac14", 0xBC), Map.entry("frac12", 0xBD),
            Map.entry("frac34", 0xBE), Map.entry("iquest", 0xBF),
            Map.entry("times", 0xD7), Map.entry("divide", 0xF7),
            Map.entry("ndash", 0x2013), Map.entry("mdash", 0x2014),
            Map.entry("lsquo", 0x2018), Map.entry("rsquo", 0x2019),
            Map.entry("sbquo", 0x201A), Map.entry("ldquo", 0x201C),
            Map.entry("rdquo", 0x201D), Map.entry("bdquo", 0x201E),
            Map.entry("dagger", 0x2020), Map.entry("Dagger", 0x2021),
            Map.entry("bull", 0x2022), Map.entry("hellip", 0x2026),
            Map.entry("permil", 0x2030), Map.entry("prime", 0x2032), Map.entry("Prime", 0x2033),
            Map.entry("lsaquo", 0x2039), Map.entry("rsaquo", 0x203A),
            Map.entry("oline", 0x203E), Map.entry("frasl", 0x2044),
            Map.entry("euro", 0x20AC), Map.entry("trade", 0x2122),
            Map.entry("larr", 0x2190), Map.entry("uarr", 0x2191), Map.entry("rarr", 0x2192),
            Map.entry("darr", 0x2193), Map.entry("harr", 0x2194),
            Map.entry("minus", 0x2212), Map.entry("lowast", 0x2217),
            Map.entry("ne", 0x2260), Map.entry("le", 0x2264), Map.entry("ge", 0x2265),
            Map.entry("loz", 0x25CA), Map.entry("spades", 0x2660), Map.entry("clubs", 0x2663),
            Map.entry("hearts", 0x2665), Map.entry("diams", 0x2666),
            Map.entry("OElig", 0x152), Map.entry("oelig", 0x153),
            Map.entry("Scaron", 0x160), Map.entry("scaron", 0x161),
            Map.entry("Yuml", 0x178), Map.entry("fnof", 0x192), Map.entry("circ", 0x2C6),
            Map.entry("tilde", 0x2DC));

    /** XML 预定义实体——严格解析器认识这五个，不需要改写。 */
    private static boolean isXmlPredefined(String name) {
        return "amp".equals(name) || "lt".equals(name) || "gt".equals(name)
                || "quot".equals(name) || "apos".equals(name);
    }

    private HtmlEntities() {
    }

    /**
     * 解出命名实体与数字实体，
     * 认不出来的原样保留。
     *
     * <p>{@code &} 后面没有合法实体形状时原样保留
     * （{@code "AT&T"} 还是 {@code "AT&T"}）。</p>
     */
    static String decode(String s) {
        if (s == null || s.indexOf('&') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c != '&') {
                out.append(c);
                i++;
                continue;
            }
            int semi = s.indexOf(';', i + 1);
            if (semi < 0 || semi - i > 32) {
                out.append(c);
                i++;
                continue;
            }
            String body = s.substring(i + 1, semi);
            String decoded = decodeOne(body);
            if (decoded == null) {
                out.append(c);
                i++;
            } else {
                out.append(decoded);
                i = semi + 1;
            }
        }
        return out.toString();
    }

    /** @return 解出的字符串；{@code null} 表示不是可识别的实体。 */
    private static String decodeOne(String body) {
        if (body.isEmpty()) {
            return null;
        }
        if (body.charAt(0) == '#') {
            try {
                int cp = (body.length() > 1 && (body.charAt(1) == 'x' || body.charAt(1) == 'X'))
                        ? Integer.parseInt(body.substring(2), 16)
                        : Integer.parseInt(body.substring(1));
                if (cp <= 0 || cp > 0x10FFFF) {
                    return null;
                }
                return new String(Character.toChars(cp));
            } catch (RuntimeException e) {
                return null;
            }
        }
        Integer cp = NAMED.get(body);
        return cp == null ? null : new String(Character.toChars(cp));
    }

    /**
     * 把一段<b>原始 XML 源文本</b>改写成"严格 XML 解析器也能吃下"的形态。
     *
     * <p>规则（都在 CDATA 段与注释之外生效）：</p>
     * <ul>
     *   <li>XML 五个预定义实体与数字实体 → 原样保留；</li>
     *   <li>本表认识的命名实体 → 改写成 {@code &#NNNN;}；</li>
     *   <li>表里没有的命名实体 → 把 {@code &} 转义成 {@code &amp;}，
     *       于是解析后 DOM 里拿到的仍是字面量 {@code "&name;"}——
     *       <b>对未知实体的处理与参照实现一致</b>；</li>
     *   <li>其它裸 {@code &} → {@code &amp;}（宽松 parser 直接放行，解出来都是 {@code &}）。</li>
     * </ul>
     *
     * <p>调用方必须用 <b>ISO-8859-1</b> 做 bytes↔String 的往返，这样非 UTF-8 的
     * feed（{@code encoding="gb2312"} 之类）也能原样透给解析器，
     * 由解析器按 XML 声明里的编码去解。</p>
     */
    static String makeXmlSafe(String source) {
        if (source == null || source.indexOf('&') < 0) {
            return source;
        }
        StringBuilder out = new StringBuilder(source.length() + 64);
        int i = 0;
        int n = source.length();
        while (i < n) {
            // CDATA 段与注释整段跳过：里面的 & 不是实体起始符
            if (source.startsWith("<![CDATA[", i)) {
                int end = source.indexOf("]]>", i + 9);
                int stop = end < 0 ? n : end + 3;
                out.append(source, i, stop);
                i = stop;
                continue;
            }
            if (source.startsWith("<!--", i)) {
                int end = source.indexOf("-->", i + 4);
                int stop = end < 0 ? n : end + 3;
                out.append(source, i, stop);
                i = stop;
                continue;
            }
            char c = source.charAt(i);
            if (c != '&') {
                out.append(c);
                i++;
                continue;
            }
            int semi = source.indexOf(';', i + 1);
            if (semi < 0 || semi - i > 32) {
                out.append("&amp;");
                i++;
                continue;
            }
            String body = source.substring(i + 1, semi);
            if (body.isEmpty()) {
                out.append("&amp;");
                i++;
                continue;
            }
            if (body.charAt(0) == '#') {
                out.append(source, i, semi + 1); // 数字实体：原样交给解析器
                i = semi + 1;
                continue;
            }
            if (isXmlPredefined(body)) {
                out.append(source, i, semi + 1);
                i = semi + 1;
                continue;
            }
            Integer cp = NAMED.get(body);
            if (cp != null) {
                out.append("&#").append(cp).append(';');
            } else {
                out.append("&amp;").append(body).append(';');
            }
            i = semi + 1;
        }
        return out.toString();
    }
}
