package com.ragagent.wiki.service.page;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ragagent.wiki.domain.WikiConstants;
import com.ragagent.wiki.domain.WikiPage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * wiki 链接维护协作者：出链解析、入链增删、全量重建与交叉链接注入，以及 wiki 链接文本工具
 * （slug 归一、行内 chunk 引句柄剥离）。
 *
 * <p>持有 {@link WikiPageServiceImpl} 回引以访问仓储与工具;本类不得独立实例化。</p>
 */
final class WikiPageLinkOps {

    private static final Logger log = LoggerFactory.getLogger(WikiPageLinkOps.class);

    private final WikiPageServiceImpl service;

    WikiPageLinkOps(WikiPageServiceImpl service) {
        this.service = service;
    }

    /** wiki 链接语法：{@code \[\[([^\]]+)\]\]} */
    static final Pattern WIKI_LINK_REGEX = Pattern.compile("\\[\\[([^\\]]+)\\]\\]");

    /**
     * 行内 chunk 引用句柄语法：
     * {@code [ \t]*\[c\d{3,}(?:\s*[,;]\s*c\d{3,})*\]}。
     *
     * <p>这些是 ingest 提示词在分类支撑 chunk 时产生的<b>内部短句柄</b>；稳定的来源
     * 关系存在 {@code WikiPage.ChunkRefs} 里，句柄对读者没有意义，绝不能泄漏进
     * 生成的 Markdown。</p>
     */
    static final Pattern WIKI_INLINE_CHUNK_CITATION_REGEX =
            Pattern.compile("[ \\t]*\\[c\\d{3,}(?:\\s*[,;]\\s*c\\d{3,})*\\]");

    /**
     * 从 markdown 正文提取
     * {@code [[wiki-link]]} 的 slug。
     *
     * <p>去重（保首次出现序）；支持 {@code [[slug|显示名]]} 只取竖线前那段；
     * slug 经 {@link #normalizeSlug} 归一。</p>
     */
    static List<String> parseOutLinks(String content) {
        if (content == null || content.isEmpty()) {
            return new ArrayList<>();
        }
        Matcher m = WIKI_LINK_REGEX.matcher(content);
        Set<String> seen = new LinkedHashSet<>();
        List<String> links = new ArrayList<>();
        while (m.find()) {
            String slug = m.group(1).trim();
            // 处理 [[slug|display name]]——slug 是竖线前那段
            int pipe = slug.indexOf('|');
            if (pipe >= 0) {
                slug = slug.substring(0, pipe).trim();
            }
            slug = normalizeSlug(slug);
            if (!slug.isEmpty() && seen.add(slug)) {
                links.add(slug);
            }
        }
        return links;
    }

    /**
     * 小写 + 去首尾空白 + 空格换成连字符。
     *
     * <p>小写化用 {@code Locale.ROOT}（Unicode 简单折叠），
     * 避免土耳其语 i 之类的区域陷阱（与仓库其它地方一致）。</p>
     */
    static String normalizeSlug(String slug) {
        if (slug == null) {
            return "";
        }
        return slug.trim().toLowerCase(java.util.Locale.ROOT).replace(" ", "-");
    }

    /**
     * 取 slug 第一个 {@code '/'} 之前的前缀，
     * 例如 {@code "summary/abc" -> "summary"}；没有 {@code '/'} 的 slug 映射为 {@code ""}。
     */
    static String slugNamespace(String slug) {
        if (slug == null) {
            return "";
        }
        int i = slug.indexOf('/');
        return i >= 0 ? slug.substring(0, i) : "";
    }

    static String stripWikiInlineChunkCitations(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        return WIKI_INLINE_CHUNK_CITATION_REGEX.matcher(content).replaceAll("");
    }

    static void stripWikiPageInlineChunkCitations(WikiPage page) {
        if (page == null) {
            return;
        }
        page.setContent(stripWikiInlineChunkCitations(page.getContent()));
        page.setSummary(stripWikiInlineChunkCitations(page.getSummary()));
    }

    /**
     * 把源 slug 加到目标页的 in_links。
     * 目标页不存在时静默跳过（还没建出来）。
     */
    void updateInLinks(String kbId, String sourceSlug, List<String> targets) {
        for (String targetSlug : targets) {
            WikiPage targetPage;
            try {
                targetPage = service.repo.getBySlug(kbId, targetSlug);
            } catch (RuntimeException e) {
                continue; // target page may not exist yet
            }
            if (!targetPage.getInLinks().contains(sourceSlug)) {
                targetPage.getInLinks().add(sourceSlug);
                targetPage.setUpdatedAt(OffsetDateTime.now());
                try {
                    service.repo.updateMeta(targetPage);
                } catch (RuntimeException e) {
                    log.warn("wiki: failed to update in_links for {}: {}", targetSlug, e.toString());
                }
            }
        }
    }

    void removeInLinks(String kbId, String sourceSlug, List<String> targets) {
        for (String targetSlug : targets) {
            WikiPage targetPage;
            try {
                targetPage = service.repo.getBySlug(kbId, targetSlug);
            } catch (RuntimeException e) {
                continue;
            }
            List<String> newInLinks = WikiPageServiceImpl.removeString(targetPage.getInLinks(), sourceSlug);
            if (newInLinks.size() != targetPage.getInLinks().size()) {
                targetPage.setInLinks(newInLinks);
                targetPage.setUpdatedAt(OffsetDateTime.now());
                try {
                    service.repo.updateMeta(targetPage);
                } catch (RuntimeException e) {
                    log.warn("wiki: failed to update in_links for {}: {}", targetSlug, e.toString());
                }
            }
        }
    }

    public void rebuildLinks(String kbId) {
        List<WikiPage> pages = service.repo.listAll(kbId);

        Map<String, WikiPage> pageMap = new LinkedHashMap<>();
        for (WikiPage p : pages) {
            pageMap.put(p.getSlug(), p);
        }

        // 先清空所有入链
        for (WikiPage p : pages) {
            p.setInLinks(new ArrayList<>());
        }

        // 重解析出链并重建入链
        for (WikiPage p : pages) {
            p.setOutLinks(parseOutLinks(p.getContent()));
            for (String target : p.getOutLinks()) {
                WikiPage tp = pageMap.get(target);
                if (tp != null) {
                    tp.getInLinks().add(p.getSlug());
                }
            }
        }

        // 全量保存（链接重建是纯元数据，不动版本号）
        for (WikiPage p : pages) {
            p.setUpdatedAt(OffsetDateTime.now());
            try {
                service.repo.updateMeta(p);
            } catch (RuntimeException e) {
                log.warn("wiki: failed to update links for page {}: {}", p.getSlug(), e.toString());
            }
        }
    }

    public void injectCrossLinks(String kbId, List<String> affectedSlugs) {
        List<WikiPage> allPages;
        try {
            allPages = service.listAllPages(kbId);
        } catch (RuntimeException e) {
            return;
        }
        if (allPages.size() < 2) {
            return;
        }

        List<WikiCrossLinker.LinkRef> refs = WikiCrossLinker.collectRefs(allPages);
        if (refs.isEmpty()) {
            return;
        }

        Set<String> affectedSet = new HashSet<>();
        if (affectedSlugs != null) {
            affectedSet.addAll(affectedSlugs);
        }

        WikiCrossLinker linker = service.crossLinker.getIfAvailable(WikiCrossLinker.Noop::new);
        int updated = 0;
        for (WikiPage p : allPages) {
            if (!affectedSet.contains(p.getSlug())) {
                continue;
            }
            if (WikiConstants.PAGE_TYPE_INDEX.equals(p.getPageType())) {
                continue;
            }

            WikiCrossLinker.LinkifyResult r = linker.linkify(p.getContent(), refs, p.getSlug());
            if (!r.changed()) {
                continue;
            }
            p.setContent(r.content());
            try {
                service.updateAutoLinkedContent(p);
            } catch (RuntimeException e) {
                log.warn("wiki: cross-link injection failed for {}: {}", p.getSlug(), e.toString());
                continue;
            }
            updated++;
        }

        if (updated > 0) {
            log.info("wiki: injected cross-links in {} pages", updated);
        }
    }
}
