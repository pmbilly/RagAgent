package com.ragagent.wiki.service.page;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.ragagent.wiki.domain.WikiPageLite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * wiki 链接修复协作者:正文内链死链检测与重写(RE2 语义见 WikiTextUtils)。
 *
 * <p>持有 {@link WikiPageServiceImpl} 回引以访问仓储与工具;本类不得独立实例化。</p>
 */
final class WikiPageLinkRepair {

    private static final Logger log = LoggerFactory.getLogger(WikiPageLinkRepair.class);

    private final WikiPageServiceImpl service;

    WikiPageLinkRepair(WikiPageServiceImpl service) {
        this.service = service;
    }

    /**
     * 对照 Go {@code RepairContentLinks}（L1121-1200）：把 {@code content} 里指向
     * <b>不存在的页面</b>、但几乎肯定是真实页面被弄花形式的 {@code [[slug]]} /
     * {@code [[slug|display]]} 引用重写掉。
     *
     * <p>典型场景：LLM 重打了一遍摘要页的 UUID slug 并插入/漏掉一个十六进制位
     * （{@code summary/…06fb5d5b5b5e → summary/…06fb14d5b14b14e}），产生一个 404
     * 且永远不可能被精确查回的死链。</p>
     *
     * <p>与 {@code stripDeadWikiLinks}（ingest 清理通道）不同，本方法是
     * <b>只重写</b>的：只有当存在高置信的活跃候选时才纠正死链，否则原样保留。
     * 它<b>绝不</b>把链接剥成纯文本，所以对任何写入路径都安全——包括目标确实
     * 还不存在的写入（那些就原样留着，等到目标出现为止）。</p>
     *
     * <p>每条死链的候选池被限定在<b>同一命名空间前缀</b>的活跃 slug（死的
     * {@code summary/<uuid>} 只会在活跃的 {@code summary/*} 里解析）。限定命名空间
     * 让 bigram 相似度这根杠杆保持安全：同命名空间内互不相同的高熵 UUID 不会碰撞，
     * 而错一位的变形与它真正的来源仍稳稳高于阈值。</p>
     */
    public WikiPageService.RepairResult repairContentLinks(String kbId, String selfSlug, String content) {
        if (content == null || content.trim().isEmpty()) {
            return new WikiPageService.RepairResult(content, false);
        }
        List<String> outLinks = WikiPageLinkOps.parseOutLinks(content);
        if (outLinks.isEmpty()) {
            return new WikiPageService.RepairResult(content, false);
        }
        Map<String, Boolean> existMap = service.repo.existsSlugs(kbId, outLinks);
        Set<String> deadPrefixes = new LinkedHashSet<>();
        for (String l : outLinks) {
            if (l.equals(selfSlug) || Boolean.TRUE.equals(existMap.get(l))) {
                continue;
            }
            deadPrefixes.add(WikiPageLinkOps.slugNamespace(l));
        }
        if (deadPrefixes.isEmpty()) {
            return new WikiPageService.RepairResult(content, false);
        }
        List<String> allSlugs = service.repo.listAllSlugs(kbId);
        Map<String, Set<String>> liveByPrefix = new LinkedHashMap<>();
        List<String> candidateSlugs = new ArrayList<>();
        for (String sl : allSlugs) {
            String ns = WikiPageLinkOps.slugNamespace(sl);
            if (!deadPrefixes.contains(ns)) {
                continue;
            }
            liveByPrefix.computeIfAbsent(ns, k -> new LinkedHashSet<>()).add(sl);
            candidateSlugs.add(sl);
        }
        if (candidateSlugs.isEmpty()) {
            return new WikiPageService.RepairResult(content, false);
        }
        // 为候选页建立 title -> slug 反查，好让 display 文本这根杠杆
        // （最安全、最精确的一根）能生效。范围限定在相关命名空间，所以在
        // 大 KB 上依然廉价。
        Map<String, String> titleToSlug = new LinkedHashMap<>();
        try {
            Map<String, WikiPageLite> lites = service.repo.listBySlugs(kbId, candidateSlugs);
            for (WikiPageLite lp : lites.values()) {
                if (lp != null && !lp.getTitle().isEmpty()) {
                    titleToSlug.put(lp.getTitle(), lp.getSlug());
                }
            }
        } catch (RuntimeException ignored) {
            // 对照 Go：`if lites, lerr := ...; lerr == nil { ... }`——反查失败只是
            // 少一根杠杆，不阻断修复
        }
        Map<String, String> resolveCache = new LinkedHashMap<>();
        SlugFuzzy.RewriteResult r = SlugFuzzy.rewriteDeadWikiLinks(content, (norm, display) -> {
            if (norm.equals(selfSlug) || Boolean.TRUE.equals(existMap.get(norm))) {
                return null;
            }
            String key = norm + "\0" + display;
            if (resolveCache.containsKey(key)) {
                String cached = resolveCache.get(key);
                return cached.isEmpty() ? null : cached;
            }
            String resolved = SlugFuzzy.resolveDeadSlug(norm, display,
                    liveByPrefix.getOrDefault(WikiPageLinkOps.slugNamespace(norm), Set.of()), titleToSlug);
            if (resolved == null || resolved.equals(norm)) {
                resolveCache.put(key, "");
                return null;
            }
            resolveCache.put(key, resolved);
            return resolved;
        });
        return new WikiPageService.RepairResult(r.content(), r.changed());
    }
}
