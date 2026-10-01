package com.ragagent.wiki.service.ingest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ragagent.common.wiki.ExtractedItem;
import com.ragagent.common.wiki.GoStrings;
import com.ragagent.wiki.domain.WikiConstants;
import com.ragagent.wiki.domain.WikiPage;
import com.ragagent.wiki.domain.WikiPageLite;
import com.ragagent.wiki.service.page.SlugFuzzy;

/**
 * 实体/概念去重的纯算法：候选页预筛与配对打分，身份标题归一、精确同名解析与身份合并。
 *
 * <p>无状态、无依赖——带状态的编排（身份认领、收敛、精确页挂载、批次重映射）在
 * {@link WikiIngestDedupService}。</p>
 */
final class WikiIdentityDedup {

    private WikiIdentityDedup() {
    }

    // ═══════════════════════════════════════════════════════════════
    // 预筛
    // ═══════════════════════════════════════════════════════════════

    /**
     * 一次
     * （新条目, 既有页面）比较的<b>预计算</b>相似度特征集。
     */
    public static final class DedupSurface {
        /** slug 基段（"/" 之后）的 kebab 分词 */
        final Set<String> slugTokens;

        /**
         * 每个表层形式（name 与每个 alias）一个
         * 字符 bigram 集合。分开保存是为了让配对得分取<b>各表层形式的最大值</b>
         * ——一个冷门 alias 命中不该被主名称的不一致稀释掉。
         */
        final List<Set<String>> nameGramSets;

        public DedupSurface(Set<String> slugTokens, List<Set<String>> nameGramSets) {
            this.slugTokens = slugTokens == null ? Set.of() : slugTokens;
            this.nameGramSets = nameGramSets == null ? List.of() : nameGramSets;
        }
    }

    /**
     * 给定页面里有多少是
     * entity/concept 类型。只用于记录预筛的压缩比。
     */
    public static int countEntityConceptPages(List<WikiPage> pages) {
        int n = 0;
        if (pages == null) {
            return 0;
        }
        for (WikiPage p : pages) {
            if (p == null) {
                continue;
            }
            if (WikiConstants.PAGE_TYPE_ENTITY.equals(p.getPageType())
                    || WikiConstants.PAGE_TYPE_CONCEPT.equals(p.getPageType())) {
                n++;
            }
        }
        return n;
    }

    /**
     * 返回 {@code allPages}
     * 中<b>至少与一个</b> {@code newItems} 貌似相关的子集。非 entity/concept 页面被
     * 无条件丢弃。返回的切片<b>保留输入顺序</b>，让下游 prompt 跨运行稳定。
     *
     * <p>小语料（{@code <= DEDUP_SMALL_CORPUS_BYPASS}）上，除了页面类型过滤之外
     * 本方法是 no-op。</p>
     */
    public static List<WikiPage> selectDedupCandidatePages(List<ExtractedItem> newItems,
                                                           List<WikiPage> allPages) {
        List<WikiPage> pages = new ArrayList<>();
        if (allPages != null) {
            for (WikiPage p : allPages) {
                if (p == null) {
                    continue;
                }
                if (!WikiConstants.PAGE_TYPE_ENTITY.equals(p.getPageType())
                        && !WikiConstants.PAGE_TYPE_CONCEPT.equals(p.getPageType())) {
                    continue;
                }
                pages.add(p);
            }
        }
        if (pages.isEmpty()) {
            return pages;
        }
        if (newItems == null || newItems.isEmpty()
                || pages.size() <= WikiBatchConstants.DEDUP_SMALL_CORPUS_BYPASS) {
            return pages;
        }

        List<DedupSurface> pageFeats = new ArrayList<>(pages.size());
        for (WikiPage p : pages) {
            List<String> surfaces = new ArrayList<>(1 + p.getAliases().size());
            surfaces.add(p.getTitle());
            surfaces.addAll(p.getAliases());
            pageFeats.add(new DedupSurface(slugBaseTokens(p.getSlug()), gramsPerSurface(surfaces)));
        }

        Set<Integer> selected = new LinkedHashSet<>();
        for (ExtractedItem it : newItems) {
            List<String> surfaces = new ArrayList<>(1 + it.getAliases().size());
            surfaces.add(it.getName());
            surfaces.addAll(it.getAliases());
            DedupSurface itemFeat = new DedupSurface(slugBaseTokens(it.getSlug()),
                    gramsPerSurface(surfaces));
            if (itemFeat.slugTokens.isEmpty() && itemFeat.nameGramSets.isEmpty()) {
                continue;
            }

            record Scored(int idx, double score) { }
            List<Scored> scores = new ArrayList<>(pageFeats.size());
            for (int i = 0; i < pageFeats.size(); i++) {
                scores.add(new Scored(i, dedupPairScore(itemFeat, pageFeats.get(i))));
            }
            // 稳定排序：并列时按原始下标确定性地破平
            scores.sort(Comparator.comparingDouble(Scored::score).reversed());

            int topKRemaining = WikiBatchConstants.DEDUP_CANDIDATE_TOP_K;
            for (Scored s : scores) {
                if (s.score() >= WikiBatchConstants.DEDUP_CANDIDATE_SCORE_FLOOR) {
                    selected.add(s.idx());
                    continue;
                }
                // 低于下限，但仍欠 LLM 一些候选，好让它能干净地拒绝——
                // 用得分最高的剩余页面填满 top-K 预算，前提是得分不严格为 0
                // （0 分意味着与该页面毫无共同点，把它塞进去只会招来幻觉）。
                if (topKRemaining > 0 && s.score() > 0) {
                    selected.add(s.idx());
                    topKRemaining--;
                    continue;
                }
                break;
            }
        }

        List<WikiPage> out = new ArrayList<>(selected.size());
        for (int i = 0; i < pages.size(); i++) {
            if (selected.contains(i)) {
                out.add(pages.get(i));
            }
        }
        return out;
    }

    /**
     * a 的任意表层形式与 b 的任意
     * 表层形式之间的<b>最大</b>相似度（外加 slug 分词相似度）。slug 与 name 信号住在
     * 不同的符号空间（ASCII 拼音 vs 原始表层形式），因此取最大值而不是平均值。
     */
    public static double dedupPairScore(DedupSurface a, DedupSurface b) {
        double best = SlugFuzzy.jaccard(a.slugTokens, b.slugTokens);
        for (Set<String> ag : a.nameGramSets) {
            for (Set<String> bg : b.nameGramSets) {
                double v = SlugFuzzy.jaccard(ag, bg);
                if (v > best) {
                    best = v;
                }
            }
        }
        return best;
    }

    /**
     * slug 基段的 kebab 分词。
     * {@code "entity/beijing-nongshang-yinxing"} → {@code {beijing, nongshang, yinxing}}。
     */
    public static Set<String> slugBaseTokens(String slug) {
        if (slug == null || slug.isEmpty()) {
            return Set.of();
        }
        String base = slug;
        int i = slug.indexOf('/');
        if (i >= 0) {
            base = slug.substring(i + 1);
        }
        base = base.toLowerCase(java.util.Locale.ROOT);
        Set<String> out = new LinkedHashSet<>();
        StringBuilder token = new StringBuilder();
        for (int k = 0; k < base.length(); ) {
            int cp = base.codePointAt(k);
            k += Character.charCount(cp);
            // 分隔符是 - _ . 与 unicode 空白
            boolean sep = cp == '-' || cp == '_' || cp == '.' || GoStrings.isSpace(cp);
            if (sep) {
                if (token.length() > 0) {
                    out.add(token.toString());
                    token.setLength(0);
                }
                continue;
            }
            token.appendCodePoint(cp);
        }
        if (token.length() > 0) {
            out.add(token.toString());
        }
        return out.isEmpty() ? Set.of() : out;
    }

    /**
     * 为每个非空表层形式算一个
     * gram 集合。
     */
    public static List<Set<String>> gramsPerSurface(List<String> surfaces) {
        List<Set<String>> out = new ArrayList<>();
        if (surfaces == null) {
            return out;
        }
        for (String s : surfaces) {
            Set<String> g = surfaceGrams(s);
            if (!g.isEmpty()) {
                out.add(g);
            }
        }
        return out;
    }

    /**
     * 表层形式转小写、剥掉非字母/数字
     * 后得到的<b>字符 bigram</b> 集合。
     *
     * <p>bigram 在 CJK（每个 bigram 近似一个词）与拉丁（能抓住 {@code corporation}
     * ↔ {@code corp} 这样的词干重叠）两种文字上都表现良好。单字符退化成 1-gram，
     * 让它仍然贡献信号。</p>
     */
    public static Set<String> surfaceGrams(String s) {
        if (s == null || s.isEmpty()) {
            return Set.of();
        }
        StringBuilder b = new StringBuilder(s.length());
        String lower = s.toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < lower.length(); ) {
            int cp = lower.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetter(cp) || Character.isDigit(cp)) {
                b.appendCodePoint(cp);
            }
        }
        int[] cps = b.toString().codePoints().toArray();
        if (cps.length == 0) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        if (cps.length == 1) {
            out.add(new String(cps, 0, 1));
            return out;
        }
        for (int i = 0; i < cps.length - 1; i++) {
            out.add(new String(cps, i, 2));
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════════
    // 身份归一化与精确同名解析
    // ═══════════════════════════════════════════════════════════════

    /**
     * 只用于防止
     * "同类型同标题"被建到不同 slug 上的保守身份键。
     *
     * <p>它<b>刻意保留标点</b>：{@code "寓言"} 与 {@code "《寓言》"} 可能分别代表一个
     * 概念与一部作品/篇章，必须保持可区分。去掉空白 + 折叠大小写足以关掉模型格式漂移
     * （{@code "Acme Corp"} vs {@code "acme  corp"}）。</p>
     */
    public static String normalizeWikiIdentityTitle(String title) {
        if (title == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(title.length());
        String trimmed = GoStrings.trimSpace(title);
        for (int i = 0; i < trimmed.length(); ) {
            int cp = trimmed.codePointAt(i);
            i += Character.charCount(cp);
            if (GoStrings.isSpace(cp)) {
                continue;
            }
            out.appendCodePoint(Character.toLowerCase(cp));
        }
        return out.toString();
    }

    /**
     * 当某个同类型候选的
     * <b>归一化显示标题完全相等</b>时，返回该条目对应的稳定既有页面。
     *
     * <p>语义/别名匹配仍由 LLM 负责；这条确定性快路径只覆盖那个无歧义的身份不变量：
     * 同一页面类型不应存在两个可见标题相同的页面。</p>
     */
    public static String exactIdentityTarget(ExtractedItem item,
                                             String pageType,
                                             Set<String> candidates,
                                             Map<String, WikiPageLite> pages) {
        String identity = normalizeWikiIdentityTitle(item.getName());
        if (identity.isEmpty()) {
            return "";
        }
        List<String> matches = new ArrayList<>(2);
        if (candidates != null) {
            for (String slug : candidates) {
                WikiPageLite page = pages == null ? null : pages.get(slug);
                if (page == null || !pageType.equals(page.getPageType())) {
                    continue;
                }
                if (normalizeWikiIdentityTitle(page.getTitle()).equals(identity)) {
                    matches.add(page.getSlug());
                }
            }
        }
        if (matches.isEmpty()) {
            return "";
        }
        for (String slug : matches) {
            if (slug.equals(item.getSlug())) {
                return slug;
            }
        }
        matches.sort(GoStrings::compareByCodePoints);
        return matches.get(0);
    }

    /**
     * 两个名字
     * 折叠到同一身份时保留<b>更紧凑</b>的显示形式（{@code "孔子"} 优于 {@code "孔 子"}），
     * 并返回被丢弃的形式以便记成别名。
     */
    public static DisplayName preferWikiIdentityDisplayName(String dst, String src) {
        if (dst == null || dst.isEmpty()) {
            return new DisplayName(src == null ? "" : src, "");
        }
        if (src == null || src.isEmpty() || src.equals(dst)) {
            return new DisplayName(dst, "");
        }
        if (normalizeWikiIdentityTitle(dst).equals(normalizeWikiIdentityTitle(src))) {
            int srcLen = src.codePointCount(0, src.length());
            int dstLen = dst.codePointCount(0, dst.length());
            if (srcLen < dstLen) {
                return new DisplayName(src, dst);
            }
            return new DisplayName(dst, src);
        }
        return new DisplayName(dst, src);
    }

    /** 保留的显示名 + 被丢弃的形式（记成别名） */
    public record DisplayName(String name, String extraAlias) { }

    /**
     * 把收敛到同一 slug 的
     * 重复候选折叠起来。它保留每一条 alias / chunk 引用，并保留更丰富的回落文本，
     * 因此收敛永远不会在引用/reduce 阶段之前丢掉证据。
     */
    public static ExtractedItem mergeExtractedIdentity(ExtractedItem dst, ExtractedItem src) {
        DisplayName preferred = preferWikiIdentityDisplayName(dst.getName(), src.getName());
        dst.setName(preferred.name());
        dst.setAliases(appendUniqueString(new ArrayList<>(dst.getAliases()), preferred.extraAlias()));
        for (String alias : src.getAliases()) {
            dst.setAliases(appendUniqueString(new ArrayList<>(dst.getAliases()), alias));
        }
        if (src.getDescription().codePointCount(0, src.getDescription().length())
                > dst.getDescription().codePointCount(0, dst.getDescription().length())) {
            dst.setDescription(src.getDescription());
        }
        if (src.getDetails().codePointCount(0, src.getDetails().length())
                > dst.getDetails().codePointCount(0, dst.getDetails().length())) {
            dst.setDetails(src.getDetails());
        }
        List<String> chunks = new ArrayList<>(dst.sourceChunksOrEmpty());
        for (String chunkId : src.sourceChunksOrEmpty()) {
            chunks = appendUniqueString(chunks, chunkId);
        }
        dst.setSourceChunks(chunks);
        return dst;
    }

    /**
     * 去空白后非空、且尚未
     * 出现过才追加。
     */
    public static List<String> appendUniqueString(List<String> values, String value) {
        List<String> out = values == null ? new ArrayList<>() : values;
        String v = GoStrings.trimSpace(value);
        if (v.isEmpty()) {
            return out;
        }
        if (out.contains(v)) {
            return out;
        }
        out.add(v);
        return out;
    }
}
