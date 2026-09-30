package com.ragagent.wiki.service.page;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.ragagent.common.wiki.GoStrings;
import com.ragagent.wiki.service.ingest.WikiBatchContext;

/**
 * 死链重写。
 *
 * <h2>为什么需要它</h2>
 * <p>摘要生成要求 LLM 为它知道的每个抽取 slug 内嵌
 * wiki 链接，但 slug 抽取发生在 map 阶段（与摘要生成并行），而页面的真正创建在更晚的
 * reduce 阶段。当 reduce 的 {@code WikiPageModifyUserPrompt} 在某个 entity/concept
 * slug 上失败时，页面从未被写出——而<b>已经持久化</b>的摘要页手里还留着一条
 * {@code [[entity/foo|name]]}，指向一个 404。</p>
 *
 * <p>处理分两档：能救的就地<b>治愈</b>（LLM 把 slug 弄花，如
 * {@code "shang-hai-tower"} vs {@code "shanghai-tower"}——display 文本往往是对的，
 * 因此用 display 反查标题、连字符归一化相等、bigram 相似度依次尝试），
 * 救不了的就<b>剥离</b>成纯文本（有 display 用 display，否则把 slug 的末段
 * 人性化）。这是纯文本替换，不调用 LLM。</p>
 *
 * <p><b>复用 {@link SlugFuzzy}</b>：本类刻意调用 {@link SlugFuzzy#resolveDeadSlug}
 * 而不是另起一份实现，否则两处的相似度阈值会漂移。</p>
 *
 * <p><b>注意</b>：传给 resolver 的是正则捕获组里的<b>原始 slug</b>
 * （未经 {@code normalizeSlug}）——这与 {@link SlugFuzzy#rewriteDeadWikiLinks}
 * 的调用形态不同（那边先归一化）。这是刻意保留的 ingest 路径行为。</p>
 */
public final class WikiDeadLinks {

    private WikiDeadLinks() {}

    /**
     * 匹配正文里的 {@code [[slug]]} 与
     * {@code [[slug|display text]]}。slug 捕获组拒绝空白与 {@code [ ] |}，
     * 避免误吞相邻文本；display 文本（第 2 组）可选。
     */
    public static final Pattern WIKI_LINK_RE =
            Pattern.compile("\\[\\[([^\\[\\]|\\s]+)(?:\\|([^\\]]+))?\\]\\]");

    /**
     * 把 {@code slug} 落在
     * {@code deadSlugs} 里的 wiki 链接重写。处理取决于能否修复：
     *
     * <ul>
     *   <li>resolver 能把死 slug 映射到活跃 slug（通常靠 display 文本反查或
     *       连字符归一化相等）→ <b>改写</b>链接，display 文本保留；</li>
     *   <li>没有足够接近的活跃候选 → 退化为<b>剥离</b>成纯文本。</li>
     * </ul>
     *
     * <p>resolver 是可选的：{@code liveSlugs} / {@code titleToSlug} 为 null 或空时，
     * 每个死 slug 都直接走剥离路径——这保持了尚未接线解析数据的历史调用点
     * （含测试）的行为。</p>
     *
     * <p><b>实现注意</b>：{@code Matcher.appendReplacement} 会对替换串做
     * {@code $}/{@code \} 回溯解析，
     * 因此所有替换串都必须过 {@link Matcher#quoteReplacement}——display 文本来自
     * 文档正文，含有 {@code $} 完全正常。</p>
     */
    public static Result stripDeadWikiLinks(String content,
                                            Set<String> deadSlugs,
                                            Set<String> liveSlugs,
                                            Map<String, String> titleToSlug) {
        if (deadSlugs == null || deadSlugs.isEmpty() || content == null || content.isEmpty()) {
            return new Result(content, false);
        }
        boolean[] changed = {false};
        Matcher m = WIKI_LINK_RE.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String match = m.group();
            String slug = m.group(1);
            if (slug == null || !deadSlugs.contains(slug)) {
                m.appendReplacement(sb, Matcher.quoteReplacement(match));
                continue;
            }
            String display = "";
            if (m.group(2) != null) {
                display = GoStrings.trimSpace(m.group(2));
            }

            // (1) 先试模糊还原。resolver 依次查 display 文本反查、连字符归一化相等、
            //     bigram 相似度；没有安全候选时返回 null。
            String resolved = SlugFuzzy.resolveDeadSlug(slug, display, liveSlugs, titleToSlug);
            if (resolved != null && !resolved.isEmpty() && !resolved.equals(slug)) {
                changed[0] = true;
                String replacement = display.isEmpty()
                        ? "[[" + resolved + "]]"
                        : "[[" + resolved + "|" + display + "]]";
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                continue;
            }

            // (2) 剥离 —— 尽力而为的纯文本。优先用 LLM 给的 display 文本；
            //     否则把 slug 的末段人性化，让行文仍可读。
            changed[0] = true;
            if (!display.isEmpty()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(display));
                continue;
            }
            String[] parts = slug.split("/", -1);
            String label = parts[parts.length - 1].replace("-", " ");
            m.appendReplacement(sb, Matcher.quoteReplacement(label));
        }
        m.appendTail(sb);
        return new Result(sb.toString(), changed[0]);
    }

    /** 结果：新正文 + 是否有改动。 */
    public record Result(String content, boolean changed) {}

    /**
     * 构造
     * {@code (liveSlugs, titleToSlug)} 二元组。
     *
     * <p>从调用方给的候选集出发（通常是页面自己的出链 + 本批次刚写出的 slug），
     * 交给批次的 {@code SlugTitleMany} fetcher 一次批量查询解析。
     * fetcher 已经过滤掉归档 / 系统页面，因此查不到的条目天然等价于"不活跃"，
     * 不需要额外检查。</p>
     *
     * <p>{@code titleToSlug} 只按页面的<b>精确标题</b>建键——lite 投影里没有 aliases。
     * 这是个可接受的取舍：实测的断链形态是"slug 被弄花、display = 标题"，
     * 而不是"slug 被弄花、display = 别名"，所以按标题反查已经覆盖了大部分救援价值，
     * 存储成本却小得多。</p>
     */
    public static ResolvedLiveSlugs resolveLiveSlugs(WikiBatchContext batchCtx,
                                                     Set<String> candidates) {
        if (candidates == null || candidates.isEmpty() || batchCtx == null
                || batchCtx.getSlugTitleMany() == null) {
            return new ResolvedLiveSlugs(null, null);
        }
        List<String> slugList = new ArrayList<>(candidates);
        Map<String, String> titles = batchCtx.slugTitleMany(slugList);
        Set<String> live = new java.util.LinkedHashSet<>();
        Map<String, String> titleToSlug = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : titles.entrySet()) {
            live.add(e.getKey());
            if (e.getValue() != null && !e.getValue().isEmpty()) {
                titleToSlug.put(e.getValue(), e.getKey());
            }
        }
        return new ResolvedLiveSlugs(live, titleToSlug);
    }

    /** 活跃 slug 集合与精确标题反查表。 */
    public record ResolvedLiveSlugs(Set<String> liveSlugs, Map<String, String> titleToSlug) {}
}
