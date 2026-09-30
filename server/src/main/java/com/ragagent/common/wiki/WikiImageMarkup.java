package com.ragagent.common.wiki;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图片 URL 脱敏 / 还原与图片标记剥离。
 *
 * <h2>为什么要把 URL 脱敏</h2>
 * <p>送进 LLM 的图片 URL 是<b>不透明 token</b>：模型若"顺手"改写、
 * 截断或规范化它，落库的图片链接就废了。因此出站前把 URL 换成低熵占位符
 * {@code wkimg:0001}，入站后还原；模型编造或弄坏的占位符一律<b>丢弃</b>，
 * 保证坏图片链接永远进不了存储。</p>
 *
 * <h2>为什么剥离图片标记要保留内侧文本</h2>
 * <p>PDF 扫描件的 VLM OCR 成功时，抽取文本会被包在
 * {@code <image_ocr>...</image_ocr>} 里（外层还有 {@code <image>}）。若天真地
 * "整块 {@code <image>...</image>} 删掉"，就会丢掉 OCR 文本——恰好是我们最想要的
 * 东西。所以本类只删<b>标签</b>（{@code imageWrapperTagRE}），保留标签之间的内容；
 * 只有 {@code <image_original>}（纯粹是已删图片链接的冗余副本）才整块删除。</p>
 *
 * <h2>正则说明</h2>
 * <p>{@code (?is)} / {@code (?i)}
 * 内联标志在 Java 里同名同义；这几个模式都是"最左匹配 + 惰性量词"。
 * 唯一需要留意的差异在 Java 侧已经规避：{@code Matcher.replaceAll} 会对<b>替换串</b>
 * 做 {@code $} / {@code \} 回溯解析，本类所有替换串都是字面量或经
 * {@link Matcher#quoteReplacement} 处理。</p>
 */
public final class WikiImageMarkup {

    private WikiImageMarkup() {}

    // ── 正则 ──

    /** Markdown 图片引用 {@code ![alt](path)} */
    static final Pattern MD_IMAGE_REF_RE = Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)");

    /** {@code <image_original>...</image_original>} 冗余块（值复制了已删的图片链接） */
    static final Pattern IMAGE_ORIGINAL_BLOCK_RE =
            Pattern.compile("(?is)<image_original\\b[^>]*>.*?</image_original>");

    /** 自闭合 / 只带属性的 HTML {@code <img>} 标签 */
    static final Pattern HTML_IMG_TAG_RE = Pattern.compile("(?i)<img\\b[^>]*/?>");

    /**
     * 包装式 {@code <image> / <images> / <image_caption> / <image_ocr>} 标签
     * （开或闭）。<b>只匹配标签本身</b>，标签之间的文本保留。
     */
    static final Pattern IMAGE_WRAPPER_TAG_RE = Pattern.compile("(?i)</?image[a-z_]*\\b[^>]*/?>");

    /** Markdown 图片引用，单独捕获 URL */
    static final Pattern MD_IMAGE_URL_RE = Pattern.compile("!\\[[^\\]]*\\]\\(([^)]*)\\)");

    /** 富化图片块的对象 URL 属性，如 {@code <image url="...">}（双引号与单引号都认） */
    static final Pattern IMAGE_URL_ATTR_RE =
            Pattern.compile("(?i)<image\\b[^>]*\\surl\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");

    /** 脱敏后的占位符 {@code wkimg:0001} */
    static final Pattern IMAGE_PLACEHOLDER_TOKEN_RE = Pattern.compile("wkimg:[A-Za-z0-9_-]+");

    /**
     * 内容被视为"有实质文本"所需的
     * 非空白、非图片引用码点数下限。
     *
     * <p>阈值刻意定得很低：合法的短文档（简短备忘录、单行笔记）必须仍能通过，
     * 目标只是拦住"只有图片、毫无文字"的情形。字段声明为可变（volatile + setter），
     * 是为了让测试覆盖、以及将来运维不重新编译就能调整。</p>
     */
    private static volatile int minTextContentRunes = 10;

    public static int getMinTextContentRunes() {
        return minTextContentRunes;
    }

    /** 供测试/运维调整阈值。 */
    public static void setMinTextContentRunes(int value) {
        minTextContentRunes = value;
    }

    // ═══════════════════════════════════════════════════════════════
    // 脱敏 / 还原
    // ═══════════════════════════════════════════════════════════════

    /** 脱敏结果：脱敏后数据 + token→URL 还原表。 */
    public record MaskedTemplateData(Map<String, String> masked, Map<String, String> tokenToUrl) {}

    /**
     * 对模板数据的<b>每个字段</b>
     * 做脱敏，<b>跨字段共享</b>同一份 URL→token 映射——因此同一个 URL 出现在
     * {@code Content} 与 {@code ExistingContent} 里时拿到同一个占位符
     * （有契约测试钉住该行为）。
     *
     * <p>按 key<b>排序</b>后处理：token 编号因此是确定性的，
     * 与容器迭代序无关。</p>
     */
    public static MaskedTemplateData maskTemplateDataImageURLs(Map<String, String> data) {
        if (data == null || data.isEmpty()) {
            return new MaskedTemplateData(data == null ? Map.of() : data, null);
        }
        Map<String, String> masked = new LinkedHashMap<>();
        Map<String, String> urlToToken = new LinkedHashMap<>();
        Map<String, String> tokenToUrl = new LinkedHashMap<>();

        List<String> keys = new ArrayList<>(data.keySet());
        java.util.Collections.sort(keys);

        for (String key : keys) {
            // nil 值按 "" 归一
            String value = data.get(key);
            masked.put(key, maskImageURLsWithState(value == null ? "" : value, urlToToken, tokenToUrl));
        }
        return new MaskedTemplateData(masked, tokenToUrl);
    }

    /** 脱敏结果：脱敏后文本 + token→URL 还原表。 */
    public record Masked(String masked, Map<String, String> tokenToUrl) {}

    /** 只冻结 URL；alt / caption 文本原样留给 LLM 编辑。 */
    public static Masked maskImageURLs(String s) {
        Map<String, String> urlToToken = new LinkedHashMap<>();
        Map<String, String> tokenToUrl = new LinkedHashMap<>();
        return new Masked(maskImageURLsWithState(s, urlToToken, tokenToUrl), tokenToUrl);
    }

    /**
     * 收集可脱敏 URL、
     * 分配 {@code wkimg:%04d} 占位符（编号 = 已分配数 + 1），再按 URL <b>长度降序</b>
     * 做全量替换。
     *
     * <p>长度降序的原因：一个 URL 可能是另一个的前缀，先替换短的会把长的切坏。</p>
     */
    static String maskImageURLsWithState(String s, Map<String, String> urlToToken,
                                         Map<String, String> tokenToUrl) {
        List<String> urls = collectMaskableImageURLs(s);
        if (urls.isEmpty()) {
            return s;
        }
        for (String url : urls) {
            if (urlToToken.containsKey(url)) {
                continue;
            }
            String token = String.format("wkimg:%04d", tokenToUrl.size() + 1);
            urlToToken.put(url, token);
            tokenToUrl.put(token, url);
        }

        List<String> replaceUrls = new ArrayList<>(urls);
        // 稳定排序：长度降序，等长保持原顺序
        replaceUrls.sort((a, b) -> Integer.compare(b.length(), a.length()));

        String masked = s;
        for (String url : replaceUrls) {
            masked = masked.replace(url, urlToToken.get(url));
        }
        return masked;
    }

    /**
     * 按出现顺序收集
     * Markdown 图片 URL 与 {@code <image url="...">} 属性 URL，去重。
     */
    static List<String> collectMaskableImageURLs(String s) {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        List<String> urls = new ArrayList<>();

        Matcher md = MD_IMAGE_URL_RE.matcher(s);
        while (md.find()) {
            addUrl(seen, urls, md.group(1));
        }
        Matcher attr = IMAGE_URL_ATTR_RE.matcher(s);
        while (attr.find()) {
            if (attr.group(1) != null && !attr.group(1).isEmpty()) {
                addUrl(seen, urls, attr.group(1));
            } else {
                addUrl(seen, urls, attr.group(2));
            }
        }
        return urls;
    }

    private static void addUrl(Map<String, Boolean> seen, List<String> urls, String url) {
        String trimmed = GoStrings.trimSpace(url);
        if (trimmed.isEmpty()) {
            return;
        }
        if (seen.containsKey(trimmed)) {
            return;
        }
        seen.put(trimmed, Boolean.TRUE);
        urls.add(trimmed);
    }

    /**
     * 还原已知占位符，并
     * <b>丢弃</b>任何损坏或凭空编造的图片占位符，保证坏图片链接永不落库。
     *
     * <p>两趟：</p>
     * <ol>
     *   <li>逐个 Markdown 图片引用处理：URL 在映射里 → 换成真 URL（用
     *       {@code lastIndexOf('(')} 定位 URL 起点）；以 {@code wkimg:}
     *       开头但不在映射里 → <b>整个图片引用替换成空串</b>；其余原样保留。</li>
     *   <li>Markdown 图片引用<b>之外</b>的裸占位符（例如出现在 JSON 字符串里）：
     *       能还原的还原，不能还原的删掉。</li>
     * </ol>
     */
    public static String unmaskImageURLs(String out, Map<String, String> urlMap) {
        if (out == null) {
            return "";
        }
        Map<String, String> map = urlMap == null ? Map.of() : urlMap;

        Matcher m = MD_IMAGE_URL_RE.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String match = m.group();
            // 防御：要求恰好 1 个捕获组（整串之外）
            if (m.groupCount() != 1) {
                m.appendReplacement(sb, Matcher.quoteReplacement(match));
                continue;
            }
            String url = GoStrings.trimSpace(m.group(1));
            String realUrl = map.get(url);
            if (realUrl != null) {
                int idx = match.lastIndexOf('(');
                if (idx < 0) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(match));
                    continue;
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(
                        match.substring(0, idx + 1) + realUrl + ")"));
                continue;
            }
            if (url.startsWith("wkimg:")) {
                m.appendReplacement(sb, "");
                continue;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(match));
        }
        m.appendTail(sb);
        String replaced = sb.toString();

        return replaceImagePlaceholderTokensOutsideMarkdown(replaced, map);
    }

    /**
     * 只对 Markdown 图片引用<b>之外</b>的文本做裸占位符替换——引用内部的占位符
     * 已由上一趟处理，重复替换会破坏已还原的真 URL。
     */
    static String replaceImagePlaceholderTokensOutsideMarkdown(String s, Map<String, String> urlMap) {
        Matcher m = MD_IMAGE_URL_RE.matcher(s);
        List<int[]> matches = new ArrayList<>();
        while (m.find()) {
            matches.add(new int[] {m.start(), m.end()});
        }
        if (matches.isEmpty()) {
            return replaceImagePlaceholderTokens(s, urlMap);
        }

        StringBuilder b = new StringBuilder();
        int last = 0;
        for (int[] match : matches) {
            if (match[0] > last) {
                b.append(replaceImagePlaceholderTokens(s.substring(last, match[0]), urlMap));
            }
            b.append(s, match[0], match[1]);
            last = match[1];
        }
        if (last < s.length()) {
            b.append(replaceImagePlaceholderTokens(s.substring(last), urlMap));
        }
        return b.toString();
    }

    /** 未知占位符删成空串。 */
    static String replaceImagePlaceholderTokens(String s, Map<String, String> urlMap) {
        Matcher m = IMAGE_PLACEHOLDER_TOKEN_RE.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String token = m.group();
            String real = urlMap.get(token);
            m.appendReplacement(sb, Matcher.quoteReplacement(real == null ? "" : real));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    // 图片标记剥离 / 实质文本判定
    // ═══════════════════════════════════════════════════════════════

    /**
     * 删掉"只有图片"的占位
     * （Markdown 图片引用、{@code <img>} 标签、{@code <image_original>} 冗余块），
     * 并<b>解开</b> {@code <image>/<image_caption>/<image_ocr>} 包装标签，
     * 把 OCR / caption 文本留成行内纯文本。
     *
     * <p>处理顺序有意义：先删 {@code <image_original>} 整块
     * （其中含 Markdown 图片引用），再删裸 Markdown 图片引用，再删 {@code <img>}，
     * 最后才解开包装标签。</p>
     */
    public static String stripImageMarkup(String s) {
        if (s == null) {
            return "";
        }
        s = IMAGE_ORIGINAL_BLOCK_RE.matcher(s).replaceAll("");
        s = MD_IMAGE_REF_RE.matcher(s).replaceAll("");
        s = HTML_IMG_TAG_RE.matcher(s).replaceAll("");
        s = IMAGE_WRAPPER_TAG_RE.matcher(s).replaceAll("");
        return s;
    }

    /**
     * 剥离图片标记后去首尾空白。
     * 调用方会缓存结果，供阈值判定与后续日志共用，避免对大文档重复跑正则。
     */
    public static String extractRealText(String content) {
        return GoStrings.trimSpace(stripImageMarkup(content));
    }

    /**
     * 剥掉图片标记后
     * 是否还有足够真实文本（OCR / caption 文本算数）值得发起 LLM 调用。
     * 这是对抗"扫描件无文字时按文件名幻觉"的第一道防线。
     */
    public static boolean hasSufficientTextContent(String content) {
        return realTextRuneCount(content) >= minTextContentRunes;
    }

    /**
     * 剥掉图片标记后的
     * <b>码点</b>长度。
     */
    public static int realTextRuneCount(String content) {
        String real = extractRealText(content);
        return real.codePointCount(0, real.length());
    }

    /** 供 trace 输出的字母序 map（键按字母序稳定输出） */
    static Map<String, String> sortedMap(Map<String, String> source) {
        return new TreeMap<>(source);
    }
}
