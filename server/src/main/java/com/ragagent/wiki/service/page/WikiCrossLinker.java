package com.ragagent.wiki.service.page;

import java.util.ArrayList;
import java.util.List;

import com.ragagent.wiki.domain.WikiConstants;
import com.ragagent.wiki.domain.WikiPage;

/**
 * 交叉链接注入的<b>可插拔端口</b>。
 *
 * <p>把「纯文本注入」抽成这个端口：
 * {@link WikiPageService#injectCrossLinks} 负责编排（取全量页面、挑出
 * 受影响页、跳过 index 页、改完调用仓储的自动链接内容更新），
 * 实际的正文链接化由本端口的实现提供。</p>
 *
 * <p><b>默认实现是 {@link Noop}（什么都不做）</b>：接入真实现时，
 * 实现本接口并注册为 bean 即可自动生效，无需改动本服务层。
 * 在此之前 {@code injectCrossLinks} 是安全的空操作（不会破坏正文）。</p>
 *
 * <p>{@code collectRefs} 因为只是对 {@code WikiPage} 字段的平凡投影
 * （title + aliases，跳过 index 页），直接给出静态实现，不占用端口。</p>
 */
public interface WikiCrossLinker {

    /** 一个 (slug, 匹配文本) 候选。 */
    record LinkRef(String slug, String matchText) {}

    /** 注入结果：新正文 + 是否有改动 */
    record LinkifyResult(String content, boolean changed) {}

    /**
     * 为每个 ref 注入
     * 至多一处 {@code [[slug|matchText]]}，跳过代码块/既有链接内部的出现，
     * ASCII 字母开头的 matchText 还要求词边界。
     */
    LinkifyResult linkify(String content, List<LinkRef> refs, String selfSlug);

    /**
     * 把所有非系统页面的
     * title + aliases 摊平成一个 linkRef 列表。index 页跳过；空 title / 空别名跳过。
     *
     * <p>这是纯投影，直接给出默认实现。</p>
     */
    static List<LinkRef> collectRefs(List<WikiPage> pages) {
        if (pages == null || pages.isEmpty()) {
            return List.of();
        }
        List<LinkRef> refs = new ArrayList<>(pages.size() * 2);
        for (WikiPage p : pages) {
            if (p == null) {
                continue;
            }
            if (WikiConstants.PAGE_TYPE_INDEX.equals(p.getPageType())) {
                continue;
            }
            if (!p.getTitle().isEmpty()) {
                refs.add(new LinkRef(p.getSlug(), p.getTitle()));
            }
            for (String alias : p.getAliases()) {
                if (alias != null && !alias.isEmpty()) {
                    refs.add(new LinkRef(p.getSlug(), alias));
                }
            }
        }
        return refs;
    }

    /**
     * 默认实现：不做任何改写，{@code injectCrossLinks} 保持为无害空操作。
     */
    final class Noop implements WikiCrossLinker {
        @Override
        public LinkifyResult linkify(String content, List<LinkRef> refs, String selfSlug) {
            return new LinkifyResult(content, false);
        }
    }
}
