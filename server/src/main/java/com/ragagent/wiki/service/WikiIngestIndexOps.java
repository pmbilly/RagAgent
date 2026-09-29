package com.ragagent.wiki.service;




import java.util.ArrayList;




import java.util.LinkedHashSet;

import java.util.List;

import java.util.Map;

import java.util.Set;













import com.ragagent.llm.LlmChatClient;







import com.ragagent.wiki.domain.WikiConstants;

import com.ragagent.wiki.domain.WikiIndexEntry;

import com.ragagent.wiki.domain.WikiPage;





import com.ragagent.wiki.prompt.WikiPrompts;


import org.slf4j.Logger;

import org.slf4j.LoggerFactory;





/**

 * 索引页协作者:既有 taxonomy 格式化、既有 slug 收集、索引导语页重建与草稿发布。

 *

 * <p>持有 {@link WikiIngestService} 回引以访问其依赖与队列原语;本类不得独立实例化。</p>

 */

final class WikiIngestIndexOps {



    private static final Logger log = LoggerFactory.getLogger(WikiIngestIndexOps.class);



    private final WikiIngestService service;



    WikiIngestIndexOps(WikiIngestService service) {

        this.service = service;

    }



    /**

     * 对照 Go {@code formatExistingTaxonomyForPrompt}（L1966-1987）：把去重后的

     * category_path 列表渲染成缩进的目录树，供抽取 prompt 使用。

     *

     * <p>同级标签按<b>字符串升序</b>输出（对照 Go 的 {@code sort.Strings(keys)}）

     * ——Go 的 map 迭代是随机的，所以它显式排序；Java 侧照抄，否则 prompt 的字节

     * 前缀会随批次抖动，provider 前缀缓存会失效。</p>

     *

     * @return 空树时返回 ""（对照 Go 的 {@code return ""}）

     */

    public static String formatExistingTaxonomyForPrompt(List<List<String>> paths) {

        if (paths == null || paths.isEmpty()) {

            return "";

        }

        TaxonomyNode root = new TaxonomyNode();

        for (List<String> path : paths) {

            insertWikiTaxonomyPath(root, path);

        }

        if (root.children.isEmpty()) {

            return "";

        }

        StringBuilder buf = new StringBuilder();

        // 对照 Go 的 sort.Strings：**字节序**（对 UTF-8 等价于码点序）。

        // 不能用 Collections.sort 的 UTF-16 码元序——顺序会直接影响 prompt 字节，

        // 进而决定 provider 前缀缓存是否命中。

        List<String> keys = new ArrayList<>(root.children.keySet());

        keys.sort(GoStrings::compareByCodePoints);

        for (String k : keys) {

            appendWikiTaxonomyNode(buf, k, root.children.get(k), 0);

        }

        return GoStrings.trimSpace(buf.toString());

    }



    /** 对照 Go {@code insertWikiTaxonomyPath}（L1925-1945） */

    static void insertWikiTaxonomyPath(TaxonomyNode root, List<String> path) {

        if (root == null || path == null || path.isEmpty()) {

            return;

        }

        TaxonomyNode cur = root;

        for (String raw : path) {

            String part = GoStrings.trimSpace(raw == null ? "" : raw);

            if (part.isEmpty()) {

                continue;

            }

            cur = cur.children.computeIfAbsent(part, k -> new TaxonomyNode());

        }

    }



    /** 对照 Go {@code appendWikiTaxonomyNode}（L1947-1962）：每层两个空格缩进。 */

    static void appendWikiTaxonomyNode(StringBuilder buf, String label,

                                               TaxonomyNode node, int depth) {

        if (label != null && !label.isEmpty()) {

            buf.append("  ".repeat(Math.max(0, depth))).append(label).append('\n');

        }

        if (node == null || node.children.isEmpty()) {

            return;

        }

        // TreeMap 已保证升序；照 Go 的 sort.Strings 语义

        for (Map.Entry<String, TaxonomyNode> e : node.children.entrySet()) {

            appendWikiTaxonomyNode(buf, e.getKey(), e.getValue(), depth + 1);

        }

    }



    /**

     * 对照 Go {@code getExistingPageSlugsForKnowledge}（L2004-2023）：返回当前在

     * {@code source_refs} 里引用了给定 knowledge id 的全部页面 slug。

     * 重新摄取前用它快照状态，好让 reduce 阶段调和"新增 vs 撤回"。

     *

     * <p>系统页（{@code index}）显式跳过——纵深防御：一个老版本有 bug 的摄取若曾

     * 误把知识引用盖到系统页上，那些 slug 会出现在重解析的"旧集合"里并搅乱 reduce。</p>

     *

     * @return 无命中时返回 <b>null</b>（对照 Go 的 {@code return nil}）

     */

    public Set<String> getExistingPageSlugsForKnowledge(String kbId, String knowledgeId) {

        List<String> slugs;

        try {

            slugs = service.wikiService.listSlugsBySourceRef(kbId, knowledgeId);

        } catch (Exception e) {

            log.warn("wiki ingest: ListSlugsBySourceRef({}) failed: {}", knowledgeId, e.getMessage());

            return null;

        }

        if (slugs == null || slugs.isEmpty()) {

            return null;

        }

        Set<String> out = new LinkedHashSet<>(slugs.size());

        for (String slug : slugs) {

            if (WikiConstants.PAGE_TYPE_INDEX.equals(slug)) {

                continue;

            }

            out.add(slug);

        }

        return out;

    }



    /**

     * 对照 Go {@code rebuildIndexPage}（L2108-2213）：刷新索引页上由 LLM 生成的导语。

     *

     * <p>历史：索引页曾把"导语 + 完整目录"作为单个数 MB 的 markdown blob 存在 content 里，

     * 每个 ingest 批次都重写整列——在数万页的 KB 上是每批次 O(N) 的 TOAST 写入。

     * 目录已被提升为结构化的 {@code GET /wiki/index} 端点（{@code GetIndexView}），

     * 本方法现在只维护导语。</p>

     *

     * <p>导语生命周期：</p>

     * <ul>

     *   <li>首次（空或历史占位符）：用全部文档摘要经 {@code WikiIndexIntroPrompt} 生成；</li>

     *   <li>带变更描述的后续调用：经 {@code WikiIndexIntroUpdatePrompt} 增量更新；</li>

     *   <li>没有变更描述：原样保留既有导语，不动版本号。</li>

     * </ul>

     * <p>新导语同时写进 {@code Content} 与 {@code Summary}，让仍回落到 Summary 的读取方

     * （老客户端、历史迁移数据）与实际渲染的那一列保持同步。</p>

     */

    public void rebuildIndexPage(LlmChatClient chatModel,
                                 WikiIngestPayload payload,
                                 String changeDesc,
                                 String lang,

                                 String customInstructions) {

        WikiPage indexPage = service.wikiService.getIndex(payload.knowledgeBaseId());

        if (indexPage == null) {

            return;

        }



        // 导语同时住在 Content 与 Summary。优先 Content（新的索引视图返回的就是它）；

        // 回落到 Summary 是为了兼容本次重构之前写入的行，

        // 好让增量更新 prompt 有东西可用。

        String existingIntro = GoStrings.trimSpace(indexPage.getContent());

        if (existingIntro.isEmpty()) {

            existingIntro = GoStrings.trimSpace(indexPage.getSummary());

        }

        // 识别历史的"导语 + 目录"载荷：那种行在导语之后紧跟围栏分隔的 "## Summary" 段，

        // 因此从第一个目录标题起全部裁掉，让回灌进更新 prompt 的导语长度有界。

        int dirIdx = existingIntro.indexOf("\n## ");

        if (dirIdx >= 0) {

            existingIntro = GoStrings.trimSpace(existingIntro.substring(0, dirIdx));

        }



        String intro;

        if (existingIntro.isEmpty() || WikiIngestService.LEGACY_INDEX_PLACEHOLDER.equals(existingIntro)) {

            // 首次生成：经 lite 投影拉最近更新的 top-N 摘要页。

            // CountByType 让我们能告诉 LLM "showing N of M"，

            // 从而在 KB 比采样集更大时诚实地交代。

            List<WikiIndexEntry> recentSummaries = service.wikiService.listByTypeRecent(

                    payload.knowledgeBaseId(), WikiConstants.PAGE_TYPE_SUMMARY,

                    WikiIngestConstants.INDEX_INTRO_SUMMARY_CAP);



            StringBuilder docSummaries = new StringBuilder();

            for (WikiIndexEntry e : recentSummaries) {

                docSummaries.append("<document>\n<title>").append(e.getTitle())

                        .append("</title>\n<summary>").append(e.getSummary())

                        .append("</summary>\n</document>\n\n");

            }

            long totalSummaries = recentSummaries.size();

            try {

                Map<String, Long> counts = service.wikiService.countByType(payload.knowledgeBaseId());

                if (counts != null && counts.get(WikiConstants.PAGE_TYPE_SUMMARY) != null) {

                    totalSummaries = counts.get(WikiConstants.PAGE_TYPE_SUMMARY);

                }

            } catch (Exception e) {

                // 计数失败不阻断导语生成（对照 Go 的 "A failure here doesn't block"）

                log.debug("wiki ingest: CountByType failed, using sample size for framing hint");

            }

            String framing = "";

            if (totalSummaries > recentSummaries.size() && !recentSummaries.isEmpty()) {

                framing = "(showing " + recentSummaries.size() + " most recent of "

                        + totalSummaries + " total documents)\n\n";

            }

            if (docSummaries.length() == 0) {

                docSummaries.append("(no documents yet)");

            }

            String generated;

            try {

                generated = service.llm.generateWithTemplate(chatModel, WikiPrompts.WIKI_INDEX_INTRO_PROMPT,

                        Map.of(

                                "DocumentSummaries", framing + docSummaries,

                                "Language", lang,

                                "CustomInstructions", customInstructions == null ? "" : customInstructions,

                                "InstructionScope", "wiki_content"));

                intro = GoStrings.trimSpace(generated);

            } catch (Exception e) {

                intro = "# Wiki Index\n\nThis wiki contains knowledge extracted from uploaded documents.\n";

            }

        } else if (changeDesc != null && !changeDesc.isEmpty()) {

            // 增量更新：只把既有导语 + 本批次的变更描述放进 prompt。

            // 这里刻意不再传完整的 DocumentSummaries——4 万文档时它每个批次都会

            // 重新灌满上下文，而变更描述块已经编码了 prompt 想要的"刚发生了什么"。

            String updated;

            try {

                updated = service.llm.generateWithTemplate(chatModel, WikiPrompts.WIKI_INDEX_INTRO_UPDATE_PROMPT,

                        Map.of(

                                "ExistingIntro", existingIntro,

                                "ChangeDescription", changeDesc,

                                "DocumentSummaries", "",

                                "Language", lang,

                                "CustomInstructions", customInstructions == null ? "" : customInstructions,

                                "InstructionScope", "wiki_content"));

                intro = GoStrings.trimSpace(updated);

            } catch (Exception e) {

                intro = existingIntro; // 出错时保留既有导语

            }

        } else {

            // 没有变更描述且已有导语：原样保留，避免为一次 no-op 递增版本号

            intro = existingIntro;

        }



        // 防御：某些 LLM 输出即使 prompt 没要求，也会渗出类似目录的段落。

        // 若刚生成的导语开始像历史载荷，就按读路径同样的规则在第一个 "\n## " 处裁掉，

        // 让 indexPage.Content 保持为长度有界的纯导语。

        int cut = intro.indexOf("\n## ");

        if (cut >= 0) {

            intro = GoStrings.trimSpace(intro.substring(0, cut));

        }



        indexPage.setContent(intro);

        indexPage.setSummary(intro);

        service.wikiService.updatePage(indexPage);

    }



    /**

     * 对照 Go {@code publishDraftPages}（L2235-2248）：摄取完成后把草稿页转为已发布，

     * 确保用户在摄取过程中看不到半成品页面。

     */

    public void publishDraftPages(String kbId, List<String> slugs) {

        if (slugs == null) {

            return;

        }

        for (String slug : slugs) {

            // 对照 Go：if err != nil || page == nil { continue }

            WikiPage page = service.wikiService.findPageBySlug(kbId, slug);

            if (page == null) {

                continue;

            }

            if (WikiConstants.STATUS_DRAFT.equals(page.getStatus())) {

                page.setStatus(WikiConstants.STATUS_PUBLISHED);

                try {

                    service.wikiService.updatePageMeta(page);

                } catch (Exception e) {

                    log.warn("wiki ingest: failed to publish page {}: {}", slug, e.getMessage());

                }

            }

        }

    }

    static final class TaxonomyNode {
        private final Map<String, TaxonomyNode> children =
                new java.util.TreeMap<>(GoStrings::compareByCodePoints);
    }
}
