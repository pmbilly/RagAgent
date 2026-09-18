package com.ragagent.datasource.connector.rss;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * {@link FeedParser} 的默认实现：<b>只用 JDK 自带的 DOM 解析器</b>，实现 gofeed 的
 * RSS 2.0 / RSS 1.0(RDF) / Atom 1.0 <b>字段子集</b>。
 *
 * <h2>支持的格式</h2>
 * <ul>
 *   <li><b>RSS 2.0 / 0.9x</b>：{@code <rss><channel>}；条目在 channel 下。</li>
 *   <li><b>RSS 1.0 (RDF)</b>：{@code <rdf:RDF>} 根；channel 在根下、<b>条目也在根下</b>
 *       （这是 RSS 1.0 的形状，gofeed 同样两种都收）。</li>
 *   <li><b>Atom 1.0</b>：{@code <feed>} 根；条目是 {@code <entry>}。</li>
 * </ul>
 *
 * <h2>⚠️ 不支持的格式（写进报告，别当成 bug）</h2>
 * <ol>
 *   <li><b>JSON Feed</b>：gofeed 支持（靠嗅探首字符 {@code &#123;} + jsoniter 校验），
 *       Java 侧<b>完全不支持</b>。表现是 {@code parse feed <url>: Failed to detect feed type
 *       (JSON Feed is not supported by this build)}——而 Go 那边<b>会成功</b>。
 *       这是三块接缝之外<b>功能上最实质</b>的一个缺口。</li>
 *   <li><b>gofeed 的其他扩展</b>：iTunes / media / 每个 feed 的 enclosures / categories /
 *       links[] / language / copyright / generator 都不解析——连接器一个都不用。</li>
 * </ol>
 *
 * <h2>与 goxpp 的宽松度差异（第二类缺口，逐条列出）</h2>
 * <ul>
 *   <li><b>未声明的命名实体</b>（{@code &nbsp;}、{@code &mdash;} …）：严格 XML 解析器会
 *       直接报致命错误，而 goxpp 放行。{@link HtmlEntities#makeXmlSafe} 做了预处理，
 *       把 {@link HtmlEntities} 表里认识的实体改写成数字实体、裸 {@code &} 转义掉，
 *       把这条差异收窄到"表里没有的实体原样保留字面量"。</li>
 *   <li><b>不闭合 / 交叉的标签、非法字符</b>：goxpp 容忍一部分，严格的 DOM 直接失败。
 *       Java 侧会报解析错误（{@code parse feed <url>: <解析器原文>}）——<b>文案与 Go 不同</b>。</li>
 *   <li><b>非 UTF-8 编码</b>：两个方向都支持（预处理用 ISO-8859-1 逐字节往返，
 *       真编码仍由解析器按 XML 声明去解），但 goxpp 的 {@code charsetconv} 覆盖面更广
 *       （比如没有 XML 声明却声明在 HTTP {@code Content-Type} 里的情况）。</li>
 * </ul>
 *
 * <h2>日期解析是有界的（第三类缺口）</h2>
 * <p>gofeed 的 {@code shared.ParseDate} 有 <b>约 180 种布局</b>（从 goread 抄来的），
 * 逐个试到成功为止。本实现覆盖其中最常用的约 30 种（见 {@link #OFFSET_DATE_FORMATS} 与
 * {@link #NAIVE_DATE_FORMATS}）：RFC 1123/822 全家、RFC 3339/ISO 8601 全家、
 * {@code yyyy-MM-dd[ HH:mm[:ss]]}、{@code MMM d, yyyy}、{@code d MMM yyyy}、
 * {@code d/M/yyyy}、{@code d.M.yyyy} 等。</p>
 * <p>解析不出来时 {@code *Parsed} 为 {@code null}——<b>与 gofeed 的行为一致</b>
 * （它也是 {@code err != nil} 就跳过、留下 nil），而不是报错。
 * 于是影响的只是"这条用 feed 内容、且更新时间回落到 {@code now()}"，
 * 不会让整次同步失败。<b>这是本实现刻意选择的失败模式</b>：宁可少一个时间戳，
 * 也不要把整条 feed 判为不可解析。</p>
 * <p>不够的地方：gofeed 认的"带名字时区"布局（{@code MST} / {@code CET} 之类，
 * 它还得靠 {@code time.LoadLocation} 兜底）只有一部分能过 Java 的 {@code zzz}；
 * 以及 {@code 6/1/2 15:04}、{@code 02 Monday, Jan 2006 15:04} 这类冷门布局完全不认。</p>
 *
 * <h2>XML 安全</h2>
 * <p>外站实体、外部 DTD、XInclude 全部关掉（对照 Go 侧 goxpp 从不解析 DTD），
 * 实体展开上限交给 JDK 的默认 {@code entityExpansionLimit}——这让
 * "billion laughs" 这类实体炸弹在 JDK 侧直接报错而不是吃满内存。</p>
 */
public final class JdkXmlFeedParser implements FeedParser {

    private static final ZoneOffset UTC = ZoneOffset.UTC;

    /** 带偏移量的日期布局（对照 Go {@code dateFormats} 的常用子集）。 */
    private static final List<String> OFFSET_DATE_FORMATS = List.of(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss XX",
            "EEE, dd MMM yyyy HH:mm:ss XXX",
            "EEE, d MMM yyyy HH:mm:ss Z",
            "EEE MMM d HH:mm:ss Z yyyy",
            "EEE, dd MMM yyyy HH:mm:ss 'GMT'",
            "dd MMM yyyy HH:mm:ss Z",
            "yyyy-MM-dd HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    /** 没有偏移量的布局——Go 的 {@code time.Parse} 对它们按 <b>UTC</b> 处理。 */
    private static final List<String> NAIVE_DATE_FORMATS = List.of(
            "EEE, dd MMM yyyy HH:mm:ss",
            "EEE, dd MMM yyyy",
            "dd MMM yyyy HH:mm:ss",
            "dd MMM yyyy HH:mm",
            "dd MMM yyyy",
            "d MMM yyyy HH:mm:ss",
            "d MMM yyyy",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd",
            "yyyy/MM/dd",
            "dd.MM.yyyy HH:mm:ss",
            "dd.MM.yyyy",
            "d.M.yyyy HH:mm:ss",
            "d.M.yyyy",
            "dd/MM/yyyy HH:mm:ss",
            "dd/MM/yyyy",
            "d/M/yyyy HH:mm:ss",
            "d/M/yyyy",
            "MM/dd/yyyy HH:mm:ss",
            "MM/dd/yyyy",
            "M/d/yyyy HH:mm:ss",
            "M/d/yyyy",
            "d MMMM yyyy HH:mm:ss",
            "d MMMM yyyy",
            "MMMM d, yyyy HH:mm:ss",
            "MMMM d, yyyy",
            "MMM d, yyyy HH:mm:ss",
            "MMM d, yyyy",
            "yyyyMMdd'T'HHmmss",
            "yyyyMMdd");

    private final DocumentBuilderFactory factory;

    public JdkXmlFeedParser() {
        this.factory = hardenedFactory();
    }

    // ── 入口 ──────────────────────────────────────────────────────────────

    @Override
    public ParsedFeed parse(byte[] data) {
        if (data == null || data.length == 0) {
            throw new FeedParseException(FeedParseException.FAILED_TO_DETECT);
        }
        // ISO-8859-1 逐字节往返：让预处理只动 ASCII、不破坏原文档的编码
        // （真编码由解析器按 XML 声明去解，与 Go 侧 charsetconv 的分工一致）。
        String source = new String(data, StandardCharsets.ISO_8859_1);
        int first = firstMeaningfulChar(source);
        if (first < 0) {
            throw new FeedParseException(FeedParseException.FAILED_TO_DETECT);
        }
        char c = source.charAt(first);
        if (c == '{') {
            // Go 这边会按 JSON Feed 解析成功；Java 侧没有 JSON Feed 支持。
            throw new FeedParseException(
                    FeedParseException.FAILED_TO_DETECT + " (JSON Feed is not supported by this build)");
        }
        if (c != '<') {
            throw new FeedParseException(FeedParseException.FAILED_TO_DETECT);
        }
        Document doc = parseXml(HtmlEntities.makeXmlSafe(source).getBytes(StandardCharsets.ISO_8859_1));
        Element root = doc.getDocumentElement();
        if (root == null) {
            throw new FeedParseException(FeedParseException.FAILED_TO_DETECT);
        }
        return switch (localName(root)) {
            case "rss", "rdf" -> parseRss(root);
            case "feed" -> parseAtom(root);
            default -> throw new FeedParseException(FeedParseException.FAILED_TO_DETECT);
        };
    }

    private Document parseXml(byte[] bytes) {
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new FeedParseException(messageOf(e), e);
        }
    }

    private static String messageOf(Exception e) {
        String msg = e.getMessage();
        return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
    }

    /** 跳过 UTF-8/16/32 BOM 与空白，返回第一个有效字符的下标（对照 gofeed 的 {@code DetectFeedType}）。 */
    private static int firstMeaningfulChar(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // gofeed 跳过 ' ' \r \n \t 与 BOM 的六个字节（0xFE 0xFF 0x00 0xEF 0xBB 0xBF）。
            // 这里的字符串是按 ISO-8859-1 解出来的，所以那些字节就是它们对应的字符。
            if (c == ' ' || c == '\r' || c == '\n' || c == '\t'
                    || c == '\uFEFF' || c == '\u00FE' || c == '\u00FF' || c == '\u0000'
                    || c == '\u00EF' || c == '\u00BB' || c == '\u00BF') {
                continue;
            }
            return i;
        }
        return -1;
    }

    private static DocumentBuilderFactory hardenedFactory() {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false); // 按前缀匹配，贴近 goxpp 的行为
        f.setXIncludeAware(false);
        f.setValidating(false);
        setFeatureQuietly(f, "http://xml.org/sax/features/external-general-entities", false);
        setFeatureQuietly(f, "http://xml.org/sax/features/external-parameter-entities", false);
        setFeatureQuietly(f, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        try {
            f.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (IllegalArgumentException ignored) {
            // 某些实现不认这两个属性；外部实体已在 feature 层关掉。
        }
        return f;
    }

    private static void setFeatureQuietly(DocumentBuilderFactory f, String name, boolean value) {
        try {
            f.setFeature(name, value);
        } catch (Exception ignored) {
            // 实现不支持该 feature 时保持默认；其余防护仍然生效。
        }
    }

    // ── RSS ───────────────────────────────────────────────────────────────

    private ParsedFeed parseRss(Element root) {
        Element channel = firstChild(root, "channel");
        String title = "";
        String description = "";
        String link = "";
        OffsetDateTime updated = null;
        List<ParsedItem> items = new ArrayList<>();

        if (channel != null) {
            title = firstNonEmptyText(channel, "title");
            description = firstNonEmptyText(channel, "description");
            link = firstNonEmptyText(channel, "link");
            updated = parseDate(firstNonEmptyText(channel, "lastBuildDate"));
            for (Element item : childElements(channel, "item")) {
                items.add(parseRssItem(item));
            }
        }
        if (title.isEmpty()) {
            title = dcText(channel != null ? channel : root, "title");
        }
        if (updated == null) {
            updated = parseDate(dcText(channel != null ? channel : root, "date"));
        }
        // RSS 1.0 (RDF)：条目直接挂在根下
        for (Element item : childElements(root, "item")) {
            items.add(parseRssItem(item));
        }
        return new ParsedFeed(title, description, link, updated, items);
    }

    private ParsedItem parseRssItem(Element item) {
        String title = firstNonEmptyText(item, "title");
        if (title.isEmpty()) {
            title = dcText(item, "title");
        }
        String description = firstNonEmptyText(item, "description");
        if (description.isEmpty()) {
            description = dcText(item, "description");
        }
        String link = firstNonEmptyText(item, "link");
        String content = contentEncoded(item);
        String guid = "";
        Element guidEl = firstChild(item, "guid");
        if (guidEl != null) {
            guid = parseText(guidEl);
        }
        OffsetDateTime dcDate = parseDate(dcText(item, "date"));
        // gofeed：RSS 的 UpdatedParsed 只来自 dc:date；pubDate 进 PublishedParsed 并优先用它。
        String pubDateText = firstNonEmptyText(item, "pubDate");
        OffsetDateTime published = pubDateText.isEmpty() ? dcDate : parseDate(pubDateText);
        if (published == null) {
            published = dcDate;
        }
        String author = firstNonEmptyText(item, "author");
        if (author.isEmpty()) {
            author = dcText(item, "creator");
        }
        if (author.isEmpty()) {
            author = dcText(item, "author");
        }
        return new ParsedItem(guid, link, title, content, description, dcDate, published,
                author.isEmpty() ? null : parseNameAddress(author));
    }

    /**
     * 对照 gofeed 的 {@code PrefixForNamespace(space, p) == "content"}：
     * 取 {@code <content:encoded>} 的<b>内层 XML</b>（CDATA 会被拆掉）。
     */
    private static String contentEncoded(Element item) {
        for (Element e : childElements(item)) {
            if (!"encoded".equals(localName(e))) {
                continue;
            }
            String prefix = prefixOf(e);
            if ("content".equals(prefix)) {
                return parseText(e);
            }
        }
        return "";
    }

    // ── Atom ──────────────────────────────────────────────────────────────

    private ParsedFeed parseAtom(Element root) {
        String title = firstNonEmptyText(root, "title");
        String description = firstNonEmptyText(root, "subtitle");
        String link = atomLink(root, "alternate");
        OffsetDateTime updated = parseDate(firstNonEmptyText(root, "updated"));
        List<ParsedItem> items = new ArrayList<>();
        for (Element entry : childElements(root, "entry")) {
            items.add(parseAtomItem(entry));
        }
        return new ParsedFeed(title, description, link, updated, items);
    }

    private ParsedItem parseAtomItem(Element entry) {
        String title = firstNonEmptyText(entry, "title");
        String link = atomLink(entry, "alternate");
        String id = firstNonEmptyText(entry, "id");
        OffsetDateTime updated = parseDate(firstNonEmptyText(entry, "updated"));
        OffsetDateTime published = parseDate(firstNonEmptyText(entry, "published"));
        // 照抄 gofeed 的 translateItemPublishedParsed：缺 published 时回落 updated。
        if (published == null) {
            published = updated;
        }
        String summary = firstNonEmptyText(entry, "summary");
        String content = atomContent(entry);
        return new ParsedItem(id, link, title, content, summary, updated, published,
                atomAuthorName(entry));
    }

    /**
     * 对照 gofeed 的 {@code firstLinkWithType("alternate", links)}：
     * <b>精确匹配</b> {@code rel="alternate"}——没有 {@code rel} 属性的
     * {@code <link href="…"/>} 取不到值（gofeed 就是这么写的，实测已钉住）。
     */
    private static String atomLink(Element parent, String rel) {
        for (Element link : childElements(parent, "link")) {
            String r = attr(link, "rel");
            if (rel.equals(r)) {
                return nz(attr(link, "href"));
            }
        }
        return "";
    }

    private static String atomContent(Element entry) {
        Element content = firstChild(entry, "content");
        if (content == null) {
            return "";
        }
        String type = nz(attr(content, "type")).toLowerCase(Locale.ROOT);
        if ("xhtml".equals(type)) {
            return innerXml(content);
        }
        return parseText(content);
    }

    /** 对照 gofeed 的 {@code firstPerson(entry.Authors).Name}——只取第一个 {@code <author><name>}。 */
    private static String atomAuthorName(Element entry) {
        Element author = firstChild(entry, "author");
        if (author == null) {
            return null;
        }
        String name = firstNonEmptyText(author, "name");
        return name.isEmpty() ? null : name;
    }

    // ── DOM 工具（尽量贴近 goxpp 的 ParseText 语义） ──────────────────────

    /**
     * 对照 goxpp 的 {@code ParseText}：拿元素的<b>内层 XML</b>，TrimSpace。
     *
     * <p>goxpp 取的是"原始源文本"再 {@code DecodeEntities}（或对 CDATA 走 {@code StripCDATA}）；
     * 而 DOM 已经替我们把实体解开了，所以这里直接把子节点的值拼起来即可——
     * 效果等价（有测试对着 Go 实录钉住）。嵌套元素会被序列化回标签，
     * 这正是 gofeed 对 {@code <description><p>x</p></description>} 的行为。</p>
     */
    private static String parseText(Element element) {
        return RssUtil.goTrim(innerXml(element));
    }

    /** 元素的内层 XML（文本节点已解码，CDATA 拆掉标记，嵌套元素还原成标签）。 */
    private static String innerXml(Node node) {
        StringBuilder out = new StringBuilder();
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            switch (child.getNodeType()) {
                case Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> out.append(child.getNodeValue());
                case Node.ELEMENT_NODE -> {
                    Element e = (Element) child;
                    out.append('<').append(e.getTagName()).append('>');
                    out.append(innerXml(e));
                    out.append("</").append(e.getTagName()).append('>');
                }
                case Node.COMMENT_NODE -> out.append("<!--").append(child.getNodeValue()).append("-->");
                case Node.ENTITY_REFERENCE_NODE ->
                        out.append('&').append(child.getNodeName()).append(';');
                default -> {
                    // processing instruction 等：忽略
                }
            }
        }
        return out.toString();
    }

    private static String firstNonEmptyText(Element parent, String local) {
        Element child = firstChild(parent, local);
        return child == null ? "" : parseText(child);
    }

    /** 按 {@code dc:xxx} / {@code dcterms:xxx} 前缀或 Dublin Core 命名空间取文本。 */
    private static String dcText(Element parent, String local) {
        if (parent == null) {
            return "";
        }
        for (Element e : childElements(parent)) {
            if (!local.equals(localName(e))) {
                continue;
            }
            String prefix = prefixOf(e);
            if ("dc".equals(prefix) || "dcterms".equals(prefix) || "dcq".equals(prefix)) {
                return parseText(e);
            }
        }
        return "";
    }

    /**
     * 按<b>小写化</b>后的元素名找第一个子元素。
     *
     * <p>⚠️ 传进来的名字也要小写化再比——goxpp 的 {@code p.Name} 本身就是小写的，
     * gofeed 的 switch 里全是 {@code "lastbuilddate"} / {@code "pubdate"} 这种小写常量。
     * 这里若写成直比，{@code pubDate} 会永远找不到。</p>
     */
    private static Element firstChild(Element parent, String local) {
        if (parent == null) {
            return null;
        }
        String wanted = local.toLowerCase(Locale.ROOT);
        for (Element e : childElements(parent)) {
            if (wanted.equals(localName(e))) {
                return e;
            }
        }
        return null;
    }

    /** 见 {@link #firstChild(Element, String)}：按小写化的名字收集全部子元素。 */
    private static List<Element> childElements(Element parent, String local) {
        List<Element> out = new ArrayList<>();
        String wanted = local.toLowerCase(Locale.ROOT);
        for (Element e : childElements(parent)) {
            if (wanted.equals(localName(e))) {
                out.add(e);
            }
        }
        return out;
    }

    private static List<Element> childElements(Element parent) {
        List<Element> out = new ArrayList<>();
        if (parent == null) {
            return out;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                out.add((Element) children.item(i));
            }
        }
        return out;
    }

    /** 元素名冒号后的部分（小写）。 */
    private static String localName(Node node) {
        String name = node.getNodeName();
        int i = name.indexOf(':');
        return (i < 0 ? name : name.substring(i + 1)).toLowerCase(Locale.ROOT);
    }

    /** 元素名冒号前的部分（小写）；无前缀时为空串。 */
    private static String prefixOf(Node node) {
        String name = node.getNodeName();
        int i = name.indexOf(':');
        return i < 0 ? "" : name.substring(0, i).toLowerCase(Locale.ROOT);
    }

    private static String attr(Element element, String name) {
        return element.hasAttribute(name) ? element.getAttribute(name) : null;
    }

    // ── "Name <email>" 拆解（对照 gofeed 的 shared.ParseNameAddress） ──────

    /** {@code "joe@example.com (Joe)"}。 */
    private static final java.util.regex.Pattern EMAIL_NAME =
            java.util.regex.Pattern.compile("^([^@]+@[^\\s]+)\\s+\\(([^@]+)\\)$");

    /** {@code "Joe (joe@example.com)"}。 */
    private static final java.util.regex.Pattern NAME_EMAIL =
            java.util.regex.Pattern.compile("^([^@]+)\\s+\\(([^@]+@[^)]+)\\)$");

    /** 纯名字。 */
    private static final java.util.regex.Pattern NAME_ONLY =
            java.util.regex.Pattern.compile("^([^@()]+)$");

    /** 纯邮箱。 */
    private static final java.util.regex.Pattern EMAIL_ONLY =
            java.util.regex.Pattern.compile("^([^@()]+@[^@()]+)$");

    /**
     * 对照 gofeed 的 {@code shared.ParseNameAddress}，只返回<b>名字</b>那一半
     * （连接器只用 {@code item.Author.Name}）。
     *
     * <p>四条分支的顺序与正则照抄；都没命中时名字为空串。
     * 注意 Go 不 Trim——调用方传进来的值已经过 {@code ParseText} 的 TrimSpace。</p>
     */
    static String parseNameAddress(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        java.util.regex.Matcher m = EMAIL_NAME.matcher(text);
        if (m.matches()) {
            return m.group(2);
        }
        m = NAME_EMAIL.matcher(text);
        if (m.matches()) {
            return m.group(1);
        }
        m = NAME_ONLY.matcher(text);
        if (m.matches()) {
            return m.group(1);
        }
        return ""; // 纯邮箱：名字为空（对照 Go 只填 address）
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ── 日期 ──────────────────────────────────────────────────────────────

    /**
     * 对照 gofeed 的 {@code shared.ParseDate}（有界子集），并把结果<b>归一成 UTC</b>
     * ——gofeed 的解析器在赋 {@code *Parsed} 之前统一做了 {@code date.UTC()}。
     *
     * @return 解析失败回 {@code null}（gofeed 也是"抛错就跳过、留下 nil"，不中断同步）
     */
    static OffsetDateTime parseDate(String raw) {
        String d = RssUtil.goTrim(raw);
        if (d.isEmpty()) {
            return null;
        }
        // 1) 带名字的时区（GMT / EST / CET …）——Go 的 time.RFC1123 与那批 "MST" 布局
        ZonedDateTime zoned = tryZonedFormats(d);
        if (zoned != null) {
            return zoned.withZoneSameInstant(UTC).toOffsetDateTime();
        }
        // 2) 带数字偏移的布局。先把尾巴上的 "GMT"/"UT"/"UTC"/"Z" 换成等价的 "+0000"：
        //    Java 的 "Z"（RFC822）只认 Z / +HHMM / GMT+HH:MM，不认裸的 "GMT"。
        String normalized = normalizeZeroZone(d);
        OffsetDateTime offset = tryOffsetFormats(normalized);
        if (offset != null) {
            return offset.withOffsetSameInstant(UTC);
        }
        offset = tryOffsetFormats(d);
        if (offset != null) {
            return offset.withOffsetSameInstant(UTC);
        }
        // 3) 没有时区的布局：Go 的 time.Parse 按 UTC 处理
        return tryNaiveFormats(d);
    }

    /**
     * 把尾巴上的零时区记号换成 {@code +0000}（只在字符串里还<b>没有</b>数字偏移时做，
     * 免得把 {@code "15:04:05 -0700 GMT"} 这种搞坏——那本来就是 Go 才认的布局）。
     */
    private static String normalizeZeroZone(String d) {
        if (d.matches(".*[+-]\\d{2}:?\\d{2}$")) {
            return d;
        }
        for (String token : List.of(" GMT", " UT", " UTC", " Z")) {
            if (d.endsWith(token)) {
                return d.substring(0, d.length() - token.length()) + " +0000";
            }
        }
        return d;
    }

    private static OffsetDateTime tryOffsetFormats(String d) {
        for (String pattern : OFFSET_DATE_FORMATS) {
            try {
                return OffsetDateTime.parse(d, formatter(pattern));
            } catch (DateTimeParseException ignored) {
                // 试下一个
            }
        }
        // 位置无关的两种规范写法
        for (DateTimeFormatter f : List.of(DateTimeFormatter.RFC_1123_DATE_TIME,
                DateTimeFormatter.ISO_OFFSET_DATE_TIME)) {
            try {
                return OffsetDateTime.parse(d, f);
            } catch (DateTimeParseException ignored) {
                // 试下一个
            }
        }
        return null;
    }

    private static ZonedDateTime tryZonedFormats(String d) {
        for (String pattern : List.of("EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, dd MMM yyyy HH:mm zzz",
                "dd MMM yyyy HH:mm:ss zzz", "yyyy-MM-dd HH:mm:ss zzz", "EEE, d MMM yyyy HH:mm:ss zzz")) {
            try {
                return ZonedDateTime.parse(d, formatter(pattern));
            } catch (DateTimeParseException ignored) {
                // 试下一个
            }
        }
        return null;
    }

    private static OffsetDateTime tryNaiveFormats(String d) {
        for (String pattern : NAIVE_DATE_FORMATS) {
            DateTimeFormatter f = formatter(pattern);
            boolean hasTime = pattern.contains("H");
            try {
                if (hasTime) {
                    return LocalDateTime.parse(d, f).atOffset(UTC);
                }
                return LocalDate.parse(d, f).atStartOfDay().atOffset(UTC);
            } catch (DateTimeParseException ignored) {
                // 试下一个
            }
        }
        return null;
    }

    private static DateTimeFormatter formatter(String pattern) {
        return DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH);
    }
}
