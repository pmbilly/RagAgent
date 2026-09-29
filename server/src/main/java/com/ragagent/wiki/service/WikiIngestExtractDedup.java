package com.ragagent.wiki.service;




import java.util.ArrayList;

import java.util.Collections;


import java.util.LinkedHashMap;

import java.util.LinkedHashSet;

import java.util.List;

import java.util.Map;

import java.util.Set;





import com.fasterxml.jackson.databind.JsonNode;

import com.fasterxml.jackson.databind.ObjectMapper;







import com.ragagent.llm.LlmChatClient;







import com.ragagent.wiki.domain.WikiConstants;



import com.ragagent.wiki.domain.WikiPageLite;




import com.ragagent.wiki.prompt.WikiPrompts;


import org.slf4j.Logger;

import org.slf4j.LoggerFactory;





/**

 * 抽取项去重协作者:候选组渲染、LLM 去重仲裁与投影稳定化。

 *

 * <p>持有 {@link WikiIngestService} 回引以访问其依赖与队列原语;本类不得独立实例化。</p>

 */

final class WikiIngestExtractDedup {



    private static final Logger log = LoggerFactory.getLogger(WikiIngestExtractDedup.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();



    private final WikiIngestService service;



    WikiIngestExtractDedup(WikiIngestService service) {

        this.service = service;

    }



    /**

     * 对照 Go {@code writeDedupCandidateGroup}（L2256-2284）：把一个新条目连同

     * <b>它自己的</b>相似候选页渲染成嵌套在 {@code <candidates>} 下的 XML。

     *

     * <p>这种逐条目分组正是把去重模型约束成"局部决策"的机制。候选页保留它们的

     * aliases，好让模型仍握有接受一次合法合并所需的缩写 / 翻译信号。</p>

     *

     * <p>{@code slug=%q} / {@code type=%q} 用 Go 的 {@code fmt %q} 语义——

     * 对 slug（纯 ASCII）而言就是加双引号并转义 {@code "} 与 {@code \}。

     * Go 的 %q 还会对不可打印字符用反斜杠转义（{@code \xNN} / {@code uXXXX} 形态），

     * Java 侧实现了同样的规则（见 {@link #goQuote}）。</p>

     */

    static void writeDedupCandidateGroup(StringBuilder buf, ExtractedItem item,

                                         String itemType, List<WikiPageLite> candidates) {

        buf.append("  <item slug=").append(goQuote(item.getSlug()))

                .append(" type=").append(goQuote(itemType)).append(">\n");

        buf.append("    <name>").append(WikiTextUtils.xmlEscape(item.getName())).append("</name>\n");

        for (String alias : item.getAliases()) {

            if (alias == null || alias.isEmpty()) {

                continue;

            }

            buf.append("    <alias>").append(WikiTextUtils.xmlEscape(alias)).append("</alias>\n");

        }

        buf.append("    <candidates>\n");

        for (WikiPageLite p : candidates) {

            if (p == null) {

                continue;

            }

            buf.append("      <page slug=").append(goQuote(p.getSlug()))

                    .append(" type=").append(goQuote(p.getPageType())).append(">\n");

            buf.append("        <name>").append(WikiTextUtils.xmlEscape(p.getTitle())).append("</name>\n");

            for (String alias : p.getAliases()) {

                if (alias == null || alias.isEmpty()) {

                    continue;

                }

                buf.append("        <alias>").append(WikiTextUtils.xmlEscape(alias)).append("</alias>\n");

            }

            buf.append("      </page>\n");

        }

        buf.append("    </candidates>\n");

        buf.append("  </item>\n");

    }



    /**

     * 对照 Go 的 {@code %q} 动词（{@code strconv.Quote}）：给字符串加双引号，

     * 并转义 {@code "}、{@code \} 以及不可打印字符。

     *

     * <p>wiki slug 是 ASCII 且不含引号，因此实际输出就是 {@code "entity/foo"}。

     * 这里把规则补全是为了将来有人把非 ASCII 内容塞进来时不至于产出非法 XML

     * （Go 的 %q 同样保留可打印 Unicode 原样，只转义不可打印字符）。</p>

     */

    static String goQuote(String s) {

        if (s == null) {

            return "\"\"";

        }

        StringBuilder out = new StringBuilder(s.length() + 2);

        out.append('"');

        for (int i = 0; i < s.length(); ) {

            int cp = s.codePointAt(i);

            i += Character.charCount(cp);

            switch (cp) {

                case '"' -> out.append("\\\"");

                case '\\' -> out.append("\\\\");

                case '\n' -> out.append("\\n");

                case '\r' -> out.append("\\r");

                case '\t' -> out.append("\\t");

                default -> {

                    if (cp < 0x20 || cp == 0x7F) {

                        out.append(String.format("\\x%02x", cp));

                    } else if (cp > 0x7E && Character.getType(cp) == Character.CONTROL) {

                        out.append(String.format("\\u%04x", cp));

                    } else {

                        out.appendCodePoint(cp);

                    }

                }

            }

        }

        out.append('"');

        return out.toString();

    }



    /**

     * 对照 Go {@code deduplicateExtractedBatch}（L2306-2502）：用<b>一次 LLM 调用</b>

     * 把 entities 与 concepts 一起对既有 wiki 页面去重。

     *

     * <p>候选预筛走 {@code FindSimilarPages}（PG 侧是 {@code lower(title)} 上的

     * pg_trgm 三元组索引）：每个新条目发一次探测，所有条目的 top-K 命中并集就是候选集。

     * 这取代了历史"ListAllPages + Go 侧表面形式 Jaccard"的 O(P × N) 路径。</p>

     *

     * <p>另外逐条目记录"为<b>这个</b>条目召回了哪些 slug"（{@code itemCandidates}）。

     * prompt 只看到扁平化的并集，而 {@code dedupMergeRejectReason} 用这份逐条目作用域

     * 拒绝"目标是为另一个条目召回的"合并——那正是并集否则会放行的幻觉类型。</p>

     *

     * <h2>未接线 {@link WikiDedupSupport} 时的行为</h2>

     * <p>整个去重退化为"跳过 LLM 调用 + 恒等 stabilize"（见该接口的说明）。

     * 这是可见的降级，不是静默的错误答案。</p>

     */

    public ExtractedProjection deduplicateExtractedBatch(LlmChatClient chatModel,

                                                         String kbId,

                                                         List<ExtractedItem> entities,

                                                         List<ExtractedItem> concepts,

                                                         WikiBatchContext batchCtx) {

        WikiDedupSupport dedup = service.dedupSupport.getIfAvailable();

        if (dedup == null) {

            log.info("wiki ingest: dedup support not wired, skipping deduplication for {} + {} items",

                    entities.size(), concepts.size());

            return new ExtractedProjection(entities, concepts);

        }



        Map<String, WikiPageLite> candidatePages = new LinkedHashMap<>();

        Map<String, Set<String>> itemCandidates = new LinkedHashMap<>();



        // 逐条目探测：用它的 name 与每个 alias 各查一次 top-K，并集即候选

        for (List<ExtractedItem> group : List.of(entities, concepts)) {

            for (ExtractedItem item : group) {

                List<String> queries = new ArrayList<>(1 + item.getAliases().size());

                if (!item.getName().isEmpty()) {

                    queries.add(item.getName());

                }

                for (String alias : item.getAliases()) {

                    if (alias != null && !alias.isEmpty()) {

                        queries.add(alias);

                    }

                }

                Set<String> own = itemCandidates.computeIfAbsent(item.getSlug(), k -> new LinkedHashSet<>());

                for (String q : queries) {

                    List<WikiPageLite> pages;

                    try {

                        pages = service.wikiService.findSimilarPages(kbId, q,

                                List.of(WikiConstants.PAGE_TYPE_ENTITY, WikiConstants.PAGE_TYPE_CONCEPT),

                                WikiDedupSupport.DEDUP_CANDIDATE_TOP_K);

                    } catch (Exception e) {

                        log.warn("wiki ingest: dedup FindSimilarPages({}) failed: {}", q, e.getMessage());

                        continue;

                    }

                    for (WikiPageLite p : pages) {

                        if (p == null || p.getSlug().isEmpty()) {

                            continue;

                        }

                        candidatePages.putIfAbsent(p.getSlug(), p);

                        own.add(p.getSlug());

                    }

                }

            }

        }



        dedup.attachExactIdentityPages(

                kbId, WikiConstants.PAGE_TYPE_ENTITY, entities, candidatePages, itemCandidates, batchCtx);

        dedup.attachExactIdentityPages(

                kbId, WikiConstants.PAGE_TYPE_CONCEPT, concepts, candidatePages, itemCandidates, batchCtx);



        // 在问模型"语义/别名变体"之前，先确定性地解析"同类型同标题"的候选。

        // 除了省掉明显情形的一次 LLM 调用，它还让已物化的页面在下方的身份预留中成为权威。

        Map<String, String> exactTargets = new LinkedHashMap<>();

        Map<String, String> mergeTargets = new LinkedHashMap<>();

        dedup.collectExactIdentityTargets(

                entities, WikiConstants.PAGE_TYPE_ENTITY, itemCandidates, candidatePages, exactTargets);

        dedup.collectExactIdentityTargets(

                concepts, WikiConstants.PAGE_TYPE_CONCEPT, itemCandidates, candidatePages, exactTargets);



        if (candidatePages.isEmpty()) {

            log.info("wiki ingest: no similar existing pages found for {} new items",

                    entities.size() + concepts.size());

            return stabilize(dedup, kbId, entities, concepts, mergeTargets, exactTargets, batchCtx);

        }

        log.info("wiki ingest: {} similar existing pages selected for {} new items",

                candidatePages.size(), entities.size() + concepts.size());



        // 把每个新条目与<b>只为它自己</b>召回的既有页面分成一组。给模型看两个扁平列表

        // （全部新条目 × 全部候选）会诱发跨条目错配——它无从判断哪个候选与哪个条目相关，

        // 于是弱模型会把仅仅共处同一 prompt 的不相干 slug 配成对。逐条目短名单把去重

        // 变成针对少量真正相似页面的局部 yes/no 决策，跨条目配对在结构上无从表达。

        // 没有候选的条目整个省略（它们无法合并，只会增加幻觉面与 token）。

        StringBuilder candBuf = new StringBuilder();

        int[] groups = {0};

        for (ExtractedItem item : entities) {

            renderDedupGroup(candBuf, groups, item, "entity",

                    itemCandidates, candidatePages, exactTargets);

        }

        for (ExtractedItem item : concepts) {

            renderDedupGroup(candBuf, groups, item, "concept",

                    itemCandidates, candidatePages, exactTargets);

        }

        if (groups[0] == 0) {

            // 每个条目都已被精确解析，或没有安全的语义候选

            return stabilize(dedup, kbId, entities, concepts, mergeTargets, exactTargets, batchCtx);

        }



        String dedupeJson;

        try {

            dedupeJson = service.llm.generateWithTemplate(chatModel, WikiPrompts.WIKI_DEDUPLICATION_PROMPT,

                    Map.of("Candidates", candBuf.toString()));

        } catch (Exception e) {

            log.warn("wiki ingest: deduplication LLM call failed: {}", e.getMessage());

            return stabilize(dedup, kbId, entities, concepts, mergeTargets, exactTargets, batchCtx);

        }



        dedupeJson = WikiTextUtils.cleanLLMJSON(dedupeJson);

        JsonNode parsed;

        try {

            parsed = MAPPER.readTree(dedupeJson);

        } catch (Exception e) {

            log.warn("wiki ingest: failed to parse dedup JSON: {}\nRaw: {}", e.getMessage(), dedupeJson);

            return stabilize(dedup, kbId, entities, concepts, mergeTargets, exactTargets, batchCtx);

        }

        JsonNode merges = parsed == null ? null : parsed.get("merges");



        for (List<ExtractedItem> group : List.of(entities, concepts)) {

            for (ExtractedItem item : group) {

                // 已被确定性精确解析的条目跳过——它们不需要模型判断

                if (!exactTargets.getOrDefault(item.getSlug(), "").isEmpty()) {

                    continue;

                }

                if (merges == null || !merges.isObject()) {

                    continue;

                }

                JsonNode target = merges.get(item.getSlug());

                if (target == null || !target.isTextual()) {

                    continue;

                }

                String existingSlug = target.textValue();

                String reason = dedup.dedupMergeRejectReason(

                        item.getSlug(), existingSlug, itemCandidates.get(item.getSlug()));

                if (reason != null && !reason.isEmpty()) {

                    log.warn("wiki ingest: dedup rejected {} → {} ({})",

                            item.getSlug(), existingSlug, reason);

                    continue;

                }

                log.info("wiki ingest: dedup merge {} → {}", item.getSlug(), existingSlug);

                mergeTargets.put(item.getSlug(), existingSlug);

            }

        }



        return stabilize(dedup, kbId, entities, concepts, mergeTargets, exactTargets, batchCtx);

    }



    void renderDedupGroup(StringBuilder candBuf, int[] groups,

                                  ExtractedItem item, String itemType,

                                  Map<String, Set<String>> itemCandidates,

                                  Map<String, WikiPageLite> candidatePages,

                                  Map<String, String> exactTargets) {

        if (!exactTargets.getOrDefault(item.getSlug(), "").isEmpty()) {

            return;

        }

        Set<String> cset = itemCandidates.get(item.getSlug());

        if (cset == null || cset.isEmpty()) {

            return;

        }

        List<String> slugs = new ArrayList<>(cset.size());

        for (String slug : cset) {

            // 跳过条目自己的 slug：slug 完全相同的既有页是"重新摄取/更新"，

            // 不是合并目标

            if (slug.equals(item.getSlug())) {

                continue;

            }

            if (candidatePages.containsKey(slug)) {

                slugs.add(slug);

            }

        }

        if (slugs.isEmpty()) {

            return;

        }

        Collections.sort(slugs);

        List<WikiPageLite> pages = new ArrayList<>(slugs.size());

        for (String slug : slugs) {

            pages.add(candidatePages.get(slug));

        }

        writeDedupCandidateGroup(candBuf, item, itemType, pages);

        groups[0]++;

    }



    /** 对照 Go 的 {@code stabilize} 闭包（L2386-2392） */

    ExtractedProjection stabilize(WikiDedupSupport dedup, String kbId,

                                          List<ExtractedItem> entities, List<ExtractedItem> concepts,

                                          Map<String, String> mergeTargets,

                                          Map<String, String> exactTargets,

                                          WikiBatchContext batchCtx) {

        List<ExtractedItem> es = dedup.stabilizeExtractedIdentities(

                kbId, WikiConstants.PAGE_TYPE_ENTITY, entities, mergeTargets, exactTargets, batchCtx);

        List<ExtractedItem> cs = dedup.stabilizeExtractedIdentities(

                kbId, WikiConstants.PAGE_TYPE_CONCEPT, concepts, mergeTargets, exactTargets, batchCtx);

        return new ExtractedProjection(es, cs);

    }



    /** 对照 Go {@code deduplicateExtractedBatch} 的 {@code ([]extractedItem, []extractedItem)} 返回。 */

    public record ExtractedProjection(List<ExtractedItem> entities, List<ExtractedItem> concepts) {}

}
