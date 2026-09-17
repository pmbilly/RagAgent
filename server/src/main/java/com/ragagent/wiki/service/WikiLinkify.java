package com.ragagent.wiki.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.ragagent.wiki.service.WikiCrossLinker.LinkifyResult;
import com.ragagent.wiki.service.WikiCrossLinker.LinkRef;

/**
 * {@code [[slug]]} 交叉链接自动注入（对照 Go
 * internal/application/service/wiki_linkify.go 全文，565 行）。
 *
 * <p>本类是该文件的<b>逐行翻译</b>：{@code linkifyContent} 与它的全部辅助函数
 * （{@code computeForbiddenSpans} / {@code findFirstSafeMatch} / {@code hasWordBoundary}
 * / {@code matchMarkdownLink} / {@code scanReferenceDefinitions} / …）逐条移植，
 * 算法与判定顺序完全一致。</p>
 *
 * <h2>为什么实现 {@link WikiCrossLinker} 而不是各写一份</h2>
 * <p>Go 侧有两个入口调用 {@code linkifyContent}：</p>
 * <ul>
 *   <li>{@code wiki_page.go:InjectCrossLinks}（L1852-1892）——用<b>全量页面</b>的
 *       title/aliases 作为 refs，服务 agent 写页后的全局补链；</li>
 *   <li>{@code wiki_ingest.go:injectCrossLinks}（L1812-1872）——只用本批次
 *       受影响页面 + 新鲜 slug 作为 refs，服务 ingest 收尾。</li>
 * </ul>
 * <p>Java 侧 {@link WikiPageService#injectCrossLinks} 已经把第一个入口写成了
 * 「取全量页面 → 挑受影响页 → 调 {@link WikiCrossLinker#linkify}」的编排，并在
 * linkify 未翻译时退化为 {@link WikiCrossLinker.Noop}。本类就是那个缺口的实现：
 * 注册为 bean 后 Noop 自动让位，两个入口同时获得真实的加链能力，
 * <b>无需改动任何既有文件</b>。</p>
 *
 * <h2>字节与码点</h2>
 * <p>Go 全程用<b>字节</b>偏移（{@code len(s)} / {@code s[i]} / {@code utf8.DecodeRune}）。
 * Java 的 {@code String} 是 UTF-16，本实现全程用 <b>char 下标</b>——对"查找子串、
 * 在命中处切接"这类操作，只要始终用同一套下标体系，结果与 Go 逐字节等价。
 * 唯一的语义差异点是<b>长度与邻接字符的判定</b>，已按下述方式对齐：</p>
 * <ul>
 *   <li>ref 排序用的 {@code utf8.RuneCountInString} → {@link String#codePointCount}
 *       （按码点，不是 char，更不是字节）；</li>
 *   <li>{@code DecodeRuneInString(s[end:])} → {@link String#codePointAt}，
 *       {@code DecodeLastRuneInString(s[:pos])} → {@link String#codePointBefore}；
 *       两者都天然处理增补平面字符的代理对。</li>
 * </ul>
 */
@Component
public class WikiLinkify implements WikiCrossLinker {

    /**
     * 不得被加链改写的半开区间 {@code [start, end)}：围栏代码块、行内代码、
     * 既有 {@code [[...]]} wiki 链接、{@code [text](url)} 与 {@code ![alt](url)}。
     * 对照 Go {@code span}（wiki_linkify.go L16-23）。
     */
    record Span(int start, int end) {}

    /**
     * 对照 Go {@code linkifyContent}（wiki_linkify.go L35-82）：为每个 ref 注入
     * <b>至多第一处</b>合格的 {@code [[slug|matchText]]}，跳过落在代码或既有链接
     * 内部的出现；ASCII 字母开头/结尾的 matchText 还要求词边界。
     *
     * <p>已经链到该 slug 的 ref（无论是 {@code [[slug]]} 还是 {@code [[slug|...]]}）
     * 整条跳过；指向 {@code selfSlug} 的 ref 跳过。</p>
     *
     * <p>入参 refs <b>不会被修改</b>（Go 注释明确保证，Java 的 {@link List} 同样只读）。</p>
     */
    @Override
    public LinkifyResult linkify(String content, List<LinkRef> refs, String selfSlug) {
        if (content == null || content.isEmpty() || refs == null || refs.isEmpty()) {
            return new LinkifyResult(content, false);
        }
        String self = selfSlug == null ? "" : selfSlug;

        // 对照 Go L42-55：先过滤出有效 ref，再按 matchText 的**码点数**降序稳定排序，
        // 让长名字赢过自己的子串（"北京邮电大学" 优先于 "北京"）。
        List<LinkRef> sorted = new ArrayList<>(refs.size());
        for (LinkRef ref : refs) {
            if (ref == null) {
                continue;
            }
            String slug = ref.slug() == null ? "" : ref.slug();
            String matchText = ref.matchText() == null ? "" : ref.matchText();
            if (slug.isEmpty() || matchText.isEmpty()) {
                continue;
            }
            if (slug.equals(self)) {
                continue;
            }
            sorted.add(new LinkRef(slug, matchText));
        }
        if (sorted.isEmpty()) {
            return new LinkifyResult(content, false);
        }
        // Go 的 sort.SliceStable：长度降序，等长保持输入顺序
        sorted.sort(Comparator.comparingInt(
                (LinkRef r) -> -r.matchText().codePointCount(0, r.matchText().length())));

        ForbiddenResult forb = computeForbiddenSpans(content);
        List<Span> spans = forb.spans();
        Set<String> used = forb.used();
        String out = content;
        boolean changed = false;

        for (LinkRef ref : sorted) {
            // 该 slug 在正文里已经有链接了 → 整条跳过（对照 Go L62-64）
            if (used.contains(ref.slug())) {
                continue;
            }
            int pos = findFirstSafeMatch(out, ref.matchText(), spans);
            if (pos < 0) {
                continue;
            }
            String replacement = "[[" + ref.slug() + "|" + ref.matchText() + "]]";
            out = out.substring(0, pos) + replacement + out.substring(pos + ref.matchText().length());
            // 对照 Go L71-77：按本次编辑**平移/扩展**禁区，后续 ref 不会把新链接再套一层
            int delta = replacement.length() - ref.matchText().length();
            List<Span> shifted = shiftSpansAfter(spans, pos, delta);
            shifted.add(new Span(pos, pos + replacement.length()));
            sortSpans(shifted);
            spans = shifted;
            used.add(ref.slug());
            changed = true;
        }

        return new LinkifyResult(out, changed);
    }

    /**
     * 对照 Go {@code findFirstSafeMatch}（wiki_linkify.go L88-114）：返回 haystack 里
     * 第一个「不落在任何禁区内、且（对 ASCII 字母边界的 needle）不与其他词字符相邻」
     * 的 needle 出现位置。<b>找不到返回 -1</b>。
     *
     * <p>注意 needle 的第一个不安全命中会让搜索从 {@code pos+1} 继续——这是
     * 「同一个 needle 在代码块里出现过、正文里也出现过」时能正确跳到正文那处的关键
     * （见 {@code TestFindFirstSafeMatch_BoundaryCases}）。</p>
     */
    static int findFirstSafeMatch(String haystack, String needle, List<Span> forbidden) {
        if (needle == null || needle.isEmpty() || haystack == null) {
            return -1;
        }
        boolean needsBoundary = hasAsciiLetterEdge(needle);

        int start = 0;
        while (start <= haystack.length() - needle.length()) {
            int pos = haystack.indexOf(needle, start);
            if (pos < 0) {
                return -1;
            }
            int end = pos + needle.length();

            if (spanContains(forbidden, pos, end)) {
                start = pos + 1;
                continue;
            }
            if (needsBoundary && !hasWordBoundary(haystack, pos, end)) {
                start = pos + 1;
                continue;
            }
            return pos;
        }
        return -1;
    }

    /**
     * 对照 Go {@code hasASCIILetterEdge}（L119-126）：needle 首<b>或</b>尾是 ASCII
     * 字母/数字/下划线时才需要词边界检查——纯 CJK 或以标点收尾的 matchText
     * 没有词边界概念。
     */
    static boolean hasAsciiLetterEdge(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        int first = s.codePointAt(0);
        int last = s.codePointBefore(s.length());
        return isAsciiWordRune(first) || isAsciiWordRune(last);
    }

    /** 对照 Go {@code isASCIIWordRune}（L128-134） */
    static boolean isAsciiWordRune(int r) {
        if (r > 0x7F) { // unicode.MaxASCII
            return false;
        }
        return r == '_' || (r >= '0' && r <= '9') || (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z');
    }

    /**
     * 对照 Go {@code hasWordBoundary}（L140-154）：pos 之前与 end 处的字符都<b>不能</b>
     * 是 ASCII 词字符。非 ASCII 码点（如 CJK）视为边界，所以 "北京" 嵌在
     * "北京邮电大学" 里仍然可匹配——那种冲突由「长度降序」另行解决。
     */
    static boolean hasWordBoundary(String s, int pos, int end) {
        if (pos > 0) {
            if (isAsciiWordRune(s.codePointBefore(pos))) {
                return false;
            }
        }
        if (end < s.length()) {
            if (isAsciiWordRune(s.codePointAt(end))) {
                return false;
            }
        }
        return true;
    }

    /** 对照 Go {@code spanContains}（L157-164）：是否存在与 {@code [pos, end)} 相交的区间 */
    static boolean spanContains(List<Span> spans, int pos, int end) {
        if (spans == null) {
            return false;
        }
        for (Span sp : spans) {
            if (pos < sp.end() && end > sp.start()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 对照 Go {@code shiftSpansAfter}（L166-179）：把起点在 {@code >= pivot} 的区间整体
     * 平移 delta。<b>delta == 0 时原样返回入参</b>（Go 的早退分支，Java 侧用同一个列表
     * 对象表达；调用方随后会 append 并排序，等价于 Go 的切片行为）。
     */
    static List<Span> shiftSpansAfter(List<Span> spans, int pivot, int delta) {
        List<Span> out = new ArrayList<>(spans.size() + 1);
        if (delta == 0) {
            out.addAll(spans);
            return out;
        }
        for (Span sp : spans) {
            if (sp.start() >= pivot) {
                out.add(new Span(sp.start() + delta, sp.end() + delta));
            } else {
                out.add(sp);
            }
        }
        return out;
    }

    /** 对照 Go {@code sortSpans}（L181-188）：按 start 升序，start 相同按 end 升序 */
    static void sortSpans(List<Span> spans) {
        spans.sort(Comparator.comparingInt(Span::start).thenComparingInt(Span::end));
    }

    /**
     * 对照 Go {@code computeForbiddenSpans}（L202-296）：返回正文里不得被加链改写的
     * 区间，以及正文中<b>已经出现</b>的 wiki 链接 slug 集合（调用方据此跳过已链的 ref，
     * 不必二次扫描）。
     *
     * <p>覆盖的禁区：</p>
     * <ul>
     *   <li>{@code ```} / {@code ~~~} 围栏代码块</li>
     *   <li>成对反引号包起来的行内代码</li>
     *   <li>既有 {@code [[slug|...]]} / {@code [[slug]]} wiki 链接</li>
     *   <li>行内 markdown 链接 {@code [text](url)} 与图片 {@code ![alt](url)}</li>
     *   <li>引用式链接 {@code [text][label]}</li>
     *   <li>引用式链接定义行 {@code [label]: url ...}</li>
     *   <li>自动链接 {@code <url>}</li>
     * </ul>
     */
    static ForbiddenResult computeForbiddenSpans(String s) {
        List<Span> spans = new ArrayList<>(8);
        Set<String> used = new HashSet<>();
        int i = 0;
        int n = s.length();

        // Pass 1（对照 Go L208-213）：引用式链接定义。它们独占一行，若不先记下来，
        // 下面的结构扫描会把 `[label]` 当成一个悬空的开括号。
        spans.addAll(scanReferenceDefinitions(s));

        while (i < n) {
            // 围栏代码块：行首的 ``` 或 ~~~
            if (isFenceStart(s, i)) {
                int fenceLen = fenceRun(s, i);
                char fenceCh = s.charAt(i);
                int end = findFenceEnd(s, i + fenceLen, fenceCh, fenceLen);
                spans.add(new Span(i, end));
                i = end;
                continue;
            }

            char c = s.charAt(i);
            switch (c) {
                case '`' -> {
                    // 行内代码：数反引号个数，找等长的闭合串
                    int run = 1;
                    while (i + run < n && s.charAt(i + run) == '`') {
                        run++;
                    }
                    int closeIdx = findInlineCodeClose(s, i + run, run);
                    if (closeIdx < 0) {
                        i += run;
                        continue;
                    }
                    spans.add(new Span(i, closeIdx + run));
                    i = closeIdx + run;
                }
                case '[' -> {
                    // [[slug...]] wiki 链接 —— 把 slug 记进 used
                    if (i + 1 < n && s.charAt(i + 1) == '[') {
                        int rel = s.indexOf("]]", i + 2);
                        if (rel >= 0) {
                            int end = rel + 2;
                            String inner = s.substring(i + 2, rel);
                            String slug = extractWikiSlug(inner);
                            if (!slug.isEmpty()) {
                                used.add(slug);
                            }
                            spans.add(new Span(i, end));
                            i = end;
                            continue;
                        }
                    }
                    // [text](url) 行内 markdown 链接
                    int mdEnd = matchMarkdownLink(s, i);
                    if (mdEnd >= 0) {
                        spans.add(new Span(i, mdEnd));
                        i = mdEnd;
                        continue;
                    }
                    // [text][label] 引用式链接
                    int refEnd = matchReferenceStyleLink(s, i);
                    if (refEnd >= 0) {
                        spans.add(new Span(i, refEnd));
                        i = refEnd;
                        continue;
                    }
                    i++;
                }
                case '!' -> {
                    // ![alt](url) 图片
                    if (i + 1 < n && s.charAt(i + 1) == '[') {
                        int mdEnd = matchMarkdownLink(s, i + 1);
                        if (mdEnd >= 0) {
                            spans.add(new Span(i, mdEnd));
                            i = mdEnd;
                            continue;
                        }
                        int refEnd = matchReferenceStyleLink(s, i + 1);
                        if (refEnd >= 0) {
                            spans.add(new Span(i, refEnd));
                            i = refEnd;
                            continue;
                        }
                    }
                    i++;
                }
                case '<' -> {
                    int autoEnd = matchAutolink(s, i);
                    if (autoEnd >= 0) {
                        spans.add(new Span(i, autoEnd));
                        i = autoEnd;
                        continue;
                    }
                    i++;
                }
                default -> i++;
            }
        }

        sortSpans(spans);
        return new ForbiddenResult(spans, used);
    }

    /**
     * 对照 Go {@code computeForbiddenSpans} 的 {@code ([]span, map[string]struct{})} 返回。
     * 测试用（Go 的 {@code TestComputeForbiddenSpans_*} 直接读这两项）。
     */
    record ForbiddenResult(List<Span> spans, Set<String> used) {}

    /**
     * 对照 Go {@code extractWikiSlug}（L302-311）：解析 {@code [[...]]} 的内部文本，
     * 返回 slug 部分。{@code [[slug|display]]} → {@code "slug"}；
     * {@code [[slug]]} → trim 后的整段。空则返回 ""。
     *
     * <p>（Go 的文档注释提到「含空白则视为不像真 slug」，但<b>代码里并没有这个判断</b>
     * ——以代码行为为准，Java 照抄。）</p>
     */
    static String extractWikiSlug(String inner) {
        int pipe = inner.indexOf('|');
        if (pipe >= 0) {
            inner = inner.substring(0, pipe);
        }
        inner = GoStrings.trimSpace(inner);
        if (inner.isEmpty()) {
            return "";
        }
        return inner;
    }

    /**
     * 对照 Go {@code matchReferenceStyleLink}（L316-334）：从 {@code [} 开始匹配
     * {@code [text][label]}，返回闭合 {@code ]} 之后的下标；不跨行、且两侧括号都必须配平。
     * 不匹配返回 -1。
     */
    static int matchReferenceStyleLink(String s, int i) {
        if (i >= s.length() || s.charAt(i) != '[') {
            return -1;
        }
        int textEnd = findClosingBracket(s, i);
        if (textEnd < 0) {
            return -1;
        }
        if (textEnd + 1 >= s.length() || s.charAt(textEnd + 1) != '[') {
            return -1;
        }
        int labelEnd = findClosingBracket(s, textEnd + 1);
        if (labelEnd < 0) {
            return -1;
        }
        return labelEnd + 1;
    }

    /**
     * 对照 Go {@code findClosingBracket}（L338-364）：返回与位置 i 处 {@code [} 配对的
     * {@code ]} 的下标，尊重 {@code \[} / {@code \]} 转义，遇到换行即放弃。找不到返回 -1。
     */
    static int findClosingBracket(String s, int i) {
        if (i >= s.length() || s.charAt(i) != '[') {
            return -1;
        }
        int depth = 1;
        int j = i + 1;
        while (j < s.length()) {
            char c = s.charAt(j);
            if (c == '\\') {
                if (j + 1 < s.length()) {
                    j += 2;
                    continue;
                }
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return j;
                }
            } else if (c == '\n') {
                return -1;
            }
            j++;
        }
        return -1;
    }

    /**
     * 对照 Go {@code scanReferenceDefinitions}（L370-400）：找出所有
     * {@code [label]: url ...} 定义行，返回它们的区间（<b>含行尾换行符</b>）。
     * 只考虑首个非空格字符是 {@code [} 的行，与 CommonMark「定义最多缩进 3 个空格」
     * 的规则一致。
     */
    static List<Span> scanReferenceDefinitions(String s) {
        List<Span> out = new ArrayList<>();
        int lineStart = 0;
        while (lineStart < s.length()) {
            int nl = s.indexOf('\n', lineStart);
            int lineEnd = nl < 0 ? s.length() : nl + 1; // 含行尾 \n

            // 量出前导缩进（CommonMark 上限 3 个空格）
            int indent = 0;
            while (indent < 3 && lineStart + indent < lineEnd && s.charAt(lineStart + indent) == ' ') {
                indent++;
            }
            int start = lineStart + indent;

            if (start < lineEnd && s.charAt(start) == '[') {
                int labelEnd = findClosingBracket(s, start);
                if (labelEnd >= 0 && labelEnd + 1 < lineEnd && s.charAt(labelEnd + 1) == ':') {
                    // 是引用式定义；禁区覆盖整行
                    out.add(new Span(lineStart, lineEnd));
                }
            }

            lineStart = lineEnd;
        }
        return out;
    }

    /**
     * 对照 Go {@code isFenceStart}（L404-416）：下标 i 是否位于行首、且是一段围栏
     * （{@code ```} 或 {@code ~~~}，三个及以上）的开头。
     */
    static boolean isFenceStart(String s, int i) {
        if (i > 0 && s.charAt(i - 1) != '\n') {
            return false;
        }
        if (i + 2 >= s.length()) {
            return false;
        }
        char c = s.charAt(i);
        if (c != '`' && c != '~') {
            return false;
        }
        return s.charAt(i + 1) == c && s.charAt(i + 2) == c;
    }

    /** 对照 Go {@code fenceRun}（L418-425）：从 i 起同字符连续个数 */
    static int fenceRun(String s, int i) {
        char c = s.charAt(i);
        int j = i;
        while (j < s.length() && s.charAt(j) == c) {
            j++;
        }
        return j - i;
    }

    /**
     * 对照 Go {@code findFenceEnd}（L429-456）：返回闭合围栏之后的下标；
     * 找不到闭合则返回 {@code s.length()}（= 一直吞到文末，与 Go 一致）。
     */
    static int findFenceEnd(String s, int start, char ch, int minLen) {
        // 先推进到下一行
        int nl = s.indexOf('\n', start);
        if (nl < 0) {
            return s.length();
        }
        int pos = nl + 1;
        while (pos < s.length()) {
            if (s.charAt(pos) == ch) {
                int runLen = fenceRun(s, pos);
                if (runLen >= minLen) {
                    // 闭合必须位于行首（此处已在换行之后）。跳过本行剩余字符。
                    int endLine = s.indexOf('\n', pos);
                    if (endLine < 0) {
                        return s.length();
                    }
                    return endLine + 1;
                }
            }
            nl = s.indexOf('\n', pos);
            if (nl < 0) {
                return s.length();
            }
            pos = nl + 1;
        }
        return s.length();
    }

    /**
     * 对照 Go {@code findInlineCodeClose}（L460-482）：返回长度恰好为 runLen 的闭合
     * 反引号串的起始下标，没有则 -1。
     *
     * <p>CommonMark 里换行<b>不</b>终止行内代码，但这里遇到<b>双</b>换行（段落分隔）
     * 就放弃，避免跨段吞掉一大片。</p>
     */
    static int findInlineCodeClose(String s, int start, int runLen) {
        int i = start;
        while (i < s.length()) {
            if (i + 1 < s.length() && s.charAt(i) == '\n' && s.charAt(i + 1) == '\n') {
                return -1;
            }
            if (s.charAt(i) == '`') {
                int j = i;
                while (j < s.length() && s.charAt(j) == '`') {
                    j++;
                }
                if (j - i == runLen) {
                    return i;
                }
                i = j;
                continue;
            }
            i++;
        }
        return -1;
    }

    /**
     * 对照 Go {@code matchMarkdownLink}（L486-545）：从 s[i] == '[' 处匹配
     * {@code [text](url)}，返回闭合 {@code )} 之后的下标；不匹配返回 -1。
     */
    static int matchMarkdownLink(String s, int i) {
        if (i >= s.length() || s.charAt(i) != '[') {
            return -1;
        }
        // 找 ']'，容忍内部配平的 [[ ]]（少见）
        int depth = 1;
        int j = i + 1;
        while (j < s.length() && depth > 0) {
            char c = s.charAt(j);
            if (c == '\\') {
                if (j + 1 < s.length()) {
                    j += 2;
                    continue;
                }
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                // depth == 0 由循环尾部统一跳出（switch 里的 break 只会跳出 switch）
            } else if (c == '\n') {
                // markdown 链接文本不能跨太多换行；放弃以免吞掉一大片
                return -1;
            }
            if (depth == 0) {
                break;
            }
            j++;
        }
        if (j >= s.length() || s.charAt(j) != ']') {
            return -1;
        }
        if (j + 1 >= s.length() || s.charAt(j + 1) != '(') {
            return -1;
        }
        // 找配对的 ')'，允许浅层括号嵌套
        int k = j + 2;
        int parenDepth = 1;
        while (k < s.length() && parenDepth > 0) {
            char c = s.charAt(k);
            if (c == '\\') {
                if (k + 1 < s.length()) {
                    k += 2;
                    continue;
                }
            } else if (c == '(') {
                parenDepth++;
            } else if (c == ')') {
                parenDepth--;
                if (parenDepth == 0) {
                    return k + 1;
                }
            } else if (c == '\n') {
                return -1;
            }
            k++;
        }
        return -1;
    }

    /**
     * 对照 Go {@code matchAutolink}（L548-565）：从 s[i] == '<' 处匹配
     * {@code <scheme://...>}，返回闭合 {@code >} 之后的下标；不匹配返回 -1。
     */
    static int matchAutolink(String s, int i) {
        if (i >= s.length() || s.charAt(i) != '<') {
            return -1;
        }
        int close = s.indexOf('>', i + 1);
        if (close < 0) {
            return -1;
        }
        String inner = s.substring(i + 1, close);
        if (inner.isEmpty() || inner.indexOf(' ') >= 0 || inner.indexOf('\t') >= 0
                || inner.indexOf('\n') >= 0) {
            return -1;
        }
        // 要求 scheme://host 或 mailto: 形态
        if (!inner.contains("://") && !inner.startsWith("mailto:")) {
            return -1;
        }
        return close + 1;
    }
}
