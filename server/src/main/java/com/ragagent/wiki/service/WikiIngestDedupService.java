package com.ragagent.wiki.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ragagent.wiki.domain.WikiConstants;
import com.ragagent.wiki.domain.WikiPage;
import com.ragagent.wiki.domain.WikiPageLite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 实体/概念去重与身份收敛。
 *
 * <p>本类实现 {@link WikiDedupSupport} 端口，把 ingest 主入口依赖的五个 dedup 函数补齐，
 * 恢复「同类型同标题收敛到同一 slug」保证；另含 {@code reclaimExtractedIdentities} /
 * {@code remapSlugUpdatesByIdentity} 两个由批次主干直接调用的入口。</p>
 *
 * <h2>为什么需要预筛</h2>
 * <p>不预筛时，去重 prompt 会把整个 entity+concept 页面语料塞进
 * {@code <existing_pages>}。在 100+ 页的 KB 上这既膨胀输入 token，更糟的是
 * <b>给弱模型留出足够长的绳子</b>——它会把两个毫不相干的 slug 合并起来，
 * 只因为输出看起来合理。观测到的案例包括 "城镇登记失业人员" → "中华优秀传统文化"
 * （零共享字符）。</p>
 * <p>下面的过滤只保留与某个新条目共享至少一点<b>廉价表层信号</b>的页面。
 * 计算快、无外部调用，而且它只会<b>移除</b> prompt 的候选——下游的
 * {@code validMerge} 校验仍然守着最终写入。</p>
 * <p>例外说明:846 行略超 800——名称 bigram/配对得分/LLM 仲裁是一条
 * 不可中断的去重链,切段只会制造参数传递层。</p>
 */
@Service
public class WikiIngestDedupService implements WikiDedupSupport {

    private static final Logger log = LoggerFactory.getLogger(WikiIngestDedupService.class);

    private final WikiPageService wikiService;
    private final WikiIdentityClaimStore claimStore;

    public WikiIngestDedupService(WikiPageService wikiService, WikiIdentityClaimStore claimStore) {
        this.wikiService = wikiService;
        this.claimStore = claimStore;
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
    // 合并合法性校验
    // ═══════════════════════════════════════════════════════════════

    /**
     * 用确定性、
     * 模型无关的规则校验一次 LLM 提议的合并（srcSlug → dstSlug）。
     * <b>返回空串表示允许</b>，否则返回简短的人类可读拒绝原因。
     * {@code srcCandidates} 是为 srcSlug <b>自己</b>的相似度探测召回的既有页面 slug 集合。
     *
     * <p>逐条目作用域检查是关键护栏：去重 prompt 给模型看的是所有新条目的候选并集，
     * 弱模型会把某个条目与<b>只为另一个条目</b>召回的页面配成对（观测到：
     * entity/tencent-open → entity/hiring-agent，两者无任何 trigram 信号）。</p>
     */
    @Override
    public String dedupMergeRejectReason(String srcSlug, String dstSlug, Set<String> srcCandidates) {
        if (srcCandidates == null || !srcCandidates.contains(dstSlug)) {
            // 同时覆盖"纯幻觉目标"（不在任何候选集里）与"只与另一个条目相似的
            // 真实页面"。两种情况下这一对都缺少针对<b>本</b>条目的相似度信号，
            // 因此不是安全的合并。
            return "target is not a similarity candidate for this item";
        }
        int srcSlash = srcSlug == null ? -1 : srcSlug.indexOf('/');
        int dstSlash = dstSlug == null ? -1 : dstSlug.indexOf('/');
        if (srcSlash <= 0 || dstSlash <= 0) {
            // 带类型前缀的 slug 必须形如 "entity/foo" 或 "concept/bar"。
            // LLM 在这里吐出无前缀的 slug 就是在幻觉；直接拒绝，
            // 而不是掉进前缀相等检查（那会把两个空前缀当成匹配）。
            return "missing type prefix";
        }
        String srcPrefix = srcSlug.substring(0, srcSlash + 1);
        String dstPrefix = dstSlug.substring(0, dstSlash + 1);
        if (!srcPrefix.equals(dstPrefix)) {
            return "type mismatch: " + srcPrefix + " vs " + dstPrefix;
        }
        return "";
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

    // ═══════════════════════════════════════════════════════════════
    // 身份认领
    // ═══════════════════════════════════════════════════════════════

    /**
     * 在 Reduce 开始之前，
     * 为一个归一化的 (KB, 页面类型, 标题) 身份预留一个 slug。
     *
     * <h2>语义要点</h2>
     * <ul>
     *   <li>跨批次互斥经 {@link WikiIdentityClaimStore#claim}
     *       （默认进程内实现，见该接口的"多实例差异"警告）；</li>
     *   <li>存储抛异常时记 warn、退回批次局部 map（认领只在该批次内有效）。</li>
     * </ul>
     *
     * <p>只有来自"精确既有页解析"的认领才是权威的、可以覆盖临时认领；
     * 语义 LLM 合并<b>必须</b>用 SetNX 语义，以免把一个已被别的 worker 预留的标题劈开。</p>
     */
    public String claimWikiIdentitySlug(String kbId,
                                        String pageType,
                                        String title,
                                        String proposedSlug,
                                        boolean authoritative,
                                        WikiBatchContext batchCtx) {
        String identity = normalizeWikiIdentityTitle(title);
        String expectedPrefix = pageType + "/";
        if (identity.isEmpty() || proposedSlug == null || proposedSlug.isEmpty()
                || !proposedSlug.startsWith(expectedPrefix)) {
            return proposedSlug;
        }

        String claim = proposedSlug;
        boolean usedSharedStore = false;
        if (claimStore != null) {
            try {
                String existing = claimStore.claim(kbId, pageType, identity, proposedSlug,
                        authoritative, expectedPrefix);
                if (existing != null && existing.startsWith(expectedPrefix)) {
                    claim = existing;
                    usedSharedStore = true;
                }
            } catch (RuntimeException e) {
                log.warn("wiki ingest: identity claim failed for {}: {} (using batch-local claim)",
                        proposedSlug, e.getMessage());
            }
        }

        if (batchCtx != null) {
            String localKey = pageType + WikiBatchContext.CACHE_KEY_SEPARATOR + identity;
            if (authoritative || usedSharedStore) {
                // 共享存储答复了，它就是跨批次的真相来源。
                // 精确既有页命中同样要覆盖过期的本地值。
                batchCtx.identityClaims().put(localKey, claim);
            } else {
                String actual = batchCtx.identityClaims()
                        .putIfAbsent(localKey, claim);
                if (actual == null) {
                    actual = claim;
                }
                if (actual.startsWith(expectedPrefix)) {
                    claim = actual;
                }
            }
        }
        return claim;
    }

    // ═══════════════════════════════════════════════════════════════
    // 身份收敛（WikiDedupSupport）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 应用确定性
     * 既有页解析、跨批次身份认领，以及同结果归并。
     *
     * @param mergeTargets 抽取 slug → 语义/LLM 合并目标（<b>非</b>权威）
     * @param exactTargets 抽取 slug → 精确同名的既有页（<b>可</b>覆盖临时认领）
     */
    @Override
    public List<ExtractedItem> stabilizeExtractedIdentities(String kbId,
                                                            String pageType,
                                                            List<ExtractedItem> items,
                                                            Map<String, String> mergeTargets,
                                                            Map<String, String> exactTargets,
                                                            WikiBatchContext batchCtx) {
        List<ExtractedItem> out = new ArrayList<>(items == null ? 0 : items.size());
        Map<String, Integer> bySlug = new LinkedHashMap<>();
        Map<String, String> claimedByIdentity = new LinkedHashMap<>();
        if (items == null) {
            return out;
        }
        for (ExtractedItem item : items) {
            String originalSlug = item.getSlug();
            boolean authoritative = false;
            String exact = exactTargets == null ? null : exactTargets.get(originalSlug);
            String merged = mergeTargets == null ? null : mergeTargets.get(originalSlug);
            if (exact != null && !exact.isEmpty()) {
                item.setSlug(exact);
                authoritative = true;
            } else if (merged != null && !merged.isEmpty()) {
                item.setSlug(merged);
            }
            String identity = normalizeWikiIdentityTitle(item.getName());
            if (!authoritative) {
                String known = identity.isEmpty() ? null : claimedByIdentity.get(identity);
                if (known != null && !known.isEmpty()) {
                    item.setSlug(known);
                } else {
                    item.setSlug(claimWikiIdentitySlug(
                            kbId, pageType, item.getName(), item.getSlug(), false, batchCtx));
                    if (!identity.isEmpty()) {
                        claimedByIdentity.put(identity, item.getSlug());
                    }
                }
            } else {
                item.setSlug(claimWikiIdentitySlug(
                        kbId, pageType, item.getName(), item.getSlug(), true, batchCtx));
                if (!identity.isEmpty()) {
                    claimedByIdentity.put(identity, item.getSlug());
                }
            }
            Integer idx = bySlug.get(item.getSlug());
            if (idx != null) {
                out.set(idx, mergeExtractedIdentity(out.get(idx), item));
                continue;
            }
            bySlug.put(item.getSlug(), out.size());
            out.add(item);
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════════
    // 精确身份页的挂载与解析
    // ═══════════════════════════════════════════════════════════════

    /** 身份页缓存键 */
    static String identityPageCacheKey(String pageType, String identity) {
        return WikiBatchContext.identityPageCacheKey(pageType, identity);
    }

    /** 读取已缓存的精确身份页；未缓存返回 null */
    static List<WikiPageLite> loadCachedIdentityPages(WikiBatchContext batchCtx,
                                                      String pageType, String identity) {
        if (batchCtx == null) {
            return null;
        }
        String key = identityPageCacheKey(pageType, identity);
        // 注意：缓存值允许是空列表（"确认查无此页"），因此必须用 containsKey 判定，
        // 不能用 get(...) == null 判定。
        if (!batchCtx.identityPages().containsKey(key)) {
            return null;
        }
        return batchCtx.identityPages().get(key);
    }

    /** 写入精确身份页缓存 */
    static void storeCachedIdentityPages(WikiBatchContext batchCtx, String pageType,
                                         String identity, List<WikiPageLite> pages) {
        if (batchCtx == null) {
            return;
        }
        batchCtx.identityPages().put(identityPageCacheKey(pageType, identity),
                pages == null ? List.of() : pages);
    }

    /** 把精确身份页挂进候选集 */
    static void bindExactIdentityPages(List<String> itemSlugs,
                                       List<WikiPageLite> pages,
                                       String pageType,
                                       String identity,
                                       Map<String, WikiPageLite> candidatePages,
                                       Map<String, Set<String>> itemCandidates) {
        if (itemSlugs == null || itemSlugs.isEmpty() || pages == null || pages.isEmpty()) {
            return;
        }
        for (WikiPageLite p : pages) {
            if (p == null || p.getSlug().isEmpty() || !pageType.equals(p.getPageType())) {
                continue;
            }
            if (!normalizeWikiIdentityTitle(p.getTitle()).equals(identity)) {
                continue;
            }
            candidatePages.putIfAbsent(p.getSlug(), p);
            for (String slug : itemSlugs) {
                itemCandidates.computeIfAbsent(slug, k -> new LinkedHashSet<>()).add(p.getSlug());
            }
        }
    }

    /**
     * 按归一化标题做一次
     * <b>批量</b>精确查找，把命中的既有页补进候选集，让精确同名无论如何都进入候选。
     */
    @Override
    public void attachExactIdentityPages(String kbId,
                                         String pageType,
                                         List<ExtractedItem> items,
                                         Map<String, WikiPageLite> candidatePages,
                                         Map<String, Set<String>> itemCandidates,
                                         WikiBatchContext batchCtx) {
        if (wikiService == null || items == null || items.isEmpty()) {
            return;
        }

        Map<String, List<String>> slugsByIdentity = new LinkedHashMap<>();
        for (ExtractedItem item : items) {
            String identity = normalizeWikiIdentityTitle(item.getName());
            if (identity.isEmpty() || item.getSlug().isEmpty()) {
                continue;
            }
            slugsByIdentity.computeIfAbsent(identity, k -> new ArrayList<>()).add(item.getSlug());
        }
        if (slugsByIdentity.isEmpty()) {
            return;
        }

        Map<String, List<WikiPageLite>> cached = new LinkedHashMap<>();
        List<String> miss = new ArrayList<>();
        for (String identity : slugsByIdentity.keySet()) {
            List<WikiPageLite> pages = loadCachedIdentityPages(batchCtx, pageType, identity);
            if (pages != null) {
                cached.put(identity, pages);
                continue;
            }
            miss.add(identity);
        }

        if (!miss.isEmpty()) {
            List<WikiPageLite> pages;
            try {
                pages = wikiService.findPagesByNormalizedTitles(kbId, pageType, miss);
            } catch (Exception e) {
                log.warn("wiki ingest: exact identity lookup failed for {} ({} titles): {}",
                        pageType, miss.size(), e.getMessage());
                pages = null;
            }
            if (pages != null) {
                Map<String, List<WikiPageLite>> byIdentity = new LinkedHashMap<>();
                for (WikiPageLite p : pages) {
                    if (p == null) {
                        continue;
                    }
                    String identity = normalizeWikiIdentityTitle(p.getTitle());
                    if (identity.isEmpty()) {
                        continue;
                    }
                    byIdentity.computeIfAbsent(identity, k -> new ArrayList<>()).add(p);
                }
                for (String identity : miss) {
                    List<WikiPageLite> hits = byIdentity.get(identity);
                    if (hits == null) {
                        hits = List.of();
                    }
                    cached.put(identity, hits);
                    storeCachedIdentityPages(batchCtx, pageType, identity, hits);
                }
            }
        }

        for (Map.Entry<String, List<String>> e : slugsByIdentity.entrySet()) {
            bindExactIdentityPages(e.getValue(), cached.get(e.getKey()), pageType,
                    e.getKey(), candidatePages, itemCandidates);
        }
    }

    /**
     * 为每个条目确定
     * 精确同名的既有页目标。
     */
    @Override
    public void collectExactIdentityTargets(List<ExtractedItem> items,
                                            String pageType,
                                            Map<String, Set<String>> itemCandidates,
                                            Map<String, WikiPageLite> candidatePages,
                                            Map<String, String> exactTargets) {
        if (items == null) {
            return;
        }
        for (ExtractedItem item : items) {
            String target = exactIdentityTarget(item, pageType,
                    itemCandidates == null ? null : itemCandidates.get(item.getSlug()),
                    candidatePages);
            if (!target.isEmpty()) {
                exactTargets.put(item.getSlug(), target);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 引用遍之后的重认领与批次级重映射
    // ═══════════════════════════════════════════════════════════════

    /**
     * 在引用发现之后
     * <b>重跑</b>精确标题查找 + 身份认领。引用遍的 new_slugs 跳过了抽取期的去重，
     * 没有这一步它们会物化出第二个同标题页面。
     *
     * @return 收敛后的 {@code (entities, concepts)}
     */
    public Identities reclaimExtractedIdentities(String kbId,
                                                 List<ExtractedItem> entities,
                                                 List<ExtractedItem> concepts,
                                                 WikiBatchContext batchCtx) {
        if ((entities == null || entities.isEmpty()) && (concepts == null || concepts.isEmpty())) {
            return new Identities(entities, concepts);
        }
        Map<String, WikiPageLite> candidatePages = new LinkedHashMap<>();
        Map<String, Set<String>> itemCandidates = new LinkedHashMap<>();
        attachExactIdentityPages(kbId, WikiConstants.PAGE_TYPE_ENTITY,
                entities == null ? List.of() : entities, candidatePages, itemCandidates, batchCtx);
        attachExactIdentityPages(kbId, WikiConstants.PAGE_TYPE_CONCEPT,
                concepts == null ? List.of() : concepts, candidatePages, itemCandidates, batchCtx);
        Map<String, String> exactTargets = new LinkedHashMap<>();
        collectExactIdentityTargets(entities, WikiConstants.PAGE_TYPE_ENTITY,
                itemCandidates, candidatePages, exactTargets);
        collectExactIdentityTargets(concepts, WikiConstants.PAGE_TYPE_CONCEPT,
                itemCandidates, candidatePages, exactTargets);
        return new Identities(
                stabilizeExtractedIdentities(kbId, WikiConstants.PAGE_TYPE_ENTITY,
                        entities, null, exactTargets, batchCtx),
                stabilizeExtractedIdentities(kbId, WikiConstants.PAGE_TYPE_CONCEPT,
                        concepts, null, exactTargets, batchCtx));
    }

    /** 收敛后的 entities 与 concepts */
    public record Identities(List<ExtractedItem> entities, List<ExtractedItem> concepts) { }

    /**
     * 每个 map worker
     * 都选完 slug 之后<b>重读</b>身份认领，让同一个标题的并发罗马化在 Reduce 按 slug
     * 分组与加锁之前收敛。summary / retract 更新保留原有 slug。
     */
    public Map<String, List<SlugUpdate>> remapSlugUpdatesByIdentity(String kbId,
                                                                   Map<String, List<SlugUpdate>> slugUpdates,
                                                                   WikiBatchContext batchCtx) {
        if (slugUpdates == null || slugUpdates.isEmpty()) {
            return slugUpdates;
        }
        Map<String, List<SlugUpdate>> out = new LinkedHashMap<>();
        Map<String, String> claimedByIdentity = new LinkedHashMap<>();
        for (List<SlugUpdate> updates : slugUpdates.values()) {
            if (updates == null) {
                continue;
            }
            for (SlugUpdate u : updates) {
                if (SlugUpdate.TYPE_ENTITY.equals(u.getType())
                        || SlugUpdate.TYPE_CONCEPT.equals(u.getType())) {
                    String title = u.getItem() == null ? "" : u.getItem().getName();
                    if (title.isEmpty()) {
                        title = u.getSlug();
                    }
                    String identity = normalizeWikiIdentityTitle(title);
                    String claimed = "";
                    if (!identity.isEmpty()) {
                        String known = claimedByIdentity.get(
                                u.getType() + WikiBatchContext.CACHE_KEY_SEPARATOR + identity);
                        claimed = known == null ? "" : known;
                    }
                    if (claimed.isEmpty()) {
                        claimed = claimWikiIdentitySlug(kbId, u.getType(), title,
                                u.getSlug(), false, batchCtx);
                        if (!identity.isEmpty() && !claimed.isEmpty()) {
                            claimedByIdentity.put(
                                    u.getType() + WikiBatchContext.CACHE_KEY_SEPARATOR + identity,
                                    claimed);
                        }
                    }
                    if (!claimed.isEmpty()) {
                        u.setSlug(claimed);
                        if (u.getItem() != null) {
                            u.getItem().setSlug(claimed);
                        }
                    }
                }
                out.computeIfAbsent(u.getSlug(), k -> new ArrayList<>()).add(u);
            }
        }
        return out;
    }
}
