package com.ragagent.wiki.service;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 模糊 slug 还原。
 *
 * <p><b>为什么在 wiki 服务层独立成类：</b>它被页面链接修复与 ingest 清理路径共用；
 * ingest 侧需要时应<b>复用本类</b>而不是另起一份（否则两处阈值会漂移）。</p>
 *
 * <p><b>背景</b>：wiki ingest 的 LLM 拿到精确的
 * {@code [[slug]] = title} 清单，仍会把 slug 弄错——插连字符、丢/重连字符、
 * 改大小写、把该是 CJK 的名字拉丁化。display 文本几乎总是对的，所以有两根杠杆：
 * 对 slug 本身的宽容比较 + 用 display 文本反查标题。</p>
 */
public final class SlugFuzzy {

    private SlugFuzzy() {}

    /**
     * 字符 bigram 的 Jaccard
     * 相似度下限。0.8 是刻意保守的——这个量级下 "shang-hai-tower" 与
     * "shanghai-tower" 仍能匹配，而 "user-profile" 与 "user-permissions" 不会
     * （前缀相同但词干不同）。误链到错误的页比输出纯文本更糟。
     */
    public static final double BIGRAM_THRESHOLD = 0.8;

    /**
     * 折叠纯装饰性的 slug 差异
     * （小写 + 去掉全部连字符与下划线），让只差连字符/大小写的两个 slug 视为同一
     * 逻辑 token 袋。CJK 原样保留——它们就是 CJK slug 的身份本身，折叠会过度合并。
     */
    public static String normalizeSlugForCompare(String slug) {
        if (slug == null) {
            return "";
        }
        return slug.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }

    /**
     * 给定（已归一化的）slug 的字符
     * bigram 集合。单字符 slug 退化成 1-gram，仍能贡献可比较的信号。
     *
     * <p>必须按<b>码点</b>切而不是 char，
     * 否则增补平面的字符（emoji 等）会被拆成两个代理项。</p>
     */
    public static Set<String> slugCharBigrams(String s) {
        if (s == null || s.isEmpty()) {
            return Set.of();
        }
        int[] cps = s.codePoints().toArray();
        if (cps.length == 1) {
            return Set.of(new String(cps, 0, 1));
        }
        Set<String> out = new LinkedHashSet<>(cps.length);
        for (int i = 0; i < cps.length - 1; i++) {
            out.add(new String(cps, i, 2));
        }
        return out;
    }

    /**
     * Jaccard 相似度。
     *
     * <p>两个语义细节：两边都空时返回 0（不是 1）；大小集合互换以便
     * 用较小的一侧驱动交集循环。</p>
     */
    public static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 0;
        }
        if (a.size() > b.size()) {
            return jaccard(b, a);
        }
        int inter = 0;
        for (String k : a) {
            if (b.contains(k)) {
                inter++;
            }
        }
        int union = a.size() + b.size() - inter;
        if (union == 0) {
            return 0;
        }
        return (double) inter / (double) union;
    }

    /**
     * 把死链 {@code [[slug]]} 映射回
     * 一个活跃 KB slug，逐级放宽：
     *
     * <ol>
     *   <li><b>display 文本反查</b>：LLM 写 {@code [[bad-slug|上海中心大厦]]} 时，
     *       若活跃页面的 Title 或别名正好是 "上海中心大厦"，直接返回它的 slug。
     *       这是绝大多数情况。</li>
     *   <li><b>连字符/大小写归一化相等</b>：归一后相等的两个 slug 视为同一逻辑页面。</li>
     *   <li><b>bigram Jaccard ≥ {@value #BIGRAM_THRESHOLD}</b>：抓归一化修不了的
     *       错别字与字符替换。</li>
     * </ol>
     *
     * @param deadSlug    待还原的 slug（调用方已 normalizeSlug 过）
     * @param displayText 链接的 display 部分（可为空）
     * @param liveSlugs   候选活跃 slug 集合（调用方给定，本方法只读不写）
     * @param titleToSlug 精确（大小写敏感）标题/别名 → slug 的反查表
     * @return 命中的活跃 slug；没有足够接近的候选时返回 null
     *
     * <p><b>确定性</b>：多个候选并列时按调用方给的 {@code liveSlugs} 迭代顺序取
     * 第一个命中（best 严格大于才替换），结果稳定可复现。</p>
     */
    public static String resolveDeadSlug(String deadSlug,
                                         String displayText,
                                         Set<String> liveSlugs,
                                         Map<String, String> titleToSlug) {
        if (deadSlug == null || deadSlug.isEmpty()) {
            return null;
        }
        Set<String> live = liveSlugs == null ? Set.of() : liveSlugs;
        Map<String, String> titles = titleToSlug == null ? Map.of() : titleToSlug;

        // (0) 本来就是活跃的？直接当成功返回
        if (live.contains(deadSlug)) {
            return deadSlug;
        }

        // (1) display 文本反查。trim 是因为 LLM 偶尔产出
        //     `[[slug| display ]]` 带多余空格，而用户侧标题不会带。
        String dt = displayText == null ? "" : displayText.trim();
        if (!dt.isEmpty()) {
            String slug = titles.get(dt);
            if (slug != null && !slug.isEmpty() && live.contains(slug)) {
                return slug;
            }
        }

        // (2) 归一化相等
        String deadNorm = normalizeSlugForCompare(deadSlug);
        if (deadNorm.isEmpty()) {
            // 归一化后只剩空——原始 slug 全是连字符/下划线，没有可比的东西
            return null;
        }
        for (String cand : live) {
            if (normalizeSlugForCompare(cand).equals(deadNorm)) {
                return cand;
            }
        }

        // (3) bigram Jaccard 兜底
        Set<String> deadGrams = slugCharBigrams(deadNorm);
        if (deadGrams.isEmpty()) {
            return null;
        }
        String bestSlug = null;
        double bestScore = 0;
        for (String cand : live) {
            String candNorm = normalizeSlugForCompare(cand);
            if (candNorm.isEmpty()) {
                continue;
            }
            Set<String> candGrams = slugCharBigrams(candNorm);
            if (candGrams.isEmpty()) {
                continue;
            }
            double score = jaccard(deadGrams, candGrams);
            if (score > bestScore) {
                bestScore = score;
                bestSlug = cand;
            }
        }
        if (bestScore >= BIGRAM_THRESHOLD) {
            return bestSlug;
        }
        return null;
    }

    // ──────────────────────── 链接重写工具 ────────────────────────

    /**
     * 遍历正文里每一处
     * {@code [[slug]] / [[slug|display]]}，由 {@code resolve} 逐条决定是否重写 slug。
     *
     * <p>display 文本<b>原样保留</b>。这是纯工具——解析策略全在回调里。</p>
     */
    public static RewriteResult rewriteDeadWikiLinks(String content, SlugResolver resolve) {
        StringBuilder out = new StringBuilder(content.length());
        java.util.regex.Matcher m = WikiPageServiceImpl.WIKI_LINK_REGEX.matcher(content);
        boolean changed = false;
        int last = 0;
        while (m.find()) {
            String match = m.group();
            String inner = m.group(1);
            String rawSlug = inner;
            String display = "";
            int pipe = inner.indexOf('|');
            if (pipe >= 0) {
                rawSlug = inner.substring(0, pipe);
                display = inner.substring(pipe + 1).trim();
            }
            String norm = WikiPageServiceImpl.normalizeSlug(rawSlug);
            if (norm.isEmpty()) {
                continue;
            }
            String newSlug = resolve.resolve(norm, display);
            if (newSlug == null || newSlug.isEmpty() || newSlug.equals(norm)) {
                continue;
            }
            changed = true;
            out.append(content, last, m.start());
            out.append(display.isEmpty() ? "[[" + newSlug + "]]" : "[[" + newSlug + "|" + display + "]]");
            last = m.end();
        }
        if (!changed) {
            return new RewriteResult(content, false);
        }
        out.append(content, last, content.length());
        return new RewriteResult(out.toString(), true);
    }

    /** {@link #rewriteDeadWikiLinks} 的逐条决策回调 */
    @FunctionalInterface
    public interface SlugResolver {
        /** 返回替换后的 slug；返回 null / 空串表示"保持原样不重写" */
        String resolve(String normSlug, String display);
    }

    /** 重写结果：新正文 + 是否有改动 */
    public record RewriteResult(String content, boolean changed) {}
}
